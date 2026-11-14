package com.museum.reader.playback

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.domain.PlaybackCommand
import com.museum.reader.model.PlaybackSnapshot
import com.museum.reader.model.PlaybackStatus
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Inject only the actual platform start boundary; retain all production command handling. */
class PlaybackStartRetryRegressionTest {
    @Test fun aRejectedForegroundStartDoesNotBlockTheNextExplicitPlayRequest() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val real = inst.targetContext
        var attempts = 0
        val denied = object : ContextWrapper(real) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? {
                attempts++
                throw IllegalStateException("Synthetic platform rejection; no service was started")
            }
        }
        inst.runOnMainSync { PlaybackController.send(real, PlaybackCommand.Clear) }
        val end = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < end) {
            var stopped = false
            inst.runOnMainSync { stopped = PlaybackController.diagnostics() == null }
            if (stopped) break
            Thread.sleep(20)
        }
        try {
            inst.runOnMainSync {
                assertNull(PlaybackController.diagnostics())
                PlaybackController.send(denied, PlaybackCommand.Load(PlaybackSnapshot(
                    documentId = "start-retry-${UUID.randomUUID()}", documentTitle = "启动失败重试",
                    sentences = listOf("下一次明确播放必须可以重试。"), sentenceIds = listOf("retry-sentence"),
                    paragraphIndices = listOf(0))))
                PlaybackController.send(denied, PlaybackCommand.Play)
                assertEquals(1, attempts)
                assertEquals(PlaybackStatus.ERROR, PlaybackBus.snapshot.value.status)
                assertFalse(PlaybackBus.snapshot.value.isPlaying)
                PlaybackController.send(denied, PlaybackCommand.Play)
                assertEquals("The second user request must reach the platform again", 2, attempts)
                assertEquals(PlaybackStatus.ERROR, PlaybackBus.snapshot.value.status)
                assertFalse(PlaybackBus.snapshot.value.isPlaying)
            }
        } finally { inst.runOnMainSync { PlaybackController.send(real, PlaybackCommand.Clear) } }
    }
}
