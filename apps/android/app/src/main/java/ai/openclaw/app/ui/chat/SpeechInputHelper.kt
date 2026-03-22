package ai.openclaw.app.ui.chat

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Lightweight speech-to-text helper for the chat composer.
 * Focused on one job: stream partial results into a text field, detect silence, fire a callback.
 *
 * Silence detection is string-content based: only resets the timer when the transcript text
 * actually changes — not on every onPartialResults callback (which fires on noise too).
 *
 * Stop distinction:
 *   - `stop()` is a manual stop (user tapped the button) — no auto-send
 *   - `onSilenceTimeout` fires when silence is detected — caller should auto-send
 */
class SpeechInputHelper(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val SILENCE_TIMEOUT_MS = 2_500L
        private const val RESTART_DELAY_MS = 200L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Exposed state
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript

    /** Called when silence timeout fires — consumer should auto-send the message. */
    var onSilenceTimeout: (() -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var silenceJob: Job? = null
    private var restartJob: Job? = null
    private var stopRequested = false
    private var scoActive = false
    private var scoReady = false

    // Track last transcript string to detect real changes vs noise callbacks
    private var lastTranscriptForSilenceCheck = ""

    // ── Bluetooth SCO: route mic through BT headset when connected ────────

    /**
     * BroadcastReceiver that waits for SCO audio to connect before starting
     * the SpeechRecognizer. This ensures the BT headset mic is active.
     */
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                scoReady = true
                // SCO connected — now start recognition on the BT mic.
                mainHandler.post { if (!stopRequested) startSession() }
            }
        }
    }

    /** Check if a Bluetooth audio device (headset/earbuds) is connected. */
    @Suppress("MissingPermission")
    private fun isBluetoothHeadsetConnected(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) return false
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return false
        // Check if SCO is available (headset profile connected).
        return audioManager.isBluetoothScoAvailableOffCall
    }

    /**
     * Start Bluetooth SCO if a headset is connected.
     * Returns true if we need to wait for the SCO receiver before starting recognition.
     */
    private fun startBluetoothSco(): Boolean {
        if (!isBluetoothHeadsetConnected()) return false
        try {
            context.registerReceiver(
                scoReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            )
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            scoActive = true
            return true // Wait for scoReceiver to fire before starting recognition.
        } catch (_: Throwable) {
            return false
        }
    }

    private fun stopBluetoothSco() {
        if (!scoActive) return
        try {
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            context.unregisterReceiver(scoReceiver)
        } catch (_: Throwable) { /* receiver may not be registered */ }
        scoActive = false
        scoReady = false
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Start listening. Caller must verify mic permission first. */
    fun start() {
        if (_isListening.value) return
        stopRequested = false
        scoReady = false
        mainHandler.post {
            try {
                recognizer?.destroy()
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(recognitionListener)
                }
                // If BT headset is connected, start SCO and wait for callback to begin recognition.
                // Otherwise start immediately on the built-in mic.
                val waitingForSco = startBluetoothSco()
                if (!waitingForSco) {
                    startSession()
                }
                // If waitingForSco, the scoReceiver will call startSession() when ready.
            } catch (_: Throwable) {
                _isListening.value = false
            }
        }
    }

    /**
     * Manual stop — user explicitly tapped the button.
     * Transcript is preserved; caller decides whether to send.
     * Does NOT trigger onSilenceTimeout.
     * Returns the current transcript text at time of stop.
     */
    fun stop(): String {
        stopRequested = true
        silenceJob?.cancel()
        silenceJob = null
        restartJob?.cancel()
        restartJob = null
        _isListening.value = false
        mainHandler.post {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            stopBluetoothSco()
        }
        return _transcript.value
    }

    /** Clear the transcript (e.g. after send). */
    fun clearTranscript() {
        _transcript.value = ""
        lastTranscriptForSilenceCheck = ""
    }

    private fun startSession() {
        val r = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Keep listening indefinitely — we control stop via silence detection
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        }
        _isListening.value = true
        r.startListening(intent)
    }

    private fun scheduleRestart() {
        if (stopRequested) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(RESTART_DELAY_MS)
            mainHandler.post {
                if (stopRequested) return@post
                try {
                    recognizer?.destroy()
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                        it.setRecognitionListener(recognitionListener)
                    }
                    startSession()
                } catch (_: Throwable) {
                    _isListening.value = false
                }
            }
        }
    }

    /**
     * Called whenever new transcript text arrives (partial or final).
     * Resets silence timer only when the string actually changed.
     */
    private fun onTranscriptUpdate(text: String) {
        if (text == lastTranscriptForSilenceCheck) return  // noise callback, no change — don't reset timer
        lastTranscriptForSilenceCheck = text
        _transcript.value = text

        // Real change detected — reset silence countdown
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(SILENCE_TIMEOUT_MS)
            if (!stopRequested) {
                // Silence timeout: stop and notify caller to auto-send
                stop()
                onSilenceTimeout?.invoke()
            }
        }
    }

    private val recognitionListener: RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _isListening.value = true }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { scheduleRestart() }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) onTranscriptUpdate(text)
            scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) onTranscriptUpdate(text)
        }

        override fun onError(error: Int) {
            if (stopRequested) return
            _isListening.value = false
            val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            if (fatal) { stopRequested = true; return }
            val delayMs = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 1_200L
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 2_500L
                else -> 600L
            }
            restartJob?.cancel()
            restartJob = scope.launch {
                delay(delayMs)
                mainHandler.post {
                    if (stopRequested) return@post
                    try {
                        recognizer?.destroy()
                        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                            it.setRecognitionListener(recognitionListener)
                        }
                        startSession()
                    } catch (_: Throwable) {
                        _isListening.value = false
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
