package com.ruangfoto.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal fun cameraPhotoKind(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "jpeg"
    "arw", "nef", "nrw", "cr2", "cr3", "dng", "raf", "orf", "rw2", "pef" -> "raw"
    else -> null
}
internal fun cameraChildPath(parent: String, name: String): String {
    require(parent.startsWith('/') && name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.code < 32 })
    return if (parent == "/") "/$name" else "$parent/$name"
}

/** Separate JNI producer; the native camera-owner loop executes all commands serially. */
class CameraBrowser(private val activity: Activity, private val destination: () -> File?, private val imported: () -> Unit) {
    private data class Entry(val name: String, val directory: Boolean)
    private data class Metadata(val size: Long, val note: String)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue())
    private val requestId = AtomicLong()
    private val thumbnails = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val metadata = mutableMapOf<String, Metadata>()
    private val loading = mutableSetOf<String>()
    private val selected = linkedSetOf<String>()
    private var entries = emptyList<Entry>()
    private var shown = emptyList<Entry>()
    private var folder = "/"
    private var session = 0L
    @Volatile private var generation = 0
    @Volatile private var closed = true
    @Volatile private var importing = false
    @Volatile private var cancelImport = false
    private var dialog: AlertDialog? = null
    private lateinit var path: TextView
    private lateinit var status: TextView
    private lateinit var filter: Spinner
    private lateinit var up: Button
    private lateinit var refresh: Button
    private lateinit var importButton: Button
    private lateinit var cancel: Button
    private lateinit var list: ListView
    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(12, 8, 12, 8)
                addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE; contentDescription = "Camera thumbnail" }, LinearLayout.LayoutParams(144, 144))
                addView(CheckBox(activity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val entry = shown[position]
            val key = cameraChildPath(folder, entry.name)
            val image = row.getChildAt(0) as ImageView
            val check = row.getChildAt(1) as CheckBox
            image.setImageBitmap(thumbnails.get(key))
            val meta = metadata[key]
            val detail = when {
                entry.directory -> "Folder · tap to open"
                meta == null -> "${cameraPhotoKind(entry.name)?.uppercase() ?: "Photo"} · loading size/preview…"
                else -> "${cameraPhotoKind(entry.name)?.uppercase()} · ${if (meta.size >= 0) "${meta.size / 1024} KB" else "size unavailable"}${if (meta.note.isNotBlank()) "\n${meta.note}" else ""}"
            }
            check.setOnCheckedChangeListener(null)
            check.text = "${entry.name}\n$detail"
            check.isChecked = !entry.directory && selected.contains(key)
            check.isEnabled = !importing
            check.setOnClickListener {
                if (entry.directory) { if (!importing) load(key) }
                else { if (check.isChecked) selected.add(key) else selected.remove(key); updateSelection() }
            }
            if (!entry.directory && meta == null && loading.add(key) && !importing) loadPreview(entry, key)
            return row
        }
    }

    fun show() {
        if (!closed) return
        session = nativeSession()
        if (session == 0L || destination() == null) {
            AlertDialog.Builder(activity).setMessage("Connect a camera and wait for initialization before browsing.").setPositiveButton("OK", null).show()
            return
        }
        closed = false; importing = false; cancelImport = false
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 12, 20, 12) }
        path = TextView(activity).also { form.addView(it) }
        status = TextView(activity).also { form.addView(it) }
        val navigation = LinearLayout(activity).also { form.addView(it) }
        up = Button(activity).apply { text = "Up"; navigation.addView(this); setOnClickListener { load(folder.substringBeforeLast('/').ifEmpty { "/" }) } }
        refresh = Button(activity).apply { text = "Refresh"; navigation.addView(this); setOnClickListener { load(folder) } }
        filter = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("All photos", "JPEG", "RAW")); form.addView(this)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { applyFilter() }
                override fun onNothingSelected(parent: AdapterView<*>?) { }
            }
        }
        list = ListView(activity).apply { adapter = this@CameraBrowser.adapter; form.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) }
        importButton = Button(activity).apply { text = "Import selected (0)"; isEnabled = false; form.addView(this); setOnClickListener { importSelected() } }
        cancel = Button(activity).apply { text = "Cancel import"; isEnabled = false; form.addView(this); setOnClickListener { cancelImport = true; cancelRequest(); status.text = "Cancelling; completed photos are kept…" } }
        dialog = AlertDialog.Builder(activity).setTitle("Photos on camera").setView(form).setNegativeButton("Close", null).create().also {
            it.setOnDismissListener { dismiss() }; it.show()
            it.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.85).toInt())
        }
        load("/")
    }

    private fun cancelRequest() { val id = requestId.get(); if (id != 0L) nativeCancel(id) }
    private fun load(target: String) {
        if (closed || importing) return
        val version = ++generation
        cancelRequest(); worker.queue.clear(); loading.clear(); selected.clear(); entries = emptyList(); shown = emptyList()
        metadata.clear(); thumbnails.evictAll()
        folder = target; path.text = target; status.text = "Reading camera folder…"; up.isEnabled = target != "/"; updateSelection(); adapter.notifyDataSetChanged()
        worker.execute {
            try {
                val response = request("list", target, "", version)
                val items = response.getJSONArray("entries")
                val result = (0 until items.length()).map { items.getJSONObject(it).let { row -> Entry(row.getString("name"), row.getBoolean("directory")) } }
                handler.post { if (current(version)) { entries = result; applyFilter(); status.text = "${entries.size} entries · physical shutter receiving continues between operations" } }
            } catch (error: Exception) { report(version, error.message ?: "Cannot read camera folder") }
        }
    }
    private fun applyFilter() {
        if (closed || !::filter.isInitialized) return
        val mode = listOf("all", "jpeg", "raw")[filter.selectedItemPosition.coerceAtLeast(0)]
        shown = entries.filter { it.directory || cameraPhotoKind(it.name)?.let { kind -> mode == "all" || kind == mode } == true }
        adapter.notifyDataSetChanged()
    }
    private fun updateSelection() { importButton.text = "Import selected (${selected.size})"; importButton.isEnabled = selected.isNotEmpty() && !importing }
    private fun current(version: Int) = !closed && generation == version
    private fun report(version: Int, message: String) { handler.post { if (current(version)) status.text = message } }

    private fun request(kind: String, directory: String, name: String, version: Int, preview: (ByteArray) -> Unit = {}): JSONObject {
        if (!current(version) || nativeSession() != session) error("Camera session changed or disconnected")
        val id = nativeSubmit(session, kind, directory, name)
        if (id == 0L) error("Camera unavailable, request cancelled, or request queue full")
        requestId.set(id)
        try {
            while (current(version)) {
                val response = JSONObject(nativePoll(id))
                if (response.optBoolean("done")) {
                    if (response.has("error")) error(response.getString("error"))
                    if (kind == "preview") nativePreview(id)?.let(preview)
                    return response
                }
                if (kind == "import" && cancelImport) error("Cancelled")
                if (kind == "import" && response.optInt("percent", -1) >= 0) report(version, "Importing $name: ${response.getInt("percent")}%")
                Thread.sleep(75)
            }
            error("Cancelled")
        } finally { requestId.compareAndSet(id, 0); nativeRelease(id) }
    }
    private fun loadPreview(entry: Entry, key: String) {
        val version = generation
        val directory = folder
        worker.execute {
            var bitmap: Bitmap? = null
            try {
                val response = request("preview", directory, entry.name, version) { bytes ->
                    if (bytes.isNotEmpty()) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) sample *= 2
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }
                val image = bitmap
                handler.post {
                    if (current(version)) {
                        metadata[key] = Metadata(response.optLong("size", -1), response.optString("note"))
                        if (image != null) thumbnails.put(key, image)
                        loading.remove(key); adapter.notifyDataSetChanged()
                    } else image?.recycle()
                }
            } catch (error: Exception) {
                bitmap?.recycle()
                handler.post { if (current(version)) { metadata[key] = Metadata(-1, error.message ?: "Preview unavailable"); loading.remove(key); adapter.notifyDataSetChanged() } }
            }
        }
    }
    private fun importSelected() {
        if (importing || selected.isEmpty()) return
        cancelRequest(); worker.queue.clear(); loading.clear()
        val version = ++generation
        val directory = folder
        val names = selected.map { it.substringAfterLast('/') }
        importing = true; cancelImport = false
        up.isEnabled = false; refresh.isEnabled = false; filter.isEnabled = false; cancel.isEnabled = true; updateSelection(); adapter.notifyDataSetChanged()
        worker.execute {
            var done = 0
            val failures = mutableListOf<String>()
            for ((index, name) in names.withIndex()) {
                if (!current(version) || cancelImport) break
                report(version, "Importing ${index + 1}/${names.size}: $name")
                try { request("import", directory, name, version); done++; handler.post { if (current(version)) imported() } }
                catch (error: Exception) { if (!cancelImport && current(version)) failures.add("$name: ${error.message}") }
            }
            handler.post {
                if (current(version)) {
                    importing = false; selected.clear(); up.isEnabled = folder != "/"; refresh.isEnabled = true; filter.isEnabled = true; cancel.isEnabled = false
                    updateSelection(); adapter.notifyDataSetChanged(); imported()
                    status.text = "$done imported · ${failures.size} failed${if (cancelImport) " · cancelled" else ""}"
                    if (failures.isNotEmpty()) AlertDialog.Builder(activity).setTitle("Import failures").setMessage(failures.joinToString("\n\n")).setPositiveButton("OK", null).show()
                }
            }
        }
    }
    private fun dismiss() { closed = true; ++generation; cancelImport = true; cancelRequest(); worker.queue.clear(); nativeCloseBrowser(session) }
    fun close() { dialog?.dismiss(); dismiss(); worker.shutdownNow(); thumbnails.evictAll(); metadata.clear() }

    private external fun nativeSession(): Long
    private external fun nativeSubmit(session: Long, kind: String, folder: String, name: String): Long
    private external fun nativePoll(id: Long): String
    private external fun nativePreview(id: Long): ByteArray?
    private external fun nativeRelease(id: Long)
    private external fun nativeCancel(id: Long)
    private external fun nativeCloseBrowser(session: Long)
}
