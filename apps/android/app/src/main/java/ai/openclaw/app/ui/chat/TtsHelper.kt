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
 * and expose reactive state (speaking) that Compose can observe.
 *
 * Auto-read is no longer toggled here — it's driven by the `lastSendWasAutoSilence`
 * flag in ChatComposer (silence-timeout auto-send → auto-speak the response).
 *
 * Usage:
 *   val tts = remember { TtsHelper(context) }
 *   DisposableEffect(Unit) { onDispose { tts.shutdown() } }
 *
 *   // per-message on-demand:
 *   IconButton(onClick = { tts.speak(message) }) { ... }
 */
class TtsHelper(context: Context) {

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val _ready = MutableStateFlow(false)

    /** Tracks how much of the streaming text we've already sent to TTS. */
    private var streamSpokenIndex = 0

    /** Regex for clause/sentence boundaries: . ! ? , : ; followed by whitespace. */
    private val clauseBoundary = Regex("""[.!?,;:]\s""")

    private val engine: TextToSpeech = TextToSpeech(context) { status ->
        _ready.value = (status == TextToSpeech.SUCCESS)
        if (_ready.value) {
            engine.language = Locale.getDefault()
        }
    }

    init {
        engine.setOnUtteranceProgressListener(
            @Suppress("OVERRIDE_DEPRECATION")
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _speaking.value = true }
                override fun onDone(utteranceId: String?)  {
                    // Only clear speaking if nothing else is queued.
                    if (!engine.isSpeaking) _speaking.value = false
                }
                override fun onError(utteranceId: String?) {
                    if (!engine.isSpeaking) _speaking.value = false
                }
            }
        )
    }

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

    // ── Streaming TTS ─────────────────────────────────────────────────────

    /**
     * Reset streaming state. Call when a new response stream begins.
     */
    fun streamReset() {
        streamSpokenIndex = 0
    }

    /**
     * Feed the current streaming partial text. Finds any new clause/sentence
     * boundaries since the last call and queues completed clauses for speech.
     * Uses QUEUE_ADD so clauses chain without gaps.
     */
    fun streamSpeak(partialText: String) {
        if (!_ready.value) return
        val plain = stripMarkdown(partialText)
        if (plain.length <= streamSpokenIndex) return

        val unspoken = plain.substring(streamSpokenIndex)
        var lastBoundary = -1

        // Find the last clause boundary in the unspoken portion.
        val match = clauseBoundary.findAll(unspoken).lastOrNull()
        if (match != null) {
            // Include the punctuation character, not the trailing space.
            lastBoundary = match.range.first + 1
        }

        if (lastBoundary > 0) {
            val chunk = unspoken.substring(0, lastBoundary).trim()
            if (chunk.isNotBlank()) {
                val mode = if (streamSpokenIndex == 0)
                    TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                engine.speak(chunk, mode, null, "stream-${System.currentTimeMillis()}")
            }
            streamSpokenIndex += lastBoundary
        }
    }

    /**
     * Flush any remaining text after the stream completes.
     * Speaks whatever hasn't been spoken yet.
     */
    fun streamFlush(fullText: String) {
        if (!_ready.value) return
        val plain = stripMarkdown(fullText)
        if (plain.length <= streamSpokenIndex) return
        val remainder = plain.substring(streamSpokenIndex).trim()
        if (remainder.isNotBlank()) {
            val mode = if (streamSpokenIndex == 0)
                TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(remainder, mode, null, "stream-flush-${System.currentTimeMillis()}")
        }
        streamSpokenIndex = plain.length
    }

    // ── Control ───────────────────────────────────────────────────────────

    /** Stop any in-progress speech immediately. */
    fun stop() {
        engine.stop()
        streamSpokenIndex = 0
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
