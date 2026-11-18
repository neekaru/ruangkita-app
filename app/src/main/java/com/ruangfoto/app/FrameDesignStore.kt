package com.ruangfoto.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class FrameDesign(
    val overlay: String? = null,
    val labels: List<FrameLabel> = emptyList(),
    val canvas: FrameCanvas = FrameCanvas.MATCH_PNG
)

internal data class FrameLabel(
    val text: String,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val size: Float = 0.06f,
    val color: Int = -1,
    val font: String? = null
)

internal enum class FrameCanvas(val title: String) {
    MATCH_PNG("Match PNG"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    SQUARE("Square")
}

internal data class FrameRevision(val id: String, val createdAt: Long, val title: String)

/** Capture and editor read the same immutable revision through an atomically replaced pointer. */
internal class FrameDesignStore(directory: File) {
    private val root = File(directory, STORE_DIRECTORY)
    private val assets = File(root, ASSETS_DIRECTORY)
    private val revisionsDirectory = File(root, REVISIONS_DIRECTORY)
    private val activeFile = File(root, ACTIVE_NAME)
    private val previewSecondsFile = File(root, PREVIEW_SECONDS_NAME)

    fun activeDesign(): FrameDesign? = activeRevisionId()?.let(::loadRevision)

    fun activeOverlay(): File? {
        val id = activeRevisionId() ?: return null
        val overlay = File(File(revisionsDirectory, id), OVERLAY_NAME)
        if (!overlay.isFile) throw IOException("The active frame overlay is missing.")
        return overlay
    }

    private fun activeRevisionId(): String? {
        val id = try {
            String(Files.readAllBytes(activeFile.toPath()), Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            return null
        }
        if (!SAFE_ID.matches(id)) throw IOException("The active frame revision is unreadable.")
        return id
    }

    fun revisions(): List<FrameRevision> {
        return revisionsDirectory.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { revisionDirectory ->
                runCatching {
                    val metadata = JSONObject(File(revisionDirectory, REVISION_NAME).readText())
                    val id = metadata.getString("id")
                    require(id == revisionDirectory.name)
                    require(File(revisionDirectory, DESIGN_NAME).isFile)
                    FrameRevision(id, metadata.getLong("createdAt"), metadata.getString("title"))
                }.getOrNull()
            }
            .sortedWith(compareByDescending<FrameRevision> { it.createdAt }.thenByDescending { it.id })
    }

    fun loadRevision(id: String): FrameDesign {
        require(SAFE_ID.matches(id)) { "Unknown frame revision." }
        val revisionDirectory = File(revisionsDirectory, id)
        if (!revisionDirectory.isDirectory) throw IOException("That frame revision is no longer available.")
        val recorded = runCatching {
            JSONObject(File(revisionDirectory, REVISION_NAME).readText()).getString("id")
        }.getOrNull()
        if (recorded != id) throw IOException("That frame revision is unreadable.")
        return decodeDesign(File(revisionDirectory, DESIGN_NAME))
    }

    @Synchronized
    fun save(design: FrameDesign) {
        validate(design)
        require(design.overlay != null || design.labels.isNotEmpty()) {
            "Add a frame image or at least one text label."
        }
        require(design.labels.none { it.text.isBlank() }) {
            "Give every label some text or remove it."
        }
        ensureDirectory(root)
        ensureDirectory(assets)
        ensureDirectory(revisionsDirectory)

        val json = encodeDesign(design)
        val createdAt = System.currentTimeMillis()
        val id = "rev-$createdAt-${UUID.randomUUID().toString().take(8)}"
        val revisionDirectory = File(revisionsDirectory, id)
        val staging = Files.createTempDirectory(revisionsDirectory.toPath(), ".pending-").toFile()
        try {
            val preview = renderPreview(design, MAX_RENDER_SIDE)
            try {
                writePng(preview, File(staging, OVERLAY_NAME))
            } finally {
                preview.recycle()
            }
            File(staging, DESIGN_NAME).writeBytes(json)
            File(staging, REVISION_NAME).writeBytes(revisionMetadata(id, createdAt, design))
            Files.move(staging.toPath(), revisionDirectory.toPath(), ATOMIC_MOVE)
            writeAtomically(activeFile) { it.writeText(id) }
        } catch (error: Exception) {
            revisionDirectory.deleteRecursively()
            throw error
        } finally {
            staging.deleteRecursively()
        }
    }

    @Synchronized
    fun disable() {
        Files.deleteIfExists(activeFile.toPath())
    }

    /** Validates and stores a PNG frame with a transparent opening; returns its internal asset reference. */
    fun importOverlay(input: InputStream): String =
        importAsset(input, MAX_OVERLAY_BYTES, "overlay-", ".png", ::validateImportedOverlay)

    /** Validates and stores a TrueType/OpenType font; returns its internal asset reference. */
    fun importFont(input: InputStream): String =
        importAsset(input, MAX_FONT_BYTES, "font-", ".ttf", ::validateImportedFont)

    fun renderPreview(design: FrameDesign, maxSide: Int = 1000): Bitmap {
        validate(design)
        val longSide = maxSide.coerceIn(MIN_PREVIEW_SIDE, MAX_RENDER_SIDE)
        val artwork = design.overlay?.let(::decodeArtwork)
        try {
            val (width, height) = canvasSize(design.canvas, longSide, artwork)
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(result)
                if (artwork != null) {
                    val scale = maxOf(width / artwork.width.toFloat(), height / artwork.height.toFloat())
                    val left = (width - artwork.width * scale) / 2f
                    val top = (height - artwork.height * scale) / 2f
                    canvas.drawBitmap(artwork, null,
                        RectF(left, top, left + artwork.width * scale, top + artwork.height * scale),
                        Paint(Paint.FILTER_BITMAP_FLAG))
                }
                if (design.labels.isNotEmpty()) {
                    val typefaces = HashMap<String, Typeface>()
                    for (label in design.labels) drawLabel(canvas, label, typefaces)
                }
                return result
            } catch (error: Throwable) {
                result.recycle()
                throw error
            }
        } finally {
            artwork?.recycle()
        }
    }

    fun previewDurationSeconds(): Int =
        runCatching { previewSecondsFile.readText().trim().toInt() }.getOrNull()
            ?.coerceIn(0, MAX_PREVIEW_SECONDS) ?: DEFAULT_PREVIEW_SECONDS

    @Synchronized
    fun setPreviewDurationSeconds(seconds: Int) {
        require(seconds in 0..MAX_PREVIEW_SECONDS) {
            "Preview duration must be between 0 and $MAX_PREVIEW_SECONDS seconds."
        }
        ensureDirectory(root)
        writeAtomically(previewSecondsFile) { it.writeText(seconds.toString()) }
    }

    private fun validate(design: FrameDesign) {
        require(design.overlay == null || SAFE_REF.matches(design.overlay)) { "Invalid frame artwork reference." }
        require(design.labels.size <= MAX_LABELS) { "Use no more than $MAX_LABELS text labels." }
        for (label in design.labels) {
            require(label.text.length <= MAX_LABEL_CHARS) {
                "Each label can use up to $MAX_LABEL_CHARS characters."
            }
            require(label.x.isFinite() && label.x in 0f..1f) { "Label position must stay inside the frame." }
            require(label.y.isFinite() && label.y in 0f..1f) { "Label position must stay inside the frame." }
            require(label.size.isFinite() && label.size in MIN_TEXT_SIZE..MAX_TEXT_SIZE) {
                "Label size must be between $MIN_TEXT_SIZE and $MAX_TEXT_SIZE of the shortest side."
            }
            require(label.font == null || SAFE_REF.matches(label.font)) { "Invalid font reference." }
        }
    }

    private fun encodeDesign(design: FrameDesign): ByteArray {
        val labels = JSONArray()
        for (label in design.labels) {
            labels.put(JSONObject().apply {
                put("text", label.text)
                put("x", label.x.toDouble())
                put("y", label.y.toDouble())
                put("size", label.size.toDouble())
                put("color", label.color)
                put("font", label.font ?: JSONObject.NULL)
            })
        }
        val json = JSONObject().apply {
            put("overlay", design.overlay ?: JSONObject.NULL)
            put("canvas", design.canvas.name)
            put("labels", labels)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodeDesign(file: File): FrameDesign {
        if (!file.isFile) throw IOException("No saved frame design.")
        if (file.length() > MAX_DESIGN_BYTES) throw IOException("Saved frame design is unreadable.")
        val json = try {
            JSONObject(file.readText())
        } catch (e: JSONException) {
            throw IOException("Saved frame design is unreadable.", e)
        }
        val design = try {
            val labelsJson = json.getJSONArray("labels")
            val labels = ArrayList<FrameLabel>(labelsJson.length())
            for (index in 0 until labelsJson.length()) {
                val label = labelsJson.getJSONObject(index)
                labels += FrameLabel(
                    text = label.getString("text"),
                    x = label.getDouble("x").toFloat(),
                    y = label.getDouble("y").toFloat(),
                    size = label.getDouble("size").toFloat(),
                    color = label.optInt("color", -1),
                    font = if (label.isNull("font")) null else label.optString("font").takeIf { it.isNotEmpty() }
                )
            }
            FrameDesign(
                overlay = if (json.isNull("overlay")) null else json.optString("overlay").takeIf { it.isNotEmpty() },
                labels = labels,
                canvas = FrameCanvas.valueOf(json.getString("canvas"))
            )
        } catch (e: JSONException) {
            throw IOException("Saved frame design is unreadable.", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Saved frame design is unreadable.", e)
        }
        try {
            validate(design)
        } catch (e: IllegalArgumentException) {
            throw IOException("Saved frame design is invalid: ${e.message}", e)
        }
        return design
    }

    private fun revisionMetadata(id: String, createdAt: Long, design: FrameDesign): ByteArray =
        JSONObject().apply {
            put("id", id)
            put("createdAt", createdAt)
            put("title", revisionTitle(design))
        }.toString().toByteArray(Charsets.UTF_8)

    private fun revisionTitle(design: FrameDesign): String = buildList {
        add(design.canvas.title)
        add(if (design.overlay != null) "frame" else "text")
        if (design.labels.isNotEmpty()) {
            add("${design.labels.size} label${if (design.labels.size == 1) "" else "s"}")
        }
    }.joinToString(" · ")

    private fun decodeArtwork(reference: String): Bitmap {
        val file = File(assets, reference)
        if (!file.isFile) throw IOException("The frame artwork is missing. Import the PNG again.")
        return BitmapFactory.decodeFile(file.path)
            ?: throw IOException("The frame artwork is unreadable. Import the PNG again.")
    }

    private fun drawLabel(canvas: Canvas, label: FrameLabel, typefaces: MutableMap<String, Typeface>) {
        val shortestSide = minOf(canvas.width, canvas.height).toFloat()
        val customTypeface = label.font?.let { typeface(it, typefaces) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = label.color
            textSize = label.size * shortestSide
            textAlign = Paint.Align.CENTER
            typeface = customTypeface ?: Typeface.DEFAULT
        }
        val lines = label.text.split('\n')
        val lineHeight = paint.fontSpacing
        var baseline = label.y * canvas.height - lineHeight * (lines.size - 1) / 2f -
            (paint.ascent() + paint.descent()) / 2f
        for (line in lines) {
            canvas.drawText(line, label.x * canvas.width, baseline, paint)
            baseline += lineHeight
        }
    }

    private fun typeface(reference: String, typefaces: MutableMap<String, Typeface>): Typeface =
        typefaces.getOrPut(reference) {
            val file = File(assets, reference)
            if (!file.isFile) throw IOException("The custom font is missing. Import it again.")
            try {
                Typeface.createFromFile(file)
            } catch (e: RuntimeException) {
                throw IOException("The custom font is unreadable. Import it again.", e)
            }
        }

    private fun importAsset(input: InputStream, maxBytes: Int, prefix: String, extension: String,
                            validate: (File) -> Unit): String {
        ensureDirectory(assets)
        val staging = File.createTempFile(".ruangfoto-", ".part", assets)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            staging.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes.toLong()) { "Choose a file smaller than ${maxBytes / (1024 * 1024)} MB." }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                require(total > 0L) { "Choose a non-empty file." }
            }
            validate(staging)
            val hash = digest.digest().joinToString("") {
                ((it.toInt() and 0xff) + 0x100).toString(16).substring(1)
            }.take(16)
            val name = prefix + hash + extension
            val target = File(assets, name)
            if (!target.isFile) {
                try {
                    Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE)
                } catch (_: FileAlreadyExistsException) {
                    // An identical asset was stored by another import; the content is the same.
                }
            }
            return name
        } finally {
            staging.delete()
        }
    }

    private fun validateImportedOverlay(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outMimeType == "image/png" && bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Choose a valid PNG frame."
        }
        require(bounds.outWidth <= MAX_OVERLAY_SIDE && bounds.outHeight <= MAX_OVERLAY_SIDE &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_OVERLAY_PIXELS) {
            "Choose a PNG up to $MAX_OVERLAY_SIDE pixels per side and 6 megapixels."
        }
        val artwork = BitmapFactory.decodeFile(file.path) ?: throw IllegalArgumentException("Cannot read that PNG.")
        try {
            require(isOverlay(artwork)) { "The PNG needs both visible artwork and a transparent opening." }
        } finally {
            artwork.recycle()
        }
    }

    private fun validateImportedFont(file: File) {
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        val tag = String(header, Charsets.ISO_8859_1)
        val sfnt = header.contentEquals(byteArrayOf(0, 1, 0, 0)) || tag == "OTTO" || tag == "true" || tag == "ttcf"
        require(read == 4 && sfnt) { "Choose a valid TrueType or OpenType font." }
        try {
            Typeface.createFromFile(file)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("Choose a valid TrueType or OpenType font.", e)
        }
    }

    private fun isOverlay(artwork: Bitmap): Boolean {
        if (!artwork.hasAlpha()) return false
        var hasOpening = false
        var hasArtwork = false
        val row = IntArray(artwork.width)
        for (y in 0 until artwork.height) {
            artwork.getPixels(row, 0, artwork.width, 0, y, artwork.width, 1)
            for (pixel in row) {
                val alpha = pixel ushr 24
                if (alpha < 255) hasOpening = true
                if (alpha > 0) hasArtwork = true
                if (hasOpening && hasArtwork) return true
            }
        }
        return false
    }

    private fun canvasSize(canvas: FrameCanvas, longSide: Int, artwork: Bitmap?): Pair<Int, Int> {
        val shortSide = (longSide * 2 / 3).coerceAtLeast(1)
        return when (canvas) {
            FrameCanvas.MATCH_PNG -> if (artwork == null) shortSide to longSide else {
                val scale = minOf(1f, longSide.toFloat() / maxOf(artwork.width, artwork.height))
                (artwork.width * scale).toInt().coerceAtLeast(1) to (artwork.height * scale).toInt().coerceAtLeast(1)
            }
            FrameCanvas.PORTRAIT -> shortSide to longSide
            FrameCanvas.LANDSCAPE -> longSide to shortSide
            FrameCanvas.SQUARE -> longSide to longSide
        }
    }

    private fun writePng(bitmap: Bitmap, target: File) {
        target.outputStream().use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IOException("Cannot save the frame preview.")
            }
        }
    }

    private fun writeAtomically(target: File, write: (File) -> Unit) {
        val staging = File.createTempFile(".ruangfoto-", ".part", target.parentFile)
        try {
            write(staging)
            Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            staging.delete()
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create ${directory.name}.")
        }
    }

    internal companion object {
        const val DEFAULT_PREVIEW_SECONDS = 15
        const val MAX_PREVIEW_SECONDS = 60

        private const val STORE_DIRECTORY = ".ruangfoto-design"
        private const val ASSETS_DIRECTORY = "assets"
        private const val REVISIONS_DIRECTORY = "revisions"
        private const val DESIGN_NAME = "design.json"
        private const val ACTIVE_NAME = "active-revision"
        private const val OVERLAY_NAME = "overlay.png"
        private const val REVISION_NAME = "revision.json"
        private const val PREVIEW_SECONDS_NAME = "preview-seconds"
        private const val MAX_DESIGN_BYTES = 1_000_000L
        private const val MAX_OVERLAY_BYTES = 32 * 1024 * 1024
        private const val MAX_FONT_BYTES = 16 * 1024 * 1024
        private const val MAX_OVERLAY_PIXELS = 6_000_000L
        private const val MAX_OVERLAY_SIDE = 4096
        private const val MAX_LABELS = 50
        private const val MAX_LABEL_CHARS = 500
        private const val MIN_TEXT_SIZE = 0.005f
        private const val MAX_TEXT_SIZE = 0.5f
        private const val MIN_PREVIEW_SIDE = 64
        private const val MAX_RENDER_SIDE = 2400
        private val SAFE_REF = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    }
}
