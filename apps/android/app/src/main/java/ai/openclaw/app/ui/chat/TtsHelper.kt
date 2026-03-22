package ai.openclaw.app.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Lightweight wrapper around Android's built-in TextToSpeech engine.
 *
 * Why: centralise TTS init/teardown so the composable layer stays clean,
 * and expose reactive state (autoRead, speaking) that Compose can observe.
 *
 * Usage:
 *   val tts = remember { TtsHelper(context) }
 *   DisposableEffect(Unit) { onDispose { tts.shutdown() } }
 *
 *   // auto-read every new assistant message:
 *   LaunchedEffect(lastAssistantMessage) {
 *       if (tts.autoRead.value) tts.speak(lastAssistantMessage)
 *   }
 *
 *   // per-message on-demand:
 *   IconButton(onClick = { tts.speak(message) }) { ... }
 */
class TtsHelper(context: Context) {

    private val _autoRead = MutableStateFlow(false)
    val autoRead: StateFlow<Boolean> = _autoRead

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val _ready = MutableStateFlow(false)

    private val engine: TextToSpeech = TextToSpeech(context) { status ->
        _ready.value = (status == TextToSpeech.SUCCESS)
        if (_ready.value) {
            engine.language = Locale.getDefault()
        }
    }

    init {
        @Suppress("OVERRIDE_DEPRECATION")
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?)  { _speaking.value = true  }
            override fun onDone(utteranceId: String?)   { _speaking.value = false }
            override fun onError(utteranceId: String?)  { _speaking.value = false }
        })
    }

    /** Toggle the auto-read flag. */
    fun toggleAutoRead() { _autoRead.value = !_autoRead.value }

    /**
     * Speak the given text aloud, stripping markdown first.
     * No-ops silently if the engine isn't ready yet.
     */
    fun speak(text: String) {
        if (!_ready.value) return
        val plain = stripMarkdown(text)
        if (plain.isBlank()) return
        engine.speak(plain, TextToSpeech.QUEUE_FLUSH, null, "tts-${System.currentTimeMillis()}")
    }

    /** Stop any in-progress speech immediately. */
    fun stop() {
        engine.stop()
        _speaking.value = false
    }

    /** Must be called from DisposableEffect.onDispose to free the engine. */
    fun shutdown() {
        engine.stop()
        engine.shutdown()
        _speaking.value = false
    }

    // ── Markdown stripping ────────────────────────────────────────────────

    /**
     * Remove common markdown syntax so TTS reads plain prose.
     * Handles: bold/italic/code/strikethrough markers, headers, links,
     * blockquotes, list bullets, horizontal rules, and excess whitespace.
     */
    private fun stripMarkdown(text: String): String = text
        .replace(Regex("""!\[.*?]\(.*?\)"""), "")          // images
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")  // links → label only
        .replace(Regex("""```[\s\S]*?```"""), "")           // fenced code blocks
        .replace(Regex("""`[^`]*`"""), "")                  // inline code
        .replace(Regex("""^#{1,6}\s+""", RegexOption.MULTILINE), "")  // headers
        .replace(Regex("""[*_]{1,3}([^*_]+)[*_]{1,3}"""), "$1")      // bold/italic
        .replace(Regex("""~~([^~]+)~~"""), "$1")            // strikethrough
        .replace(Regex("""^>\s*""", RegexOption.MULTILINE), "")       // blockquotes
        .replace(Regex("""^[-*+]\s+""", RegexOption.MULTILINE), "")   // unordered lists
        .replace(Regex("""^\d+\.\s+""", RegexOption.MULTILINE), "")   // ordered lists
        .replace(Regex("""^---+$""", RegexOption.MULTILINE), "")      // horizontal rules
        .replace(Regex("""\s{2,}"""), " ")                            // excess spaces
        .trim()
}
