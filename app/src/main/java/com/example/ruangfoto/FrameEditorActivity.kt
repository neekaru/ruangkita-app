package com.ruangfoto.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ruangfoto.app.databinding.ActivityFrameEditorBinding
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Per-session frame workbench: overlay image, text/emoji layers, revisions and the
 * shot preview duration. Camera capture lives in MainActivity; saving here only writes
 * the session design, so it never interrupts a running session.
 */
class FrameEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFrameEditorBinding
    private lateinit var store: FrameDesignStore

    private var draft = FrameDesign()
    private var savedDesign: FrameDesign? = null
    private var loadedRevisionId: String? = null
    private var previewDuration = FrameDesignStore.DEFAULT_PREVIEW_SECONDS
    private var selectedIndex = 0
    private var pendingFontLabel = -1
    private var busy = false
    private var busyMessage = 0
    private var bindingFields = false
    private var textEditRecorded = false
    private var draggingLabel = -1
    private var draggingMoved = false
    @Volatile private var destroyed = false

    private var previewBitmap: Bitmap? = null

    private val undoStack = ArrayDeque<FrameDesign>()
    private val redoStack = ArrayDeque<FrameDesign>()

    private val worker = Executors.newSingleThreadExecutor()
    private val pendingPreview = AtomicReference<FrameDesign?>(null)
    private val previewRunning = AtomicBoolean(false)

    private val overlayPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importOverlay(uri)
    }

    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val index = pendingFontLabel
        pendingFontLabel = -1
        if (uri != null) importFont(index, uri)
    }

    private val labelTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(text: Editable?) {
            if (bindingFields || busy) return
            val label = selectedLabel() ?: return
            val value = text?.toString().orEmpty()
            if (label.text == value) return
            if (!textEditRecorded) {
                recordEdit()
                textEditRecorded = true
            }
            updateSelectedLabel { it.copy(text = value) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val directory = intent.getStringExtra(EXTRA_DIRECTORY)?.let(::File)
        if (directory == null) {
            finish()
            return
        }
        store = FrameDesignStore(directory)

        binding = ActivityFrameEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { leaveEditor() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveEditor()
        })

        setupSpinners()
        setupActions()
        setupDuration()

        if (savedInstanceState != null) {
            restoreState(savedInstanceState)
            bindAll()
            requestPreview()
            loadActive(keepDraft = true)
        } else {
            loadActive(keepDraft = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::binding.isInitialized) return
        outState.putBundle(STATE_DRAFT, designToBundle(draft))
        savedDesign?.let { outState.putBundle(STATE_SAVED, designToBundle(it)) }
        outState.putInt(STATE_SELECTED, selectedIndex)
        outState.putString(STATE_REVISION, loadedRevisionId)
        outState.putInt(STATE_PENDING_FONT, pendingFontLabel)
    }

    override fun onDestroy() {
        destroyed = true
        // shutdown(), not shutdownNow(): an in-flight save should finish writing.
        worker.shutdown()
        previewBitmap?.recycle()
        previewBitmap = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------------------------
    // Setup

    private fun setupSpinners() {
        val canvasAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            FrameCanvas.entries.map { it.title })
        canvasAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.canvasSpinner.adapter = canvasAdapter
        binding.canvasSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (bindingFields || busy) return
                val canvas = FrameCanvas.entries.getOrNull(position) ?: return
                if (draft.canvas == canvas) return
                recordEdit()
                draft = draft.copy(canvas = canvas)
                requestPreview()
                updateButtons()
                updateStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.labelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (bindingFields || position == selectedIndex || position !in draft.labels.indices) return
                selectedIndex = position
                bindSelectedLabel()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupActions() {
        binding.overlayChooseButton.setOnClickListener { overlayPicker.launch(arrayOf("image/png")) }
        binding.overlayRemoveButton.setOnClickListener { removeOverlay() }
        binding.addLabelButton.setOnClickListener { addLabel() }
        binding.removeLabelButton.setOnClickListener { removeSelectedLabel() }
        binding.colorButton.setOnClickListener { chooseColor() }
        binding.fontButton.setOnClickListener { chooseFont() }
        binding.undoButton.setOnClickListener { undo() }
        binding.redoButton.setOnClickListener { redo() }
        binding.historyButton.setOnClickListener { showHistory() }
        binding.saveButton.setOnClickListener {
            saveFrame(getString(if (loadedRevisionId == null) R.string.frame_editor_saved else R.string.frame_editor_applied))
        }
        binding.disableButton.setOnClickListener { confirmDisable() }

        binding.labelTextInput.addTextChangedListener(labelTextWatcher)
        binding.labelTextInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) textEditRecorded = false
        }

        setupSeekBars()
        binding.previewImage.setOnTouchListener { _, event -> handlePreviewTouch(event) }
    }

    private fun setupSeekBars() {
        binding.sizeSeek.max = MAX_SIZE_PERCENT - MIN_SIZE_PERCENT
        binding.sizeSeek.setOnSeekBarChangeListener(seekListener(
            onProgress = { progress ->
                val percent = progress + MIN_SIZE_PERCENT
                binding.sizeValue.text = getString(R.string.frame_editor_percent, percent)
                updateSelectedLabel { it.copy(size = percent / 100f) }
            }
        ))

        binding.xSeek.max = 100
        binding.xSeek.setOnSeekBarChangeListener(seekListener(
            onProgress = { progress ->
                binding.xValue.text = getString(R.string.frame_editor_percent, progress)
                updateSelectedLabel { it.copy(x = progress / 100f) }
            }
        ))

        binding.ySeek.max = 100
        binding.ySeek.setOnSeekBarChangeListener(seekListener(
            onProgress = { progress ->
                binding.yValue.text = getString(R.string.frame_editor_percent, progress)
                updateSelectedLabel { it.copy(y = progress / 100f) }
            }
        ))
    }

    private fun seekListener(onProgress: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            private var tracking = false
            private var recorded = false

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (bindingFields || !fromUser || selectedLabel() == null) return
                if (!recorded) recordEdit()
                recorded = tracking
                onProgress(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) { tracking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                tracking = false
                recorded = false
            }
        }

    private fun setupDuration() {
        binding.previewDurationSeek.max = FrameDesignStore.MAX_PREVIEW_SECONDS
        binding.previewDurationValue.text = durationText(previewDuration)
        binding.previewDurationSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var tracking = false

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.previewDurationValue.text = durationText(progress)
                    if (!tracking) saveDuration(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) { tracking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                tracking = false
                saveDuration(seekBar.progress)
            }
        })
    }

    private fun saveDuration(seconds: Int) {
        if (seconds == previewDuration) return
        previewDuration = seconds
        background {
            val result = runCatching { store.setPreviewDurationSeconds(seconds) }
            if (result.isFailure) {
                val persisted = store.previewDurationSeconds()
                runOnUiThread {
                    if (!destroyed && previewDuration == seconds) {
                        showDuration(persisted)
                        showError(getString(R.string.frame_editor_duration_failed))
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // State

    private fun restoreState(state: Bundle) {
        state.getBundle(STATE_DRAFT)?.let { draft = designFromBundle(it) }
        savedDesign = state.getBundle(STATE_SAVED)?.let(::designFromBundle)
        selectedIndex = state.getInt(STATE_SELECTED, 0)
        loadedRevisionId = state.getString(STATE_REVISION)
        pendingFontLabel = state.getInt(STATE_PENDING_FONT, -1)
    }

    private fun loadActive(keepDraft: Boolean) {
        setBusy(true, R.string.frame_editor_status_loading)
        background {
            val active = runCatching { store.activeDesign() }
            val duration = runCatching { store.previewDurationSeconds() }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                active.onSuccess { design ->
                    savedDesign = design
                    if (!keepDraft) draft = design ?: FrameDesign()
                    bindAll()
                }.onFailure { showError(getString(R.string.frame_editor_load_failed)) }
                duration.onSuccess(::showDuration)
                requestPreview()
            }
        }
    }

    private fun bindAll() {
        withoutFieldEvents { binding.canvasSpinner.setSelection(FrameCanvas.entries.indexOf(draft.canvas)) }
        rebuildLabelChooser()
        bindSelectedLabel()
        updateButtons()
        updateStatus()
    }

    private fun bindSelectedLabel() {
        val label = draft.labels.getOrNull(selectedIndex)
        binding.labelPanel.visibility = if (label == null) View.GONE else View.VISIBLE
        if (label == null) {
            textEditRecorded = false
            updateButtons()
            return
        }

        withoutFieldEvents {
            if (binding.labelTextInput.text.toString() != label.text) {
                binding.labelTextInput.setText(label.text)
                binding.labelTextInput.setSelection(binding.labelTextInput.length())
            }

            val sizePercent = (label.size * 100).roundToInt().coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)
            binding.sizeSeek.progress = sizePercent - MIN_SIZE_PERCENT
            binding.sizeValue.text = getString(R.string.frame_editor_percent, sizePercent)

            val xPercent = (label.x * 100).roundToInt().coerceIn(0, 100)
            binding.xSeek.progress = xPercent
            binding.xValue.text = getString(R.string.frame_editor_percent, xPercent)

            val yPercent = (label.y * 100).roundToInt().coerceIn(0, 100)
            binding.ySeek.progress = yPercent
            binding.yValue.text = getString(R.string.frame_editor_percent, yPercent)
        }

        val colorName = LabelColor.entries.firstOrNull { it.argb == label.color }
            ?.let { getString(it.labelRes) }
            ?: getString(R.string.frame_editor_custom)
        binding.colorButton.text = getString(R.string.frame_editor_color_value, colorName)
        binding.fontButton.text = getString(R.string.frame_editor_font_value,
            getString(if (label.font == null) R.string.frame_editor_system else R.string.frame_editor_custom))

        textEditRecorded = false
        updateButtons()
    }

    private fun rebuildLabelChooser() {
        val names = draft.labels.indices.map { getString(R.string.frame_editor_layer, it + 1) }
        withoutFieldEvents {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.labelSpinner.adapter = adapter
            if (selectedIndex !in draft.labels.indices) selectedIndex = draft.labels.lastIndex
            if (selectedIndex >= 0) binding.labelSpinner.setSelection(selectedIndex)
        }
        binding.labelSpinner.visibility = if (draft.labels.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateButtons() {
        val hasLabel = selectedLabel() != null
        val content = hasContent(draft)
        binding.saveButton.isEnabled = !busy && content && (isDirty() || loadedRevisionId != null)
        binding.saveButton.setText(if (loadedRevisionId == null) R.string.frame_editor_save else R.string.frame_editor_apply_revision)
        binding.historyButton.isEnabled = !busy
        binding.disableButton.isEnabled = !busy && savedDesign != null
        binding.undoButton.isEnabled = !busy && undoStack.isNotEmpty()
        binding.redoButton.isEnabled = !busy && redoStack.isNotEmpty()
        binding.addLabelButton.isEnabled = !busy && draft.labels.size < MAX_LABELS
        binding.overlayChooseButton.isEnabled = !busy
        binding.overlayRemoveButton.isEnabled = !busy && draft.overlay != null
        binding.canvasSpinner.isEnabled = !busy
        binding.labelSpinner.isEnabled = !busy && draft.labels.isNotEmpty()
        binding.removeLabelButton.isEnabled = !busy && hasLabel
        binding.colorButton.isEnabled = !busy && hasLabel
        binding.fontButton.isEnabled = !busy && hasLabel
        binding.labelTextInput.isEnabled = !busy && hasLabel
        binding.sizeSeek.isEnabled = !busy && hasLabel
        binding.xSeek.isEnabled = !busy && hasLabel
        binding.ySeek.isEnabled = !busy && hasLabel
        binding.previewDurationSeek.isEnabled = !busy
        binding.overlayChooseButton.setText(
            if (draft.overlay != null) R.string.frame_editor_replace_png else R.string.frame_editor_choose_png)
        binding.labelSpinner.visibility = if (draft.labels.isEmpty()) View.GONE else View.VISIBLE
        binding.previewEmpty.visibility = if (content) View.GONE else View.VISIBLE
    }

    private fun updateStatus(message: String? = null, error: Boolean = false) {
        if (message != null) {
            binding.statusText.text = message
            binding.statusText.setTextColor(ContextCompat.getColor(this,
                if (error) R.color.transfer_stop else R.color.transfer_text))
            return
        }
        binding.statusText.text = when {
            busy && busyMessage != 0 -> getString(busyMessage)
            busy -> getString(R.string.frame_editor_status_working)
            loadedRevisionId != null -> getString(R.string.frame_editor_revision_loaded)
            isDirty() -> getString(R.string.frame_editor_status_unsaved)
            savedDesign != null -> getString(R.string.frame_editor_status_enabled)
            else -> getString(R.string.frame_editor_status_disabled)
        }
        binding.statusText.setTextColor(ContextCompat.getColor(this,
            if (savedDesign != null) R.color.transfer_accent else R.color.transfer_muted))
    }

    private fun showError(message: String) = updateStatus(message, error = true)

    private fun setBusy(value: Boolean, message: Int = 0) {
        busy = value
        busyMessage = if (value) message else 0
        binding.statusProgress.visibility = if (value) View.VISIBLE else View.GONE
        updateButtons()
        updateStatus()
    }

    private fun showDuration(seconds: Int) {
        previewDuration = seconds.coerceIn(0, FrameDesignStore.MAX_PREVIEW_SECONDS)
        binding.previewDurationSeek.progress = previewDuration
        binding.previewDurationValue.text = durationText(previewDuration)
    }

    private fun durationText(seconds: Int): String =
        if (seconds <= 0) getString(R.string.frame_editor_preview_off)
        else getString(R.string.frame_editor_preview_seconds, seconds)

    // -------------------------------------------------------------------------------------
    // Editing

    private fun recordEdit() {
        if (undoStack.size >= MAX_UNDO) undoStack.removeFirst()
        undoStack.addLast(draft)
        redoStack.clear()
        textEditRecorded = false
        loadedRevisionId = null
        updateButtons()
        updateStatus()
    }

    private fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(draft)
        loadedRevisionId = null
        restoreDraft(previous)
        updateStatus()
    }

    private fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(draft)
        loadedRevisionId = null
        restoreDraft(next)
        updateStatus()
    }

    private fun restoreDraft(design: FrameDesign) {
        draft = design
        if (selectedIndex !in design.labels.indices) selectedIndex = design.labels.lastIndex
        withoutFieldEvents { binding.canvasSpinner.setSelection(FrameCanvas.entries.indexOf(design.canvas)) }
        rebuildLabelChooser()
        bindSelectedLabel()
        requestPreview()
        updateButtons()
    }

    private fun selectedLabel(): FrameLabel? = draft.labels.getOrNull(selectedIndex)

    private fun updateSelectedLabel(change: (FrameLabel) -> FrameLabel) =
        updateSelectedLabelAt(selectedIndex, change)

    private fun updateSelectedLabelAt(index: Int, change: (FrameLabel) -> FrameLabel) {
        if (index !in draft.labels.indices) return
        draft = draft.copy(labels = draft.labels.mapIndexed { i, label ->
            if (i == index) change(label) else label
        })
        requestPreview()
        updateButtons()
    }

    private fun addLabel() {
        if (draft.labels.size >= MAX_LABELS) return
        recordEdit()
        draft = draft.copy(labels = draft.labels + FrameLabel(getString(R.string.frame_editor_new_label)))
        selectedIndex = draft.labels.lastIndex
        rebuildLabelChooser()
        bindSelectedLabel()
        requestPreview()
        binding.labelTextInput.requestFocus()
        binding.labelTextInput.selectAll()
    }

    private fun removeSelectedLabel() {
        if (selectedIndex !in draft.labels.indices) return
        recordEdit()
        draft = draft.copy(labels = draft.labels.filterIndexed { i, _ -> i != selectedIndex })
        selectedIndex = selectedIndex.coerceAtMost(draft.labels.lastIndex)
        rebuildLabelChooser()
        bindSelectedLabel()
        requestPreview()
        updateStatus()
    }

    private fun removeOverlay() {
        if (draft.overlay == null) return
        recordEdit()
        draft = draft.copy(overlay = null)
        requestPreview()
        updateButtons()
        updateStatus()
    }

    private fun chooseColor() {
        val label = selectedLabel() ?: return
        val checked = LabelColor.entries.indexOfFirst { it.argb == label.color }
        val names = Array<CharSequence>(LabelColor.entries.size) { getString(LabelColor.entries[it].labelRes) }
        AlertDialog.Builder(this)
            .setTitle(R.string.frame_editor_color)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val color = LabelColor.entries[which].argb
                if (color != label.color) {
                    recordEdit()
                    updateSelectedLabel { it.copy(color = color) }
                    bindSelectedLabel()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseFont() {
        val label = selectedLabel() ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.frame_editor_font_dialog_title)
            .setItems(arrayOf(getString(R.string.frame_editor_font_system),
                getString(R.string.frame_editor_font_pick))) { _, which ->
                if (which == 0) {
                    if (label.font != null) {
                        recordEdit()
                        updateSelectedLabel { it.copy(font = null) }
                        bindSelectedLabel()
                    }
                } else {
                    pendingFontLabel = selectedIndex
                    fontPicker.launch(arrayOf("*/*"))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------------------------------
    // Overlay and font import

    // ponytail: an imported overlay/font stays in the store's asset folder even when the
    // edit is discarded; the shared store contract has no delete call.
    private fun importOverlay(uri: Uri) {
        if (busy) return
        setBusy(true, R.string.frame_editor_status_importing_frame)
        background {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { store.importOverlay(it) }
                    ?: throw IOException("Cannot open the selected image")
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess { overlay ->
                    recordEdit()
                    draft = draft.copy(overlay = overlay)
                    requestPreview()
                    updateButtons()
                    updateStatus()
                }.onFailure { showError(getString(R.string.frame_editor_import_failed)) }
            }
        }
    }

    private fun importFont(index: Int, uri: Uri) {
        if (index !in draft.labels.indices) return
        setBusy(true, R.string.frame_editor_status_importing_font)
        background {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { store.importFont(it) }
                    ?: throw IOException("Cannot open the selected font")
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess { font ->
                    if (index in draft.labels.indices) {
                        recordEdit()
                        updateSelectedLabelAt(index) { it.copy(font = font) }
                        if (index == selectedIndex) bindSelectedLabel()
                    }
                }.onFailure { showError(getString(R.string.frame_editor_font_failed)) }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Save and disable

    private fun saveFrame(message: String) {
        if (busy) return
        val design = draft.copy(labels = draft.labels.filter { it.text.isNotBlank() })
        if (!hasContent(design)) return
        // Let the preview drain finish so the save is not queued behind constant dragging.
        pendingPreview.set(null)

        setBusy(true, R.string.frame_editor_status_saving)
        background {
            val result = runCatching { store.save(design) }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess {
                    savedDesign = design
                    draft = design
                    loadedRevisionId = null
                    bindAll()
                    requestPreview()
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }.onFailure { showError(getString(R.string.frame_editor_save_failed)) }
            }
        }
    }

    private fun confirmDisable() {
        if (savedDesign == null || busy) return
        AlertDialog.Builder(this)
            .setTitle(R.string.frame_editor_disable_title)
            .setMessage(R.string.frame_editor_disable_message)
            .setPositiveButton(R.string.frame_editor_disable) { _, _ -> disableFrame() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun disableFrame() {
        if (busy) return
        pendingPreview.set(null)
        setBusy(true, R.string.frame_editor_status_saving)
        background {
            val result = runCatching { store.disable() }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess {
                    savedDesign = null
                    draft = FrameDesign()
                    loadedRevisionId = null
                    undoStack.clear()
                    redoStack.clear()
                    selectedIndex = 0
                    clearPreview()
                    bindAll()
                    Toast.makeText(this, R.string.frame_editor_turned_off, Toast.LENGTH_SHORT).show()
                }.onFailure { showError(getString(R.string.frame_editor_disable_failed)) }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // History

    private fun showHistory() {
        setBusy(true, R.string.frame_editor_status_loading_revision)
        background {
            val result = runCatching { store.revisions() }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess { revisions ->
                    val dialog = AlertDialog.Builder(this).setTitle(R.string.frame_editor_history)
                        .setNegativeButton(android.R.string.cancel, null)
                    if (revisions.isEmpty()) {
                        dialog.setMessage(R.string.frame_editor_history_empty)
                    } else {
                        val entries = revisions.map { revision ->
                            getString(R.string.frame_editor_revision_row, revision.title, formatDate(revision.createdAt))
                        }.toTypedArray()
                        dialog.setItems(entries) { _, index -> loadRevision(revisions[index].id) }
                    }
                    dialog.show()
                }.onFailure { showError(getString(R.string.frame_editor_history_failed)) }
            }
        }
    }

    private fun loadRevision(id: String) {
        if (busy) return
        setBusy(true, R.string.frame_editor_status_loading_revision)
        background {
            val result = runCatching { store.loadRevision(id) }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess { design ->
                    recordEdit()
                    loadedRevisionId = id
                    selectedIndex = 0
                    restoreDraft(design)
                    updateStatus()
                }.onFailure { showError(getString(R.string.frame_editor_revision_failed)) }
            }
        }
    }

    private fun formatDate(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    // -------------------------------------------------------------------------------------
    // Preview rendering

    private fun requestPreview() {
        if (destroyed) return
        if (!hasContent(draft)) {
            pendingPreview.set(null)
            clearPreview()
            return
        }
        pendingPreview.set(draft)
        startPreviewDrain()
    }

    private fun startPreviewDrain() {
        if (!previewRunning.compareAndSet(false, true)) return
        background { drainPreviews() }
    }

    /** Renders only the newest pending design, so dragging or typing never builds a backlog. */
    private fun drainPreviews() {
        try {
            while (true) {
                val design = pendingPreview.getAndSet(null) ?: break
                val bitmap = runCatching {
                    store.renderPreview(design.copy(labels = design.labels.filter { it.text.isNotEmpty() }))
                }.getOrNull()
                runOnUiThread {
                    if (destroyed) {
                        if (bitmap !== previewBitmap) bitmap?.recycle()
                    } else {
                        showPreview(design, bitmap)
                    }
                }
            }
        } finally {
            previewRunning.set(false)
            if (pendingPreview.get() != null) startPreviewDrain()
        }
    }

    private fun showPreview(design: FrameDesign, bitmap: Bitmap?) {
        // A newer edit already arrived: this bitmap is stale.
        if (bitmap == null || design != draft) {
            if (bitmap !== previewBitmap) bitmap?.recycle()
            return
        }
        val previous = previewBitmap
        previewBitmap = bitmap
        binding.previewImage.setImageBitmap(bitmap)
        if (previous !== bitmap) previous?.recycle()
    }

    private fun clearPreview() {
        binding.previewImage.setImageBitmap(null)
        previewBitmap?.recycle()
        previewBitmap = null
    }

    // -------------------------------------------------------------------------------------
    // Drag a label on the live preview

    private fun handlePreviewTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingLabel = -1
                return startDrag(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingLabel < 0) return false
                moveDrag(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = draggingLabel >= 0
                draggingLabel = -1
                if (wasDragging) {
                    binding.previewImage.parent.requestDisallowInterceptTouchEvent(false)
                    draggingMoved = false
                    bindSelectedLabel()
                    updateButtons()
                    updateStatus()
                }
                return wasDragging
            }
        }
        return false
    }

    private fun startDrag(x: Float, y: Float): Boolean {
        if (busy) return false
        val bitmap = previewBitmap ?: return false
        val rect = imageRect(bitmap) ?: return false
        if (!rect.contains(x, y)) return false
        val nx = (x - rect.left) / rect.width()
        val ny = (y - rect.top) / rect.height()
        var best = -1
        var bestDistance = dp(40f).toFloat()
        draft.labels.forEachIndexed { index, label ->
            val dx = (label.x - nx) * rect.width()
            val dy = (label.y - ny) * rect.height()
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        if (best < 0) return false
        draggingLabel = best
        draggingMoved = false
        binding.previewImage.parent.requestDisallowInterceptTouchEvent(true)
        if (selectedIndex != best) {
            selectedIndex = best
            withoutFieldEvents { binding.labelSpinner.setSelection(best) }
            bindSelectedLabel()
        }
        return true
    }

    private fun moveDrag(x: Float, y: Float) {
        val index = draggingLabel
        if (index !in draft.labels.indices) return
        val bitmap = previewBitmap ?: return
        val rect = imageRect(bitmap) ?: return
        if (!draggingMoved) {
            recordEdit()
            draggingMoved = true
        }
        val nx = ((x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val ny = ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
        updateSelectedLabelAt(index) { it.copy(x = nx, y = ny) }
        withoutFieldEvents {
            val xPercent = (nx * 100).roundToInt()
            binding.xSeek.progress = xPercent
            binding.xValue.text = getString(R.string.frame_editor_percent, xPercent)
            val yPercent = (ny * 100).roundToInt()
            binding.ySeek.progress = yPercent
            binding.yValue.text = getString(R.string.frame_editor_percent, yPercent)
        }
    }

    /** Bitmap display rect inside the fit-center preview ImageView. */
    private fun imageRect(bitmap: Bitmap): RectF? {
        val viewWidth = binding.previewImage.width
        val viewHeight = binding.previewImage.height
        if (viewWidth == 0 || viewHeight == 0) return null
        val scale = min(viewWidth.toFloat() / bitmap.width, viewHeight.toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (viewWidth - width) / 2f
        val top = (viewHeight - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    // -------------------------------------------------------------------------------------
    // Navigation, state helpers

    private fun leaveEditor() {
        if (!isDirty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.frame_editor_discard_title)
            .setMessage(R.string.frame_editor_discard_message)
            .setPositiveButton(R.string.frame_editor_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.frame_editor_keep_editing, null)
            .show()
    }

    private fun isDirty(): Boolean = savedDesign?.let { it != draft } ?: hasContent(draft)

    private fun hasContent(design: FrameDesign): Boolean =
        design.overlay != null || design.labels.any { it.text.isNotBlank() }

    private fun background(block: () -> Unit) {
        if (destroyed) return
        runCatching { worker.execute(block) }
    }

    private inline fun withoutFieldEvents(block: () -> Unit) {
        val previous = bindingFields
        bindingFields = true
        try {
            block()
        } finally {
            bindingFields = previous
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun designToBundle(design: FrameDesign): Bundle {
        val labels = ArrayList<Bundle>(design.labels.size)
        design.labels.forEach { label ->
            labels.add(Bundle().apply {
                putString(KEY_TEXT, label.text)
                putFloat(KEY_X, label.x)
                putFloat(KEY_Y, label.y)
                putFloat(KEY_SIZE, label.size)
                putInt(KEY_COLOR, label.color)
                putString(KEY_FONT, label.font)
            })
        }
        return Bundle().apply {
            putString(KEY_OVERLAY, design.overlay)
            putString(KEY_CANVAS, design.canvas.name)
            putParcelableArrayList(KEY_LABELS, labels)
        }
    }

    private fun designFromBundle(bundle: Bundle): FrameDesign {
        val labels = BundleCompat.getParcelableArrayList(bundle, KEY_LABELS, Bundle::class.java)
            ?.map { label ->
                FrameLabel(
                    text = label.getString(KEY_TEXT).orEmpty(),
                    x = label.getFloat(KEY_X, 0.5f),
                    y = label.getFloat(KEY_Y, 0.5f),
                    size = label.getFloat(KEY_SIZE, 0.06f),
                    color = label.getInt(KEY_COLOR, -1),
                    font = label.getString(KEY_FONT)
                )
            }.orEmpty()
        return FrameDesign(
            overlay = bundle.getString(KEY_OVERLAY),
            labels = labels,
            canvas = FrameCanvas.entries.firstOrNull { it.name == bundle.getString(KEY_CANVAS) }
                ?: FrameCanvas.MATCH_PNG
        )
    }

    private enum class LabelColor(val labelRes: Int, val argb: Int) {
        WHITE(R.string.frame_editor_color_white, -1),
        BLACK(R.string.frame_editor_color_black, 0xFF000000.toInt()),
        GOLD(R.string.frame_editor_color_gold, 0xFFD5A85B.toInt()),
        RED(R.string.frame_editor_color_red, 0xFFE0766B.toInt()),
        TEAL(R.string.frame_editor_color_teal, 0xFF4DB6AC.toInt())
    }

    companion object {
        private const val EXTRA_DIRECTORY = "com.ruangfoto.app.extra.FRAME_EDITOR_DIRECTORY"
        private const val MIN_SIZE_PERCENT = 2
        private const val MAX_SIZE_PERCENT = 25
        private const val MAX_LABELS = 24
        private const val MAX_UNDO = 50

        private const val STATE_DRAFT = "frame_editor_draft"
        private const val STATE_SAVED = "frame_editor_saved"
        private const val STATE_SELECTED = "frame_editor_selected"
        private const val STATE_REVISION = "frame_editor_revision"
        private const val STATE_PENDING_FONT = "frame_editor_pending_font"

        private const val KEY_OVERLAY = "overlay"
        private const val KEY_CANVAS = "canvas"
        private const val KEY_LABELS = "labels"
        private const val KEY_TEXT = "text"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_SIZE = "size"
        private const val KEY_COLOR = "color"
        private const val KEY_FONT = "font"

        internal fun intent(context: Context, directory: File): Intent =
            Intent(context, FrameEditorActivity::class.java)
                .putExtra(EXTRA_DIRECTORY, directory.absolutePath)
    }
}
