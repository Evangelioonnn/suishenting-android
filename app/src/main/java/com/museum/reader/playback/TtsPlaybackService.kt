package com.museum.reader.playback

import android.app.*
import android.content.*
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.museum.reader.MainActivity
import com.museum.reader.R
import com.museum.reader.data.AudioFiles
import com.museum.reader.data.DocumentRepository
import com.museum.reader.domain.*
import com.museum.reader.model.*
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

/** Sole owner of output resources; all commands and callbacks execute on the main looper. */
class TtsPlaybackService : Service() {
    private val outputHandler = Handler(Looper.getMainLooper())
    private val callbacks = Handler(Looper.getMainLooper())
    private val timerHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generation = OutputGeneration()
    private var outputJob: Job? = null
    private var player: ExoPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitializing = false
    private var ttsLanguageAvailable = false
    private var ttsToken = 0L
    private var activeUtterance: String? = null
    private var chunks = emptyList<String>()
    private var chunkIndex = 0
    private var foreground = false
    private var destroyed = false
    private var closing = false
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var focusGeneration = 0L
    private var focusDucked = false
    private var ttsWaitingForFocus = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var mediaSession: MediaSession
    private val snapshot get() = PlaybackBus.snapshot.value

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) execute(PlaybackCommand.Pause)
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val headphoneTypes = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET) +
                if (Build.VERSION.SDK_INT >= 31) setOf(AudioDeviceInfo.TYPE_BLE_HEADSET) else emptySet()
            if (snapshot.isPlaying && removed.any { it.isSink && it.type in headphoneTypes })
                execute(PlaybackCommand.Pause)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "随身听播放控制", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) })
        mediaSession = MediaSession(this, "SuishentingPlayback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = execute(PlaybackCommand.Play)
                override fun onPause() = execute(PlaybackCommand.Pause)
                override fun onStop() = execute(PlaybackCommand.Stop)
                override fun onSkipToPrevious() = execute(PlaybackCommand.Move(-1))
                override fun onSkipToNext() = execute(PlaybackCommand.Move(1))
            }, callbacks)
            setSessionActivity(openIntent())
            isActive = true
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        audioManager.registerAudioDeviceCallback(devices, callbacks)
        PlaybackController.attach(this)
        scheduleTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                // Even a superseded start must satisfy Android's foreground-start contract.
                startForeground(NOTIFICATION_ID, notification())
                foreground = true
                if (!intent.hasExtra("request") || PlaybackController.consumeStart())
                    execute(PlaybackCommand.Play)
                else if (!snapshot.isPlaying) finishService()
            }
            ACTION_PAUSE -> execute(PlaybackCommand.Pause)
            ACTION_STOP -> execute(PlaybackCommand.Stop)
            ACTION_PREVIOUS -> execute(PlaybackCommand.Move(-1))
            ACTION_NEXT -> execute(PlaybackCommand.Move(1))
            else -> finishService()
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    internal fun execute(command: PlaybackCommand) {
        if (destroyed || closing) return
        if ((PlaybackController.maintenance || PlaybackController.capturing) && (command == PlaybackCommand.Play || command is PlaybackCommand.Load && command.play)) {
            publish(snapshot.copy(status = PlaybackStatus.PAUSED, error = if (PlaybackController.capturing) "正在录音，请先结束采集" else "正在整理音频，请稍后播放"))
            finishService()
            return
        }
        val before = snapshot
        var next = PlaybackSession.reduce(before, command)
        if (SleepDeadline.expired(next.timerDeadlineElapsed, SystemClock.elapsedRealtime()))
            next = next.copy(status = if (command == PlaybackCommand.Play) next.status else PlaybackStatus.PAUSED, timerDeadlineElapsed = null)
        val changesOutput = command is PlaybackCommand.Load || command is PlaybackCommand.Seek ||
            command is PlaybackCommand.Move || command == PlaybackCommand.Play ||
            command == PlaybackCommand.Pause || command == PlaybackCommand.Stop ||
            command == PlaybackCommand.Clear || (command is PlaybackCommand.Refresh &&
                command.queue.documentId == before.documentId && command.queue.contentVersion != before.contentVersion)
        if (changesOutput) invalidateOutput()
        publish(next)
        scheduleTimer()
        if (next.isPlaying && changesOutput) {
            if (next.sentences.isEmpty()) return finishService()
            startForeground(NOTIFICATION_ID, notification())
            foreground = true
            if (!requestFocus()) return fail("其他应用正在使用音频，请返回后重试")
            acquireWakeLock()
            speakCurrent()
        } else if (!next.isPlaying && changesOutput) {
            releaseResources()
            if (command == PlaybackCommand.Stop || command == PlaybackCommand.Clear) finishService()
            else {
                updateNotification()
                outputHandler.postDelayed({ if (!snapshot.isPlaying) finishService() }, PAUSED_TIMEOUT)
            }
        } else updateNotification()
    }

    private fun publish(value: PlaybackSnapshot) = PlaybackController.publish(this, value)

    private fun speakCurrent() {
        if (!snapshot.isPlaying) return
        invalidateOutput()
        val token = generation.next()
        val captured = snapshot
        val text = captured.sentences.getOrNull(captured.currentIndex) ?: return complete()
        publish(captured.copy(outputSource = "正在准备声音", appliedRate = captured.requestedRate))
        outputJob = scope.launch {
            val candidates = withContext(Dispatchers.IO) {
                val hash = DocumentRepository.textHash(text)
                captured.audioCandidates[captured.sentenceIds.getOrNull(captured.currentIndex)].orEmpty()
                    .filter { it.archivedAt == null && it.textHash == hash &&
                        (it.kind == AudioKind.RECORDING || captured.cloudEnabled) }
                    .sortedBy { when {
                        it.kind == AudioKind.RECORDING -> 0
                        it.provider == captured.preferredCloudProvider && it.voiceId == captured.preferredCloudVoice -> 1
                        else -> 2
                    } }
                    .filter { asset -> runCatching { AudioFiles.requireOwned(this@TtsPlaybackService, File(asset.path)).isFile }
                        .getOrDefault(false) }
            }
            if (!accepts(token)) return@launch
            tryCandidate(token, candidates, 0, text)
        }
    }

    private fun tryCandidate(token: Long, candidates: List<SentenceAudio>, at: Int, text: String) {
        if (!accepts(token)) return
        player?.release(); player = null
        val asset = candidates.getOrNull(at)
        if (asset == null) { useSystemVoice(token, text); return }
        // A listener belongs to this exact player/attempt, never a reused document:index identity.
        val current = ExoPlayer.Builder(this).build()
        player = current
        current.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== current || !accepts(token)) return
                if (playbackState == Player.STATE_ENDED) advance(token)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player !== current || !accepts(token)) return
                tryCandidate(token, candidates, at + 1, text)
            }
        })
        try {
            current.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
            current.setMediaItem(MediaItem.fromUri(File(asset.path).toURI().toString()))
            current.setPlaybackSpeed(snapshot.requestedRate)
            current.volume = if (focusDucked) DUCK_VOLUME else 1f
            publish(snapshot.copy(outputSource = if (asset.kind == AudioKind.RECORDING) "本人录音"
                else "云缓存 · " + when (asset.provider) { "XUNFEI" -> "讯飞"; "BAIDU" -> "百度"; else -> "自然声音" },
                appliedRate = snapshot.requestedRate))
            current.prepare()
            current.play()
            updateNotification()
        } catch (_: RuntimeException) {
            tryCandidate(token, candidates, at + 1, text)
        }
    }

    private fun useSystemVoice(token: Long, text: String) {
        if (!accepts(token)) return
        ttsToken = token
        chunks = text.chunked((TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500))
        chunkIndex = 0
        if (ttsReady) return startSystemVoice(token)
        if (ttsInitializing) return
        ttsInitializing = true
        tts = TextToSpeech(this) { status ->
            callbacks.post {
                if (destroyed) return@post
                ttsInitializing = false
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    ttsLanguageAvailable = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                }
                if (accepts(ttsToken)) startSystemVoice(ttsToken)
            }
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    callbacks.post {
                        if (utteranceId == null || utteranceId != activeUtterance || !accepts(ttsToken)) return@post
                        if (++chunkIndex < chunks.size) speakChunk(ttsToken) else advance(ttsToken)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = handleTtsError(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = handleTtsError(utteranceId)
            })
        }
    }

    private fun startSystemVoice(token: Long) {
        if (!accepts(token)) return
        if (!ttsReady || !ttsLanguageAvailable) return fail(
            "本句没有可用音频，手机也没有可用的中文声音。请录制本句、生成云缓存，或在设置中安装中文声音")
        val engine = tts ?: return
        // Reset default first, so clearing a previously selected voice really takes effect.
        engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        snapshot.globalSettings.voiceName?.let { name ->
            engine.voices?.firstOrNull { it.name == name }?.let { engine.voice = it }
        }
        engine.setSpeechRate(snapshot.requestedRate)
        engine.setPitch(snapshot.globalSettings.pitch)
        publish(snapshot.copy(outputSource = "系统声音", appliedRate = snapshot.requestedRate))
        updateNotification()
        speakChunk(token)
    }

    private fun speakChunk(token: Long) {
        if (!accepts(token)) return
        if (focusDucked) {
            ttsWaitingForFocus = true
            publish(snapshot.copy(outputSource = "系统声音 · 等待提示结束"))
            updateNotification()
            return
        }
        val text = chunks.getOrNull(chunkIndex) ?: return advance(token)
        activeUtterance = "$token:$chunkIndex"
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), activeUtterance)
        if (result != TextToSpeech.SUCCESS) fail("系统声音无法朗读本句，请检查中文声音设置后重试")
    }
    private fun handleTtsError(id: String?) {
        callbacks.post {
            if (id != null && id == activeUtterance && accepts(ttsToken))
                fail("系统声音播放失败，请检查中文声音设置或生成本句的云缓存")
        }
    }

    private fun accepts(token: Long) = !destroyed && !closing && snapshot.isPlaying && generation.accepts(token)

    private fun advance(token: Long) {
        if (!accepts(token)) return
        val before = snapshot
        val next = PlaybackRules.nextIndex(before.currentIndex, before.sentences.size,
            before.loopMode, before.rangeStart, before.rangeEnd)
        invalidateOutput()
        if (next == null) return complete()
        val crossed = before.paragraphIndices.getOrNull(before.currentIndex) != before.paragraphIndices.getOrNull(next)
        val pause = if (crossed) before.paragraphPauseMs ?: before.globalSettings.paragraphPauseMs
            else before.sentencePauseMs ?: before.globalSettings.sentencePauseMs
        publish(before.copy(currentIndex = next, outputSource = "句间停顿", appliedRate = null))
        updateNotification()
        val pending = generation.next()
        outputHandler.postDelayed({ if (accepts(pending)) speakCurrent() }, pause.toLong())
    }

    private fun complete() {
        invalidateOutput()
        publish(snapshot.copy(status = PlaybackStatus.COMPLETED, outputSource = "全文已结束",
            appliedRate = null, timerDeadlineElapsed = null))
        finishService()
    }
    private fun fail(message: String) {
        invalidateOutput()
        publish(snapshot.copy(status = PlaybackStatus.ERROR, error = message,
            outputSource = "声音不可用", appliedRate = null))
        finishService()
    }
    private fun invalidateOutput() {
        generation.next()
        outputHandler.removeCallbacksAndMessages(null)
        outputJob?.cancel(); outputJob = null
        activeUtterance = null
        ttsWaitingForFocus = false
        tts?.stop()
        player?.release(); player = null
    }
    private fun scheduleTimer() {
        timerHandler.removeCallbacksAndMessages(null)
        val deadline = snapshot.timerDeadlineElapsed ?: return
        timerHandler.postDelayed({
            if (SleepDeadline.expired(snapshot.timerDeadlineElapsed, SystemClock.elapsedRealtime())) {
                execute(PlaybackCommand.Stop)
            } else scheduleTimer()
        }, SleepDeadline.remaining(deadline, SystemClock.elapsedRealtime()))
    }

    private fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val focusToken = ++focusGeneration
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                if (focusToken == focusGeneration && focusRequest != null && !destroyed && !closing)
                    onFocusChanged(change)
            }, callbacks).build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = request
        return granted
    }
    private fun onFocusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                if (snapshot.isPlaying) execute(PlaybackCommand.Pause)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (snapshot.isPlaying) {
                focusDucked = true
                player?.volume = DUCK_VOLUME
                // TTS has no per-utterance volume control once synthesis has started.
                // Keep this exact chunk pending instead of mixing it over the screen reader.
                if (activeUtterance != null) {
                    activeUtterance = null
                    ttsWaitingForFocus = true
                    tts?.stop()
                    publish(snapshot.copy(outputSource = "系统声音 · 等待提示结束"))
                    updateNotification()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusDucked = false
                player?.volume = 1f
                if (ttsWaitingForFocus && accepts(ttsToken)) {
                    ttsWaitingForFocus = false
                    startSystemVoice(ttsToken)
                }
            }
        }
    }
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:playback")
            .apply { acquire(2 * 60 * 60 * 1000L) }
    }
    private fun releaseResources() {
        val previous = focusRequest
        focusRequest = null
        ++focusGeneration
        focusDucked = false
        ttsWaitingForFocus = false
        previous?.let(audioManager::abandonAudioFocusRequest)
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
    }
    private fun finishService() {
        closing = true
        PlaybackController.detach(this)
        releaseResources()
        timerHandler.removeCallbacksAndMessages(null)
        callbacks.removeCallbacksAndMessages(null)
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun actionIntent(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, TtsPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_speech)
        .setContentTitle(snapshot.documentTitle.ifBlank { "随身听" })
        .setContentText(snapshot.outputSource + " · " + snapshot.sentences.getOrNull(snapshot.currentIndex).orEmpty().take(48))
        .setContentIntent(openIntent()).setOnlyAlertOnce(true).setOngoing(snapshot.isPlaying)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .addAction(android.R.drawable.ic_media_previous, "上一句", actionIntent(ACTION_PREVIOUS, 1))
        .addAction(if (snapshot.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (snapshot.isPlaying) "暂停" else "从句首继续", actionIntent(if (snapshot.isPlaying) ACTION_PAUSE else ACTION_PLAY, 2))
        .addAction(android.R.drawable.ic_media_next, "下一句", actionIntent(ACTION_NEXT, 3))
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", actionIntent(ACTION_STOP, 4)).build()

    private fun updateNotification() {
        if (destroyed) return
        mediaSession.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(if (snapshot.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (snapshot.isPlaying) snapshot.effectiveRate else 0f).build())
        mediaSession.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.documentTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.outputSource).build())
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }
    override fun onDestroy() {
        destroyed = true
        PlaybackController.detach(this)
        invalidateOutput()
        timerHandler.removeCallbacksAndMessages(null)
        tts?.shutdown(); tts = null
        callbacks.removeCallbacksAndMessages(null)
        releaseResources()
        scope.cancel()
        mediaSession.release()
        unregisterReceiver(noisy)
        audioManager.unregisterAudioDeviceCallback(devices)
        if (!closing && snapshot.isPlaying) publish(PlaybackSession.reduce(snapshot, PlaybackCommand.Pause))
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.museum.reader.action.PLAY"
        const val ACTION_PAUSE = "com.museum.reader.action.PAUSE"
        const val ACTION_STOP = "com.museum.reader.action.STOP"
        const val ACTION_PREVIOUS = "com.museum.reader.action.PREVIOUS"
        const val ACTION_NEXT = "com.museum.reader.action.NEXT"
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 1001
        private const val PAUSED_TIMEOUT = 10 * 60_000L
        private const val DUCK_VOLUME = 0.2f
        fun dispatch(context: Context, action: String) = PlaybackController.send(context, when (action) {
            ACTION_PLAY -> PlaybackCommand.Play
            ACTION_PAUSE -> PlaybackCommand.Pause
            ACTION_PREVIOUS -> PlaybackCommand.Move(-1)
            ACTION_NEXT -> PlaybackCommand.Move(1)
            else -> PlaybackCommand.Stop
        })
    }

    internal fun diagnostics() = PlaybackDiagnostics(
        player?.isPlaying == true, player?.playbackParameters?.speed,
        tts != null, foreground, wakeLock?.isHeld == true)
}

internal data class PlaybackDiagnostics(
    val filePlaying: Boolean, val fileRate: Float?, val ttsCreated: Boolean,
    val foreground: Boolean, val wakeLockHeld: Boolean,
)
