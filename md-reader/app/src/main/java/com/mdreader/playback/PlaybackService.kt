package com.mdreader.playback

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mdreader.MainActivity
import com.mdreader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Owns the TextToSpeech engine and reads the current document one segment at a time.
 * Runs as a media-playback foreground service so reading continues with the screen off,
 * and exposes a media session for lock-screen, notification and headset controls.
 */
class PlaybackService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_PLAY = "com.mdreader.action.PLAY"
        const val ACTION_PAUSE = "com.mdreader.action.PAUSE"
        const val ACTION_NEXT = "com.mdreader.action.NEXT"
        const val ACTION_PREVIOUS = "com.mdreader.action.PREVIOUS"
        const val ACTION_SEEK = "com.mdreader.action.SEEK"
        const val ACTION_STOP = "com.mdreader.action.STOP"
        const val ACTION_SLEEP = "com.mdreader.action.SLEEP"
        const val ACTION_APPLY_SETTINGS = "com.mdreader.action.APPLY_SETTINGS"
        const val ACTION_WARM_UP = "com.mdreader.action.WARM_UP"
        const val EXTRA_INDEX = "index"
        const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_FROM_NOTIFICATION = "from_notification"

        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var playWhenReady = false
    private var isForeground = false

    /** True from the first play until a full stop; the notification stays up (paused) in between. */
    private var notificationWanted = false

    /** Bumped whenever speech is interrupted so callbacks from older utterances are ignored. */
    private var utteranceToken = 0

    private val sleepRunnable = Runnable {
        PlaybackHub.update { it.copy(sleepAtMillis = null) }
        pause()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.channel_name))
                .setDescription(getString(R.string.channel_description))
                .setShowBadge(false)
                .build(),
        )

        session = MediaSessionCompat(this, "MdReader").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = seek(PlaybackHub.state.value.index + 1)
                override fun onSkipToPrevious() = seek(PlaybackHub.state.value.index - 1)
                override fun onStop() = stopEverything()
            })
            isActive = true
        }

        tts = TextToSpeech(this, this)

        scope.launch {
            PlaybackHub.state
                .map { NotificationModel(it.document?.title, it.index, it.segmentCount, it.isPlaying) }
                .distinctUntilChanged()
                .collect { refreshSessionAndNotification() }
        }
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            PlaybackHub.update {
                it.copy(ttsReady = false, ttsProblem = "음성 엔진을 시작할 수 없습니다. 기기 설정에서 TTS 엔진을 확인해 주세요.")
            }
            playWhenReady = false
            pause()
            return
        }
        ttsReady = true
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                handler.post { onUtteranceFinished(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { onUtteranceFinished(utteranceId) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handler.post { onUtteranceFinished(utteranceId) }
            }
        })

        val language = engine.setLanguage(Locale.KOREAN)
        val problem = if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            "한국어 음성이 설치되어 있지 않습니다. 설정 > 텍스트 음성 변환에서 한국어 음성 데이터를 설치해 주세요."
        } else {
            null
        }
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == Locale.KOREAN.language && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .sortedBy { it.name }
            .map { voice ->
                val suffix = if (voice.isNetworkConnectionRequired) " (온라인)" else ""
                VoiceOption(voice.name, voice.name + suffix)
            }
        PlaybackHub.update { it.copy(ttsReady = true, ttsProblem = problem, voices = voices) }

        if (playWhenReady) {
            playWhenReady = false
            speakCurrent()
        } else if (!PlaybackHub.state.value.isPlaying && !isForeground) {
            // Started only to list voices: nothing to keep running for.
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Notification buttons start the service with startForegroundService(), which obliges it
        // to enter the foreground even for a pause; it leaves again at the end if nothing plays.
        val fromNotification = intent?.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) == true
        if (fromNotification) goForeground()
        when (intent?.action) {
            ACTION_PLAY -> {
                goForeground()
                play()
            }
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> seek(PlaybackHub.state.value.index + 1)
            ACTION_PREVIOUS -> seek(PlaybackHub.state.value.index - 1)
            ACTION_SEEK -> seek(intent.getIntExtra(EXTRA_INDEX, PlaybackHub.state.value.index))
            ACTION_STOP -> stopEverything()
            ACTION_APPLY_SETTINGS -> if (PlaybackHub.state.value.isPlaying) speakCurrent()
            ACTION_SLEEP -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
                if (minutes > 0) goForeground()
                setSleepTimer(minutes)
            }
            ACTION_WARM_UP -> Unit
        }
        // Nothing audible: keep the paused notification but stop being a foreground service.
        if (isForeground && !PlaybackHub.state.value.isPlaying && !playWhenReady) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            isForeground = false
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!PlaybackHub.state.value.isPlaying) stopEverything()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonAudioFocus()
        session.isActive = false
        session.release()
        PlaybackHub.update { it.copy(isPlaying = false, sleepAtMillis = null) }
        super.onDestroy()
    }

    private fun play() {
        val state = PlaybackHub.state.value
        if (state.segmentCount == 0) {
            stopEverything()
            return
        }
        // Starting again after the last segment finished goes back to the beginning.
        val index = if (state.index >= state.segmentCount) 0 else state.index
        PlaybackHub.update { it.copy(isPlaying = true, index = index) }
        requestAudioFocus()
        goForeground()
        if (ttsReady) speakCurrent() else playWhenReady = true
    }

    private fun pause() {
        utteranceToken++
        playWhenReady = false
        tts?.stop()
        PlaybackHub.update { it.copy(isPlaying = false) }
        abandonAudioFocus()
        if (isForeground) {
            // Keep the notification so playback can be resumed from it, but let the service be stopped.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            isForeground = false
        }
        refreshSessionAndNotification()
    }

    private fun seek(index: Int) {
        val count = PlaybackHub.state.value.segmentCount
        if (count == 0) return
        PlaybackHub.update { it.copy(index = index.coerceIn(0, count - 1)) }
        if (PlaybackHub.state.value.isPlaying) speakCurrent()
    }

    private fun stopEverything() {
        utteranceToken++
        playWhenReady = false
        tts?.stop()
        handler.removeCallbacks(sleepRunnable)
        PlaybackHub.update { it.copy(isPlaying = false, sleepAtMillis = null) }
        abandonAudioFocus()
        notificationWanted = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        isForeground = false
        stopSelf()
    }

    private fun speakCurrent() {
        val engine = tts ?: return
        val state = PlaybackHub.state.value
        val segment = state.document?.segments?.getOrNull(state.index) ?: return
        utteranceToken++
        engine.setSpeechRate(state.speed)
        engine.setPitch(state.pitch)
        val voice = state.voiceName?.let { name -> runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == name } }
        if (voice != null) engine.setVoice(voice) else engine.setLanguage(Locale.KOREAN)
        engine.speak(segment, TextToSpeech.QUEUE_FLUSH, Bundle(), "$utteranceToken:${state.index}")
    }

    private fun onUtteranceFinished(utteranceId: String?) {
        val token = utteranceId?.substringBefore(':')?.toIntOrNull()
        if (token != utteranceToken || !PlaybackHub.state.value.isPlaying) return
        val state = PlaybackHub.state.value
        if (state.index + 1 < state.segmentCount) {
            PlaybackHub.update { it.copy(index = state.index + 1) }
            speakCurrent()
        } else {
            // Finished the document: rewind so the next play starts from the top.
            PlaybackHub.update { it.copy(index = 0) }
            pause()
        }
    }

    private fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes > 0) {
            val delay = minutes * 60_000L
            handler.postDelayed(sleepRunnable, delay)
            PlaybackHub.update { it.copy(sleepAtMillis = System.currentTimeMillis() + delay) }
        } else {
            PlaybackHub.update { it.copy(sleepAtMillis = null) }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            handler.post { pause() }
        }
    }

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            isForeground = true
            notificationWanted = true
        } catch (e: RuntimeException) {
            // Android 12+ refuses a foreground start from the background (e.g. a headset button
            // long after the app was closed); reading still works while the process lives.
        }
    }

    private fun refreshSessionAndNotification() {
        val state = PlaybackHub.state.value
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (state.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.document?.title ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, progressText(state))
                .build(),
        )

        if (!notificationWanted || state.document == null) return
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val state = PlaybackHub.state.value
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playPause = if (state.isPlaying) {
            NotificationCompat.Action(R.drawable.ic_pause, getString(R.string.action_pause), serviceIntent(ACTION_PAUSE, 2))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, getString(R.string.action_play), serviceIntent(ACTION_PLAY, 3))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.document?.title ?: getString(R.string.app_name))
            .setContentText(state.document?.segments?.getOrNull(state.index)?.take(120) ?: "")
            .setSubText(progressText(state))
            .setContentIntent(contentIntent)
            .setDeleteIntent(serviceIntent(ACTION_STOP, 6))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(state.isPlaying)
            .addAction(R.drawable.ic_previous, getString(R.string.action_previous), serviceIntent(ACTION_PREVIOUS, 4))
            .addAction(playPause)
            .addAction(R.drawable.ic_next, getString(R.string.action_next), serviceIntent(ACTION_NEXT, 5))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java)
            .setAction(action)
            .putExtra(EXTRA_FROM_NOTIFICATION, true)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getForegroundService(this, requestCode, intent, flags)
    }

    private fun progressText(state: PlaybackState): String =
        if (state.segmentCount == 0) "" else "${state.index + 1} / ${state.segmentCount}"

    private data class NotificationModel(val title: String?, val index: Int, val count: Int, val playing: Boolean)
}
