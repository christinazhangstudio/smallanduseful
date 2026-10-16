package dev.lystic.fanyixia

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val state: AppState by viewModels()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var modelStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var prepare: Button
    private lateinit var record: Button
    private lateinit var stop: Button
    private lateinit var fresh: Button
    private lateinit var copy: Button
    private lateinit var sessionTitle: EditText
    private lateinit var chinese: EditText
    private lateinit var english: TextView
    private lateinit var translate: Button
    private lateinit var save: Button
    private lateinit var publish: Button
    private lateinit var destination: Button
    private lateinit var selection: TextView
    private lateinit var library: LinearLayout
    private var rendering = false
    private var displayedSessions: List<SavedSession>? = null
    private var displayedServer: String? = null
    private var previousStatus = ""

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) state.startRecording()
        else if (!allowed) state.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { state.ui.collect { render(it) } }
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        scroll.addView(root)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val edges = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(edges.left, edges.top, edges.right, edges.bottom)
            insets
        }
        setContentView(scroll)
        heading("Fan yi Xia", 28f)
        text("翻译一下 · translate a little\nMandarin microphone → Chinese text → English, on this device.")
        text("Privacy: no audio is saved or uploaded. Saved text stays here unless you explicitly Publish PUBLICLY. Models need internet only for initial preparation. Downloads may use mobile data (~180 MB total); Wi-Fi is recommended.")
        modelStatus = text("")
        prepare = button("Prepare offline models / Retry download") { state.prepare() }
        progress = ProgressBar(this).also { root.addView(it) }
        status = text("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        heading("Record or edit", 22f)
        text("Record at most 60 seconds. Stop runs offline transcription. Leaving this screen (including rotation) stops and discards an active recording. Unsaved text is kept in memory only: a process restart loses it. Save locally to keep text; New draft discards unsaved edits.")
        fresh = button("New draft / Clear unsaved text") {
            discardDraftIfNeeded { state.newDraft() }
        }
        record = button("Record Mandarin") {
            val current = state.ui.value
            if (current.chinese.isNotBlank() || current.english.isNotBlank()) {
                AlertDialog.Builder(this).setTitle("Replace unsaved text?")
                    .setMessage("Starting a recording replaces this draft's Chinese and English. Saved sessions are unchanged.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Record") { _, _ -> requestRecording() }.show()
            } else requestRecording()
        }
        stop = button("Stop and transcribe offline") { state.stopRecording(false) }
        selection = text("")
        sessionTitle = editor("Session title", 160, false) { state.titleChanged(it) }
        chinese = editor("Chinese — review and edit before translating", 4000, true) { state.chineseChanged(it) }
        translate = button("Translate Chinese → English offline") { state.translate() }
        text("English — ML Kit on-device translation; review for mistakes. Not suitable for safety-critical decisions.")
        english = text("").apply { setTextIsSelectable(true); minHeight = dp(64) }
        save = button("Save locally — no upload") { state.save() }
        copy = button("Edit a copy — new session ID") { state.copySelected() }
        heading("Explicit public sharing", 22f)
        text("The shared library is PUBLIC, with no sign-in. Anyone can read and analyze published Chinese and English. Publishing cannot be undone here. Only the selected saved record's title, text, timestamps and engine names are sent; never audio. A failed request keeps local data. Retry uses the same immutable UUID.")
        destination = button("Publish server") { editDestination() }
        publish = button("Publish selected saved session PUBLICLY") { confirmPublish() }
        heading("Saved on this device", 22f)
        text("Tap a session to reopen it. Records are immutable: edit a copy to save changes under a new ID. Publication badges are acknowledgements from the shown server, not live availability checks.")
        library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(library)
        button("Android microphone permission settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun render(ui: AppUi) {
        rendering = true
        try {
            modelStatus.text = "Whisper base: ${if (ui.whisperReady) "verified and ready" else "not ready"}\nML Kit Chinese → English: ${if (ui.translationReady) "downloaded and ready" else "not ready"}\n${if (ui.ready) "Offline capture and translation available." else "Prepare models before microphone use."}"
            prepare.isEnabled = !ui.busy && !ui.recording
            prepare.text = if (ui.ready) "Verify / Prepare offline models again" else "Prepare offline models / Retry download"
            progress.visibility = if (ui.busy) View.VISIBLE else View.GONE
            status.text = ui.status
            if (previousStatus != ui.status) {
                status.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                previousStatus = ui.status
            }
            val editable = !ui.busy && !ui.recording && ui.selectedId == null
            sessionTitle.isEnabled = editable
            chinese.isEnabled = editable
            updateText(sessionTitle, ui.title)
            updateText(chinese, ui.chinese)
            english.text = if (ui.english.isBlank()) "No current English translation. Translate after reviewing Chinese." else ui.english
            fresh.isEnabled = !ui.busy && !ui.recording
            record.isEnabled = editable && ui.ready
            record.text = if (ui.recording) "Recording ${ui.elapsedSeconds} / 60 seconds…" else "Record Mandarin (max 60 seconds)"
            stop.isEnabled = ui.recording
            translate.isEnabled = editable && ui.translationReady && ui.chinese.isNotBlank()
            save.isEnabled = editable && ui.title.isNotBlank() && ui.chinese.isNotBlank() && ui.english.isNotBlank()
            copy.isEnabled = !ui.busy && !ui.recording && ui.selectedId != null
            val selected = ui.sessions.find { it.id == ui.selectedId }
            selection.text = if (selected == null) "Unsaved draft — save before publishing."
                else "Saved ${selected.id}\nRecorded ${selected.recordedAt}\n${publicationStatus(selected, ui.server)}"
            destination.isEnabled = !ui.busy && !ui.recording
            destination.text = "Publish server: ${ui.server} (change)"
            publish.isEnabled = !ui.busy && !ui.recording && selected != null
            publish.text = if (selected?.publishedServer == ui.server && selected.publishedAt != null)
                "Publish same record again / Confirm PUBLIC acknowledgement" else "Publish selected saved session PUBLICLY"
            if (displayedSessions != ui.sessions || displayedServer != ui.server) {
                displayedSessions = ui.sessions
                displayedServer = ui.server
                library.removeAllViews()
                if (ui.sessions.isEmpty()) library.addView(TextView(this).apply { text = "No saved sessions yet." })
                for (session in ui.sessions) {
                    library.addView(Button(this).apply {
                        text = "${session.title}\n${session.recordedAt}\n${publicationStatus(session, ui.server)}"
                        isAllCaps = false
                        setOnClickListener { discardDraftIfNeeded { state.open(session.id) } }
                    })
                }
            }
            for (index in 0 until library.childCount) library.getChildAt(index).isEnabled = !ui.busy && !ui.recording
        } finally { rendering = false }
    }

    private fun requestRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) state.startRecording()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun discardDraftIfNeeded(action: () -> Unit) {
        if (state.ui.value.hasDraft) {
            AlertDialog.Builder(this).setTitle("Discard unsaved draft?")
                .setMessage("This draft has not been saved. Continuing discards it; saved sessions stay on this device.")
                .setNegativeButton("Keep editing", null).setPositiveButton("Discard draft") { _, _ -> action() }.show()
        } else action()
    }

    private fun editDestination() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(state.ui.value.server)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this).setTitle("HTTPS publication server")
            .setMessage("Only explicit publication contacts this server. Use your smallanduseful HTTPS deployment.")
            .setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Save destination") { _, _ -> state.setServer(input.text.toString()) }.show()
    }

    private fun confirmPublish() {
        val current = state.ui.value
        val selected = current.sessions.find { it.id == current.selectedId } ?: return
        AlertDialog.Builder(this).setTitle("Publish text to a PUBLIC library?")
            .setMessage("Publish “${selected.title}” to ${current.server}?\n\nAnyone can read, copy, summarize and analyze BOTH the Chinese and English. Only this saved session is sent. No audio is sent. Publishing cannot be undone in this app. Ensure everyone whose speech is included consents.")
            .setNegativeButton("Keep local", null)
            .setPositiveButton("Publish PUBLICLY") { _, _ -> state.publishSelected(selected.id, current.server) }.show()
    }

    private fun publicationStatus(session: SavedSession, server: String): String = when {
        session.publishedAt == null -> "LOCAL ONLY — not acknowledged as published"
        session.publishedServer == server -> "PUBLIC — confirmed ${session.publishedAt} on $server"
        else -> "LOCAL for this server; PUBLIC on ${session.publishedServer} (${session.publishedAt})"
    }

    private fun updateText(editor: EditText, value: String) {
        if (editor.text.toString() != value) {
            editor.setText(value)
            editor.setSelection(editor.text.length)
        }
    }

    private fun heading(value: String, size: Float) = text(value).apply {
        textSize = size
        setPadding(0, dp(20), 0, dp(8))
        accessibilityHeadingCompat()
    }

    private fun TextView.accessibilityHeadingCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun text(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        setPadding(0, dp(6), 0, dp(6))
        root.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
        root.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun editor(label: String, max: Int, multiline: Boolean, changed: (String) -> Unit): EditText {
        val caption = text(label)
        return EditText(this).apply {
            id = View.generateViewId()
            isSaveEnabled = false // Draft persistence is explicitly in-memory, never Android saved-instance state.
            caption.labelFor = id
            hint = label
            // Saved records disable editing, not the readability of their source text.
            setTextColor(currentTextColor)
            filters = arrayOf(InputFilter.LengthFilter(max))
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            if (multiline) minLines = 3 else setSingleLine(true)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(value: Editable?) {
                    if (!rendering) changed(value.toString())
                }
            })
            root.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        // No foreground service: microphone never remains active when this capture UI is left.
        state.stopRecording(true)
        super.onStop()
    }
}
