package io.github.judegibatron.phoneagent.session

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import io.github.judegibatron.phoneagent.PhoneAgentApp
import io.github.judegibatron.phoneagent.R
import io.github.judegibatron.phoneagent.access.AgentAccessibilityService
import io.github.judegibatron.phoneagent.agent.ClaudeAgent
import io.github.judegibatron.phoneagent.agent.SystemPrompt
import io.github.judegibatron.phoneagent.core.AgentLog
import io.github.judegibatron.phoneagent.core.Settings
import io.github.judegibatron.phoneagent.root.RootShell
import io.github.judegibatron.phoneagent.speech.AudioFocusHelper
import io.github.judegibatron.phoneagent.speech.SpeechInput
import io.github.judegibatron.phoneagent.speech.SpeechOutput
import io.github.judegibatron.phoneagent.tools.ToolContext
import io.github.judegibatron.phoneagent.tools.ToolRegistry
import io.github.judegibatron.phoneagent.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Runs voice sessions. Every trigger starts a brand-new conversation:
 * listen -> Claude (with tools) -> speak -> optionally listen for a follow-up -> close.
 */
class SessionController(private val app: Context) {

    enum class Source { HOLD, KEY, TILE, ASSIST, NOTIFICATION, MANUAL }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlayHost = OverlayHost(app)
    private val focus = AudioFocusHelper(app)
    private var speaker: SpeechOutput? = null
    private var job: Job? = null

    private val settings: Settings get() = PhoneAgentApp.get(app).settings
    private val root: RootShell get() = PhoneAgentApp.get(app).root

    val isActive: Boolean get() = job?.isActive == true

    /** A hold while a session is running cancels it; otherwise it starts one. */
    fun onHoldTrigger() {
        if (isActive) stop("hold during session") else start(Source.HOLD)
    }

    fun onKeyTrigger() {
        if (isActive) stop("key during session") else start(Source.KEY)
    }

    /** Starts a new session, cancelling any running one. [typedText] skips the first listen (testing). */
    fun start(source: Source, typedText: String? = null) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            runSession(source, typedText)
        }
    }

    fun stop(reason: String) {
        AgentLog.d(TAG, "session stopped: $reason")
        // Keep the reference: isActive turns false immediately, and the next start() still joins
        // this job so its cleanup (overlay, audio focus, TTS) cannot race a new session.
        job?.cancel()
    }

    private suspend fun runSession(source: Source, typedText: String?) {
        AgentLog.d(TAG, "session started from $source")
        vibrate()
        if (settings.apiKey.isNullOrBlank()) {
            notifySetup("Add your Claude API key in Phone Agent before starting a voice session.")
            return
        }
        val overlay = SessionOverlay(app) { stop("card closed") }
        if (!overlayHost.show(overlay)) {
            AgentLog.w(TAG, "no overlay permission; running without the session card")
        }
        // `su` can block for seconds the first time; take that hit off the main thread now so tools
        // and the device-context builder get a cached answer later.
        withContext(Dispatchers.IO) { root.isAvailable() }
        val stt = SpeechInput(app, settings)
        val tts = speaker ?: SpeechOutput(app).also { speaker = it }
        val agent = ClaudeAgent(settings, ToolRegistry.standard()) { SystemPrompt.deviceContext(app, root) }
        val toolContext = SessionToolContext(overlay, stt, tts)
        focus.request()
        try {
            var text: String? = typedText
            if (text == null) {
                overlay.setStatus("Listening…", SessionOverlay.Tone.LISTENING)
                text = listen(stt, overlay, followUp = false)
                if (text == null) {
                    overlay.setStatus("Didn't catch that", SessionOverlay.Tone.IDLE)
                    delay(1500)
                    return
                }
            }
            var turns = 0
            while (true) {
                turns++
                val utterance: String = text ?: break
                overlay.setUserText(utterance)
                overlay.setStatus("Thinking…", SessionOverlay.Tone.THINKING)
                val result = agent.runTurn(utterance, toolContext) { overlay.setActivity(it) }
                overlay.setActivity("")
                overlay.setAssistantText(result.text)
                overlay.setStatus("Speaking", SessionOverlay.Tone.SPEAKING)
                speak(tts, result.text)
                if (!settings.followUp || result.suppressFollowUp || turns >= MAX_TURNS) break
                overlay.setStatus("Listening…", SessionOverlay.Tone.LISTENING)
                text = listen(stt, overlay, followUp = true)
            }
            overlay.setStatus("Done", SessionOverlay.Tone.IDLE)
            delay(1200)
        } catch (e: CancellationException) {
            tts.stop()
            throw e
        } catch (e: Exception) {
            AgentLog.e(TAG, "session failed", e)
            val message = friendlyError(e)
            overlay.setStatus("Error", SessionOverlay.Tone.ERROR)
            overlay.setAssistantText(message)
            runCatching { speak(tts, message) }
            delay(2500)
        } finally {
            focus.abandon()
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                stt.release()
                overlayHost.hide()
            }
            AgentLog.d(TAG, "session ended")
        }
    }

    private suspend fun listen(stt: SpeechInput, overlay: SessionOverlay, followUp: Boolean): String? {
        val outcome = stt.listen(followUp) { partial -> overlay.setUserText(partial) }
        return when (outcome) {
            is SpeechInput.Outcome.Heard -> outcome.text
            SpeechInput.Outcome.NoSpeech -> null
            is SpeechInput.Outcome.Failed -> {
                if (followUp) {
                    AgentLog.w(TAG, "follow-up listen failed: ${outcome.message}")
                    null
                } else {
                    throw IllegalStateException("Speech recognition failed: ${outcome.message}")
                }
            }
        }
    }

    private suspend fun speak(tts: SpeechOutput, text: String) {
        if (settings.ttsEnabled) tts.speak(text)
    }

    private inner class SessionToolContext(
        private val overlay: SessionOverlay,
        private val stt: SpeechInput,
        private val tts: SpeechOutput,
    ) : ToolContext {
        override val context: Context get() = app
        override val settings: Settings get() = this@SessionController.settings
        override val root: RootShell get() = this@SessionController.root
        override val accessibility: AgentAccessibilityService? get() = AgentAccessibilityService.instance

        override suspend fun askUser(question: String): String? {
            overlay.setAssistantText(question)
            overlay.setStatus("Speaking", SessionOverlay.Tone.SPEAKING)
            speak(tts, question)
            overlay.setStatus("Listening…", SessionOverlay.Tone.LISTENING)
            val answer = listen(stt, overlay, followUp = true)
            if (answer != null) overlay.setUserText(answer)
            overlay.setStatus("Thinking…", SessionOverlay.Tone.THINKING)
            return answer
        }

        override suspend fun confirm(prompt: String): Boolean {
            val answer = askUser("$prompt Say yes to confirm.") ?: return false
            val yes = isAffirmative(answer)
            AgentLog.d(TAG, "confirmation answer '$answer' -> $yes")
            return yes
        }

        override fun status(text: String) = overlay.setActivity(text)
    }

    private fun vibrate() {
        runCatching {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                app.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Vibrator::class.java)
            }
            vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun notifySetup(message: String) {
        runCatching { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
        val open = PendingIntent.getActivity(
            app, 10, Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(app, PhoneAgentApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Phone Agent needs setup")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(2001, notification)
    }

    companion object {
        private const val TAG = "Session"
        private const val MAX_TURNS = 20

        private val NEGATIVE = listOf("no", "nope", "don't", "dont", "do not", "cancel", "stop", "never mind", "nevermind", "negative", "wrong")
        private val AFFIRMATIVE = listOf(
            "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "confirmed", "do it", "go ahead", "send it",
            "correct", "affirmative", "please", "absolutely", "right", "go for it", "proceed",
        )

        fun isAffirmative(answer: String): Boolean {
            val normalized = answer.lowercase(Locale.ROOT).replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
            if (normalized.isEmpty()) return false
            // Whole-word matching: "now" must not count as "no", "notes" must not count as "not".
            val padded = " $normalized "
            if (NEGATIVE.any { padded.contains(" $it ") }) return false
            return AFFIRMATIVE.any { padded.contains(" $it ") }
        }

        fun friendlyError(e: Throwable): String = when (e) {
            is UnauthorizedException -> "Claude rejected the API key. Please check it in Phone Agent."
            is PermissionDeniedException -> "This API key is not allowed to use the selected model."
            is NotFoundException -> "The selected model is not available for this API key."
            is RateLimitException -> "Claude is rate-limiting requests right now. Try again in a moment."
            is BadRequestException -> "Claude rejected the request: ${e.message?.take(160)}"
            is AnthropicIoException -> "I couldn't reach Claude. Check the internet connection."
            is AnthropicServiceException -> "Claude returned an error: ${e.message?.take(160)}"
            else -> "Something went wrong: ${e.message?.take(160) ?: e.javaClass.simpleName}"
        }
    }
}
