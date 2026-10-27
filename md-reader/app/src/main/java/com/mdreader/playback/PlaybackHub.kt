package com.mdreader.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mdreader.core.ReadableDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VoiceOption(val name: String, val label: String)

data class PlaybackState(
    val document: ReadableDocument? = null,
    /** Identifies the open file (its URI) so the reading position can be saved. */
    val documentKey: String? = null,
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val ttsReady: Boolean = false,
    val ttsProblem: String? = null,
    val voices: List<VoiceOption> = emptyList(),
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val voiceName: String? = null,
    val sleepAtMillis: Long? = null,
) {
    val segmentCount: Int get() = document?.segments?.size ?: 0
}

/**
 * Process-wide playback state shared by the UI and [PlaybackService].
 * The UI reads [state]; commands that need speech go to the service as intents.
 */
object PlaybackHub {

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    internal fun update(transform: (PlaybackState) -> PlaybackState) = _state.update(transform)

    fun load(context: Context, document: ReadableDocument, key: String, startIndex: Int) {
        if (PlaybackService.isRunning) send(context, PlaybackService.ACTION_PAUSE)
        val last = (document.segments.size - 1).coerceAtLeast(0)
        update {
            it.copy(
                document = document,
                documentKey = key,
                index = startIndex.coerceIn(0, last),
                isPlaying = false,
            )
        }
    }

    fun close(context: Context) {
        if (PlaybackService.isRunning) send(context, PlaybackService.ACTION_STOP)
        update { it.copy(document = null, documentKey = null, index = 0, isPlaying = false) }
    }

    fun play(context: Context) {
        if (state.value.segmentCount == 0) return
        send(context, PlaybackService.ACTION_PLAY, foreground = true)
    }

    fun pause(context: Context) {
        if (PlaybackService.isRunning) send(context, PlaybackService.ACTION_PAUSE)
        else update { it.copy(isPlaying = false) }
    }

    fun togglePlay(context: Context) {
        if (state.value.isPlaying) pause(context) else play(context)
    }

    fun next(context: Context) = seek(context, state.value.index + 1)

    fun previous(context: Context) = seek(context, state.value.index - 1)

    fun seek(context: Context, index: Int) {
        val count = state.value.segmentCount
        if (count == 0) return
        val target = index.coerceIn(0, count - 1)
        if (PlaybackService.isRunning) {
            send(context, PlaybackService.ACTION_SEEK) { putExtra(PlaybackService.EXTRA_INDEX, target) }
        } else {
            update { it.copy(index = target) }
        }
    }

    fun stop(context: Context) {
        if (PlaybackService.isRunning) send(context, PlaybackService.ACTION_STOP)
        update { it.copy(isPlaying = false, index = 0) }
    }

    /** Speed, pitch and voice live in the state; a playing service restarts the sentence to apply them. */
    fun setVoiceSettings(context: Context, speed: Float, pitch: Float, voiceName: String?) {
        val before = state.value
        update { it.copy(speed = speed, pitch = pitch, voiceName = voiceName) }
        val changed = before.speed != speed || before.pitch != pitch || before.voiceName != voiceName
        if (changed && before.isPlaying && PlaybackService.isRunning) {
            send(context, PlaybackService.ACTION_APPLY_SETTINGS)
        }
    }

    /** Stops reading after [minutes]; 0 cancels the timer. */
    fun setSleepTimer(context: Context, minutes: Int) {
        if (minutes <= 0 && !PlaybackService.isRunning) {
            update { it.copy(sleepAtMillis = null) }
            return
        }
        send(context, PlaybackService.ACTION_SLEEP, foreground = minutes > 0) {
            putExtra(PlaybackService.EXTRA_MINUTES, minutes)
        }
    }

    /** Lets the UI list voices and show problems before anything is played. */
    fun warmUp(context: Context) {
        if (!PlaybackService.isRunning && !state.value.ttsReady) {
            send(context, PlaybackService.ACTION_WARM_UP)
        }
    }

    /**
     * Only commands that start audible playback promote the service to the foreground;
     * the rest are sent while the UI is visible, where a plain startService() is allowed.
     */
    private fun send(
        context: Context,
        action: String,
        foreground: Boolean = false,
        extras: Intent.() -> Unit = {},
    ) {
        val intent = Intent(context, PlaybackService::class.java).setAction(action).apply(extras)
        if (foreground) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // App is in the background; the command is dropped rather than crashing.
            }
        }
    }
}
