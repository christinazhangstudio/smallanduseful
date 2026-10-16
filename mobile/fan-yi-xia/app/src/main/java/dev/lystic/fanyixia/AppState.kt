package dev.lystic.fanyixia

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private fun timestampNow(): String = Instant.ofEpochMilli(System.currentTimeMillis()).toString()

internal data class AppUi(
    val busy: Boolean = true,
    val recording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val whisperReady: Boolean = false,
    val translationReady: Boolean = false,
    val status: String = "Checking local models and saved sessions…",
    val title: String = "",
    val chinese: String = "",
    val english: String = "",
    val recordedAt: String = timestampNow(),
    val selectedId: String? = null,
    val sessions: List<SavedSession> = emptyList(),
    val server: String = Publisher.DEFAULT_SERVER
) {
    val ready get() = whisperReady && translationReady
    val hasDraft get() = selectedId == null && (title.isNotBlank() || chinese.isNotBlank() || english.isNotBlank())
}

internal class AppState(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("settings", 0)
    private val modelFiles = ModelFiles(application)
    private val store = SessionStore(application)
    private val microphone = Microphone()
    private val translator = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.ENGLISH).build())
    private val chineseModel = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
    private val mutable = MutableStateFlow(AppUi(server = preferences.getString("server", Publisher.DEFAULT_SERVER)!!))
    val ui = mutable.asStateFlow()
    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.list() }
                mutable.update { it.copy(sessions = saved) }
                val whisperReady = withContext(Dispatchers.IO) { modelFiles.verified() }
                val translationReady = RemoteModelManager.getInstance().isModelDownloaded(chineseModel).await()
                mutable.update { it.copy(whisperReady = whisperReady, translationReady = translationReady,
                    status = if (whisperReady && translationReady) "Ready offline. Nothing is shared automatically."
                    else "Initial preparation needed. Downloads require internet; later recording and translation work offline.") }
            } catch (error: Exception) {
                showError(error)
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun prepare() = work("Downloading multilingual Whisper base (148 MB)…") {
        withContext(Dispatchers.IO) {
            modelFiles.prepare { percent -> mutable.update { it.copy(status = "Downloading and verifying Whisper base: $percent% of 148 MB…") } }
        }
        mutable.update { it.copy(whisperReady = true, status = "Preparing ML Kit Chinese → English (about 30 MB). This can take several minutes…") }
        // Download is only requested by this explicit preparation action, never by translate().
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        check(RemoteModelManager.getInstance().isModelDownloaded(chineseModel).await()) {
            "ML Kit did not confirm the Chinese model. Retry preparation."
        }
        mutable.update { it.copy(translationReady = true, status = "Both models ready offline. You can turn on airplane mode.") }
    }

    fun titleChanged(text: String) {
        if (!canEdit()) return
        mutable.update { it.copy(title = text) }
    }

    fun chineseChanged(text: String) {
        if (!canEdit() || text == mutable.value.chinese) return
        mutable.update { it.copy(chinese = text, english = "", status = "Chinese changed. Translate again before saving; old English was cleared.") }
    }

    fun newDraft() {
        if (mutable.value.busy || mutable.value.recording) return
        mutable.update { it.copy(title = "", chinese = "", english = "", selectedId = null,
            recordedAt = timestampNow(), status = "New unsaved draft. Save locally to keep it across app restarts.") }
    }

    fun open(id: String) {
        if (mutable.value.busy || mutable.value.recording) return
        val saved = mutable.value.sessions.find { it.id == id } ?: return
        mutable.update { it.copy(selectedId = saved.id, title = saved.title, chinese = saved.chinese,
            english = saved.english, recordedAt = saved.recordedAt, status = "Saved record is read-only. Use Edit a copy for changes; the copy gets a new ID.") }
    }

    fun copySelected() {
        if (mutable.value.busy || mutable.value.recording || mutable.value.selectedId == null) return
        mutable.update { it.copy(selectedId = null, status = "Editing an unsaved copy. Saving creates a new session ID; original remains unchanged.") }
    }

    fun startRecording() {
        val state = mutable.value
        if (state.busy || state.recording || !state.ready || state.selectedId != null) return
        try {
            val started = SystemClock.elapsedRealtime()
            microphone.start { Handler(Looper.getMainLooper()).post { stopRecording(false) } }
            mutable.update { it.copy(recording = true, elapsedSeconds = 0, title = state.title,
                chinese = "", english = "", recordedAt = timestampNow(), status = "Recording Mandarin locally. Stop to transcribe; maximum 60 seconds.") }
            ticker = viewModelScope.launch {
                while (mutable.value.recording) {
                    mutable.update { it.copy(elapsedSeconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt().coerceAtMost(60)) }
                    delay(250)
                }
            }
        } catch (error: Exception) {
            showError(error)
        }
    }

    fun stopRecording(discard: Boolean) {
        if (!mutable.value.recording) return
        ticker?.cancel()
        mutable.update { it.copy(recording = false) }
        try {
            val audio = microphone.stop()
            if (discard) {
                mutable.update { it.copy(status = "Recording stopped and discarded because the capture screen was left. No audio was saved or uploaded.") }
                return
            }
            require(audio.count >= 1600) { "Recording was too short. Record at least a moment of speech and retry." }
            work("Transcribing Mandarin offline with Whisper base…") {
                val text = withContext(Dispatchers.Default) {
                    WhisperNative.transcribe(modelFiles.model.absolutePath, audio.samples, audio.count).toString(Charsets.UTF_8).trim()
                }
                require(text.isNotBlank()) { "No speech recognized. Retry in a quieter place, or type the Chinese text." }
                mutable.update { it.copy(chinese = text, english = "", status = "Mandarin transcribed offline. Review Chinese, then Translate offline. Audio has been discarded.") }
            }
        } catch (error: Exception) {
            showError(error)
        }
    }

    fun translate() {
        val text = mutable.value.chinese.trim()
        if (!canEdit() || !mutable.value.translationReady) return
        work("Translating Chinese → English offline with ML Kit…") {
            require(text.isNotBlank() && text.length <= 4000) { "Chinese must contain 1–4000 UTF-16 characters." }
            // No downloadModelIfNeeded call here: no cloud translation or model-download fallback.
            val english = translator.translate(text).await().trim()
            require(english.isNotBlank() && english.length <= 16000) { "English translation is empty or too long to save. Shorten the Chinese and retry." }
            mutable.update { it.copy(chinese = text, english = english, status = "Translated offline. Review both languages, then Save locally.") }
        }
    }

    fun save() {
        val draft = mutable.value
        if (!canEdit()) return
        work("Saving only on this device…") {
            val title = draft.title.trim()
            val chinese = draft.chinese.trim()
            val english = draft.english.trim()
            require(title.isNotBlank() && title.length <= 160) { "Enter a title of 1–160 characters." }
            require(chinese.isNotBlank() && chinese.length <= 4000 && english.isNotBlank() && english.length <= 16000) {
                "Review Chinese (1–4000 characters) and translate it before saving."
            }
            val session = SavedSession(UUID.randomUUID().toString(), title, chinese, english, draft.recordedAt)
            val sessions = withContext(Dispatchers.IO) { store.save(session); store.list() }
            mutable.update { it.copy(sessions = sessions, selectedId = session.id, title = title, chinese = chinese,
                english = english, status = "Saved locally. It is private until you explicitly Publish PUBLICLY.") }
        }
    }

    fun setServer(value: String) {
        if (mutable.value.busy || mutable.value.recording) return
        try {
            val normalized = Publisher.normalizeServer(value)
            check(preferences.edit().putString("server", normalized).commit()) { "Server setting could not be saved." }
            mutable.update { it.copy(server = normalized, status = "Publish destination saved. No data was sent.") }
        } catch (error: Exception) {
            showError(error)
        }
    }

    fun publishSelected(confirmedId: String, confirmedServer: String) {
        val state = mutable.value
        if (state.selectedId != confirmedId || state.server != confirmedServer) return
        val saved = state.sessions.find { it.id == confirmedId } ?: return
        work("Publishing selected text PUBLICLY to $confirmedServer…") {
            val sessions = withContext(Dispatchers.IO) {
                val publishedAt = Publisher.publish(confirmedServer, saved)
                store.acknowledge(saved.id, confirmedServer, publishedAt)
                store.list()
            }
            mutable.update { it.copy(sessions = sessions, status = "Publication confirmed and acknowledgement saved. This text is now PUBLIC on $confirmedServer.") }
        }
    }

    fun permissionDenied() {
        mutable.update { it.copy(status = "Microphone permission denied. Tap Record to retry, or allow Microphone in Android Settings → Apps → Fan yi Xia → Permissions. Typing Chinese still works.") }
    }

    private fun canEdit() = !mutable.value.busy && !mutable.value.recording && mutable.value.selectedId == null

    private fun work(message: String, action: suspend () -> Unit) {
        if (mutable.value.busy || mutable.value.recording) return
        mutable.update { it.copy(busy = true, status = message) }
        viewModelScope.launch {
            try { action() }
            catch (error: Exception) { showError(error) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private fun showError(error: Exception) {
        if (error is CancellationException) throw error
        mutable.update { it.copy(status = error.message ?: "Operation failed. Local saved sessions are unchanged; retry when ready.") }
    }

    override fun onCleared() {
        ticker?.cancel()
        runCatching { microphone.stop() }
        translator.close()
        store.close()
        super.onCleared()
    }
}
