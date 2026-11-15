package com.ruangfoto.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

internal class UploadUi(private val activity: Activity, private val button: Button, private val status: TextView,
                        private val session: () -> File?, private val receiving: () -> Boolean) {
    private val store = UploadStore.get(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var visible = false
    private var closed = false
    private var testing: FtpTransfer? = null
    private var dialog: AlertDialog? = null
    private val refresh = object : Runnable {
        override fun run() {
            if (!visible || closed) return
            UploadService.watching = receiving()
            worker.execute {
                val summary = runCatching { store.summary() }.getOrElse { "Upload storage unavailable" }
                val canStart = runCatching { !store.paused && (store.hasPending() || (receiving() && session()?.let { store.rule(it).enabled } == true)) }.getOrDefault(false)
                handler.post {
                    if (visible && !closed) {
                        status.text = listOf(UploadService.progress, summary).filter { it.isNotBlank() }.joinToString("\n")
                        if (canStart && !UploadService.running) start()
                    }
                }
            }
            handler.postDelayed(this, 1_000)
        }
    }
    init { button.setOnClickListener { show() } }
    fun resume() {
        visible = true
        // Recover finalized files written just before process death, even without a camera attached.
        worker.execute { runCatching { store.scan() } }
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }
    fun stop() { visible = false; handler.removeCallbacks(refresh) }
    fun close() { closed = true; stop(); testing?.cancel(); dialog?.dismiss(); worker.shutdownNow() }

    private fun start() {
        if (!visible || closed) return
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Transfer may run without notification permission; Android still lists its foreground task.
            status.text = "Uploads active; allow notifications in Android settings to see transfer progress"
        }
        try { UploadService.startFromVisibleApp(activity) }
        catch (_: Exception) { store.paused = true; status.text = "Android blocked background transfer; reopen the app and tap Resume" }
    }

    private fun show() {
        val directory = session() ?: return
        val saved = runCatching { store.settings() }
        val settings = saved.getOrNull()
        val rule = store.rule(directory)
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 16) }
        fun label(text: String) { form.addView(TextView(activity).apply { this.text = text; setPadding(0, 12, 0, 4) }) }
        fun field(title: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            label(title)
            return EditText(activity).apply { inputType = type; setSingleLine(true); setText(value); form.addView(this) }
        }
        label("Custom FTP server · queued files use this destination. Changing destination disables session auto-upload; enable it again explicitly.")
        saved.exceptionOrNull()?.let { label("Saved password unavailable. Enter credentials and save again.") }
        val protocol = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("FTPS (explicit TLS)", "FTP (unencrypted)"))
            setSelection(if (settings?.tls != false) 0 else 1); form.addView(this)
        }
        label("FTP sends passwords and photos unencrypted. Use FTPS on public networks. SFTP is not FTP and is not supported here.")
        val host = field("Host (no ftp:// prefix)", settings?.host ?: "")
        val port = field("Port", (settings?.port ?: 21).toString(), InputType.TYPE_CLASS_NUMBER)
        val user = field("Username", settings?.user ?: "")
        val password = field("Password", settings?.password ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val folder = field("Existing remote folder", settings?.folder ?: "/")
        val result = TextView(activity).also { form.addView(it) }
        fun values() = FtpSettings(host.text.toString().trim(), port.text.toString().toIntOrNull() ?: 0,
            user.text.toString(), password.text.toString(), folder.text.toString().trim(), protocol.selectedItemPosition == 0).also { it.validate() }
        fun action(title: String, block: () -> Unit): Button = Button(activity).apply {
            text = title; form.addView(this)
            setOnClickListener { try { block() } catch (error: Exception) { result.text = error.message ?: "Action failed" } }
        }
        action("Save server") {
            store.saveSettings(values())
            result.text = "Server saved. Enable this session below. Pending jobs must be finished or cancelled before changing destination."
        }
        val test = action("Test connection + write permission") { }
        test.setOnClickListener {
            try {
                val value = values()
                test.isEnabled = false; result.text = "Testing connection, write, rename and delete…"
                val connection = FtpTransfer(); testing = connection
                worker.execute {
                    val message = try { connection.test(value); "Connection, write, rename and delete succeeded" }
                    catch (error: Exception) { when (error) {
                        is javax.net.ssl.SSLException -> "FTPS certificate/TLS verification failed"
                        is FtpFailure -> error.message ?: "FTP test failed"
                        else -> "Connection failed; check host, port, network and server access"
                    } }
                    handler.post { if (!closed && dialog?.isShowing == true) { result.text = message; test.isEnabled = true } }
                    testing = null
                }
            } catch (error: Exception) { result.text = error.message }
        }
        label("Session: ${directory.name}")
        val enabled = CheckBox(activity).apply { text = "Automatically upload NEW photos in this session"; isChecked = rule.enabled; form.addView(this) }
        val mode = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("JPEG + RAW", "JPEG only", "RAW only"))
            setSelection(listOf("all", "jpeg", "raw").indexOf(rule.mode).coerceAtLeast(0)); form.addView(this)
        }
        val existing = CheckBox(activity).apply { text = "Also enqueue existing photos (not already uploaded to this destination)"; form.addView(this) }
        label("Disabling auto-upload stops discovering new files. Already queued uploads remain; use Pause or Cancel queue to stop them.")
        action("Apply session settings") {
            store.configureSession(directory, enabled.isChecked, listOf("all", "jpeg", "raw")[mode.selectedItemPosition], existing.isChecked && enabled.isChecked)
            result.text = "Session settings saved"
            if (enabled.isChecked && !store.paused) start()
        }
        action("Pause uploads") { UploadService.pause(activity); result.text = "Paused; queued files and local photos are kept" }
        action("Resume / retry failed") {
            store.retryFailed(); store.paused = false
            UploadService.watching = receiving(); start(); result.text = "Uploads resumed"
        }
        action("Cancel pending and failed uploads") {
            AlertDialog.Builder(activity).setTitle("Cancel queued uploads?")
                .setMessage("Local photos will NOT be deleted. Transfers already completed on the server stay there. This releases the destination lock so you can correct host, username or folder.")
                .setNegativeButton("Keep queue", null).setPositiveButton("Cancel uploads") { _, _ ->
                    UploadService.pause(activity); store.cancelUnfinished(); result.text = "Queue cancelled; local photos retained"
                }.show()
        }
        label("Uploads continue with a foreground notification. Force-stop stops transfers; reopen and Resume to recover the queue. Android may impose a background transfer time limit.")
        dialog = AlertDialog.Builder(activity).setTitle("FTP auto-upload").setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton("Close", null).create().also {
                it.setOnDismissListener { testing?.cancel(); testing = null; password.text.clear() }
                it.show()
            }
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 210)
        }
    }
}
