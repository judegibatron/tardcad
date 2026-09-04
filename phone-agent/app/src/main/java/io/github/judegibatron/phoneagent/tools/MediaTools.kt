package io.github.judegibatron.phoneagent.tools

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import io.github.judegibatron.phoneagent.access.AgentNotificationListener
import io.github.judegibatron.phoneagent.util.Apps
import io.github.judegibatron.phoneagent.util.Fuzzy
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.max

/** Media session helpers; require notification access to see other apps' sessions. */
object MediaSessions {

    fun controllers(context: Context): List<MediaController> {
        if (!AgentNotificationListener.isEnabled(context)) return emptyList()
        val manager = context.getSystemService(MediaSessionManager::class.java)
        return try {
            manager.getActiveSessions(AgentNotificationListener.componentName(context))
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun stateName(controller: MediaController): String = when (controller.playbackState?.state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_STOPPED -> "stopped"
        null, PlaybackState.STATE_NONE -> "idle"
        else -> "other"
    }

    fun describe(context: Context, controller: MediaController): String {
        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
        return "${Apps.label(context, controller.packageName)} (${controller.packageName}): ${stateName(controller)}" +
            (title?.let { " - \"$it\"" } ?: "") + (artist?.let { " by $it" } ?: "")
    }

    fun summary(context: Context): String {
        val list = controllers(context)
        if (list.isEmpty()) {
            return if (AgentNotificationListener.isEnabled(context)) "no active media sessions"
            else "unknown (notification access not granted)"
        }
        return list.joinToString("; ") { describe(context, it) }
    }
}

class ListMediaTool : AgentTool(
    ToolSpec(
        name = "list_media",
        description = "List active media sessions: which apps are playing or paused and what they are playing.",
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput =
        ToolOutput.text(MediaSessions.summary(ctx.context))
}

class MediaControlTool : AgentTool(
    ToolSpec(
        name = "media_control",
        description = "Control media playback (music, podcasts, audiobooks, video): play/resume, pause, toggle, next, previous, stop. " +
            "Optionally target a specific app by name; otherwise the session that is playing, else the most recent one.",
        properties = mapOf(
            "action" to prop("string", "What to do", listOf("play", "pause", "toggle", "next", "previous", "stop")),
            "app" to prop("string", "Optional app name or package, e.g. Audible, Spotify, YouTube"),
        ),
        required = listOf("action"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val action = args.str("action") ?: return ToolOutput.error("action is required")
        val c = ctx.context
        val controllers = MediaSessions.controllers(c)
        val appQuery = args.str("app")

        val target: MediaController? = when {
            controllers.isEmpty() -> null
            appQuery != null -> {
                val scored = controllers.map { it to max(Fuzzy.score(appQuery, Apps.label(c, it.packageName)), Fuzzy.score(appQuery, it.packageName)) }
                val best = scored.maxByOrNull { it.second }
                if (best != null && best.second >= 0.5) {
                    best.first
                } else {
                    return ToolOutput.text(
                        "No active media session belongs to '$appQuery'. Active sessions: ${MediaSessions.summary(c)}. " +
                            "Open the app first (open_app) or control one of these instead.",
                    )
                }
            }
            else -> controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.first()
        }

        if (target != null) {
            val transport = target.transportControls
            val wasPlaying = target.playbackState?.state == PlaybackState.STATE_PLAYING
            val startsAudio = when (action) {
                "play" -> { transport.play(); true }
                "pause" -> { transport.pause(); false }
                "toggle" -> { if (wasPlaying) transport.pause() else transport.play(); !wasPlaying }
                "next" -> { transport.skipToNext(); true }
                "previous" -> { transport.skipToPrevious(); true }
                "stop" -> { transport.stop(); false }
                else -> return ToolOutput.error("Unknown action '$action'.")
            }
            delay(500)
            val after = MediaSessions.controllers(c).firstOrNull { it.packageName == target.packageName } ?: target
            return ToolOutput.text(
                "Sent '$action' to ${Apps.label(c, target.packageName)}. Now: ${MediaSessions.describe(c, after)}",
                suppressFollowUp = startsAudio,
            )
        }

        // Fallback: a media button press reaches whichever app last owned the media button.
        val key = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return ToolOutput.error("Unknown action '$action'.")
        }
        val audio = c.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        val note = if (AgentNotificationListener.isEnabled(c)) {
            "No active media session was found, so I sent a media-button '$action' to the last media app."
        } else {
            "Notification access is off, so I sent a media-button '$action' to the last media app instead of controlling a specific session."
        }
        return ToolOutput.text(note, suppressFollowUp = action in setOf("play", "toggle", "next", "previous"))
    }
}
