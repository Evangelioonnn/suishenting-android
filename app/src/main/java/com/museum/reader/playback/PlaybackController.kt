package com.museum.reader.playback

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.museum.reader.data.DocumentRepository
import com.museum.reader.domain.PlaybackCommand
import com.museum.reader.domain.PlaybackSession
import com.museum.reader.domain.SleepDeadline
import com.museum.reader.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Main-thread command gateway. Queues stay in process; large documents never enter Binder extras. */
object PlaybackController {
    private val main = Handler(Looper.getMainLooper())
    private var service: TtsPlaybackService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val positions = Channel<Triple<Context, String, Int>>(Channel.UNLIMITED)
    private var revision = 0L
    internal var capturing = false
        private set
    fun setCapturing(context: Context, value: Boolean) {
        if (value) send(context, PlaybackCommand.Pause)
        capturing = value
    }
    internal var maintenance = false
        private set
    fun setMaintenance(context: Context, value: Boolean) {
        if (value) send(context, PlaybackCommand.Stop)
        maintenance = value
    }
    private var pendingStart: Long? = null
    internal fun consumeStart(): Boolean {
        if (pendingStart == null) return false
        pendingStart = null
        return true
    }
    init {
        scope.launch {
            for ((context, id, index) in positions) runCatching { DocumentRepository(context).savePosition(id, index) }
        }
    }
    internal fun attach(value: TtsPlaybackService) { service = value }
    internal fun detach(value: TtsPlaybackService) { if (service === value) service = null }
    internal fun diagnostics(): PlaybackDiagnostics? = service?.diagnostics()

    fun send(context: Context, command: PlaybackCommand) {
        val app = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { send(app, command) }
            return
        }
        if ((maintenance || capturing) && (command == PlaybackCommand.Play || command is PlaybackCommand.Load && command.play)) {
            publish(app, PlaybackBus.snapshot.value.copy(status = PlaybackStatus.PAUSED, error = if (capturing) "正在录音，请先结束采集" else "正在整理音频，请稍后播放"))
            return
        }
        revision++
        val wasPending = pendingStart != null
        val base = PlaybackBus.snapshot.value.let { if (wasPending) it.copy(status = PlaybackStatus.PLAYING) else it }
        pendingStart = null
        service?.let { it.execute(command); return }
        var next = PlaybackSession.reduce(base, command)
        if (SleepDeadline.expired(next.timerDeadlineElapsed, SystemClock.elapsedRealtime()))
            next = next.copy(status = if (command == PlaybackCommand.Play) next.status else PlaybackStatus.PAUSED, timerDeadlineElapsed = null)
        val shouldPlay = next.isPlaying
        // Never publish playing before the service has obtained focus and started output.
        publish(app, if (shouldPlay) next.copy(status = PlaybackStatus.PAUSED) else next)
        if (shouldPlay) {
            pendingStart = revision
            if (wasPending) return
            try {
                ContextCompat.startForegroundService(app,
                    Intent(app, TtsPlaybackService::class.java).setAction(TtsPlaybackService.ACTION_PLAY)
                        .putExtra("request", revision))
            } catch (_: RuntimeException) {
                publish(app, next.copy(status = PlaybackStatus.ERROR, error = "无法启动后台播放，请返回应用后重试"))
            }
        }
    }

    internal fun publish(context: Context, value: PlaybackSnapshot) {
        PlaybackBus.publish(value)
        val id = value.documentId
        val p = context.getSharedPreferences("playback_session_v2", Context.MODE_PRIVATE)
        val editor = p.edit()
        if (id == null) editor.clear() else {
            editor.putString("document", id)
                .putString("sentence", value.sentenceIds.getOrNull(value.currentIndex))
                .putInt("index", value.currentIndex).putString("mode", value.loopMode.name)
                .putInt("start", value.rangeStart).putInt("end", value.rangeEnd)
                .putString("content", value.contentVersion)
                .putLong("deadline", value.timerDeadlineElapsed ?: 0L)
                .putInt("boot", Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
            positions.trySend(Triple(context.applicationContext, id, value.currentIndex))
        }
        // Small state is committed before showing the updated position; no document/audio data here.
        editor.apply()
    }

    /** Startup restores context only, and must never start a service or produce sound. */
    suspend fun restore(context: Context, build: suspend (String) -> PlaybackSnapshot?) {
        val observed = revision
        val p = context.getSharedPreferences("playback_session_v2", Context.MODE_PRIVATE)
        val id = p.getString("document", null) ?: return
        val queue = build(id) ?: return
        withContext(Dispatchers.Main.immediate) {
            if (revision != observed || PlaybackBus.snapshot.value.documentId != null) return@withContext
            val stable = p.getString("sentence", null)
            val index = queue.sentenceIds.indexOf(stable).takeIf { it >= 0 }
                ?: p.getInt("index", queue.currentIndex).coerceIn(0, queue.sentences.lastIndex.coerceAtLeast(0))
            val mode = runCatching { LoopMode.valueOf(p.getString("mode", "NONE")!!) }.getOrDefault(LoopMode.NONE)
            val sameContent = p.getString("content", "") == queue.contentVersion
            val sameBoot = p.getInt("boot", -1) == Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -2)
            val deadline = p.getLong("deadline", 0).takeIf { sameBoot && it > SystemClock.elapsedRealtime() }
            publish(context, queue.copy(currentIndex = index, status = PlaybackStatus.PAUSED,
                loopMode = if (sameContent) mode else LoopMode.NONE,
                rangeStart = p.getInt("start", 0).coerceIn(0, queue.sentences.lastIndex.coerceAtLeast(0)),
                rangeEnd = p.getInt("end", 0).coerceIn(0, queue.sentences.lastIndex.coerceAtLeast(0)),
                timerDeadlineElapsed = deadline))
        }
    }
}
