package com.museum.reader.playback

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.DocumentRepository
import com.museum.reader.data.writeTestWav
import com.museum.reader.domain.PlaybackCommand
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun command(value: PlaybackCommand) = main { PlaybackController.send(context, value) }
    private fun waitUntil(check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < until) {
            var ready = false
            main { ready = check() }
            if (ready) return
            Thread.sleep(30)
        }
        fail("Timed out: " + PlaybackBus.snapshot.value.status + " / " + PlaybackBus.snapshot.value.outputSource)
    }
    private fun queue(file: File, kind: AudioKind, cloud: Boolean = true): PlaybackSnapshot {
        val text = "这是音源切换测试。"
        val audio = SentenceAudio(UUID.randomUUID().toString(), "playback-test", "s1", kind,
            "XUNFEI", "voice", file.path, DocumentRepository.textHash(text), 1000)
        return PlaybackSnapshot(documentId = "playback-test", contentVersion = "v1", documentTitle = "播放测试",
            sentences = listOf(text), sentenceIds = listOf("s1"), paragraphIndices = listOf(0),
            audioCandidates = mapOf("s1" to listOf(audio)), speechRate = 1.2f,
            loopMode = LoopMode.ALL, cloudEnabled = cloud, preferredCloudProvider = "XUNFEI",
            preferredCloudVoice = "voice", globalSettings = ReaderSettings(sentencePauseMs = 0))
    }

    @Test fun recordingAndCloudPlayWithoutCreatingTtsAndApplyRealPlayerSpeed() {
        val file = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID() + "/voice.wav")
        writeTestWav(file)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                command(PlaybackCommand.Clear)
                for (kind in listOf(AudioKind.RECORDING, AudioKind.CLOUD)) {
                    command(PlaybackCommand.Load(queue(file, kind), true))
                    waitUntil { PlaybackController.diagnostics()?.filePlaying == true }
                    main {
                        val d = PlaybackController.diagnostics()!!
                        assertFalse(d.ttsCreated)
                        assertTrue(d.foreground)
                        assertTrue(d.wakeLockHeld)
                        assertEquals(1.2f, d.fileRate!!, 0.001f)
                        assertTrue(PlaybackBus.snapshot.value.outputSource.contains(
                            if (kind == AudioKind.RECORDING) "本人录音" else "云缓存"))
                    }
                    command(PlaybackCommand.Pause)
                    main {
                        assertFalse(PlaybackController.diagnostics()!!.filePlaying)
                        assertFalse(PlaybackController.diagnostics()!!.wakeLockHeld)
                    }
                }
                command(PlaybackCommand.Stop)
                waitUntil { PlaybackController.diagnostics() == null }
            } finally {
                command(PlaybackCommand.Clear)
                file.delete(); file.parentFile?.delete()
            }
        }
    }

    @Test fun corruptRecordingFallsBackToCloudAndTimerSurvivesSeekAndPause() {
        val dir = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID()).apply { mkdirs() }
        val good = File(dir, "good.wav")
        val bad = File(dir, "bad.m4a").apply { writeText("invalid-media") }
        writeTestWav(good)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                val q = queue(good, AudioKind.CLOUD)
                val broken = q.audioCandidates.getValue("s1").single().copy(id = "broken",
                    kind = AudioKind.RECORDING, path = bad.path)
                command(PlaybackCommand.Load(q.copy(audioCandidates = mapOf("s1" to listOf(broken) + q.audioCandidates.getValue("s1"))), true))
                waitUntil { PlaybackController.diagnostics()?.filePlaying == true }
                main { assertTrue(PlaybackBus.snapshot.value.outputSource.contains("云缓存")) }
                command(PlaybackCommand.Timer(SystemClock.elapsedRealtime() + 1500))
                command(PlaybackCommand.Seek(0))
                command(PlaybackCommand.Pause)
                command(PlaybackCommand.Play)
                waitUntil { !PlaybackBus.snapshot.value.isPlaying && PlaybackBus.snapshot.value.timerDeadlineElapsed == null }
                waitUntil { PlaybackController.diagnostics() == null }
            } finally {
                command(PlaybackCommand.Clear)
                good.delete(); bad.delete(); dir.delete()
            }
        }
    }

    @Test fun recordingGateBlocksNotificationPlayAndAllowsExplicitPlayAfterCapture() {
        val file = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID() + "/voice.wav")
        writeTestWav(file)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                command(PlaybackCommand.Clear)
                command(PlaybackCommand.Load(queue(file, AudioKind.RECORDING)))
                main {
                    PlaybackController.setCapturing(context, true)
                    context.startService(android.content.Intent(context, TtsPlaybackService::class.java)
                        .setAction(TtsPlaybackService.ACTION_PLAY))
                }
                Thread.sleep(300)
                waitUntil { PlaybackController.diagnostics() == null }
                main {
                    assertFalse(PlaybackBus.snapshot.value.isPlaying)
                    assertTrue(PlaybackBus.snapshot.value.error.orEmpty().contains("录音"))
                    PlaybackController.setCapturing(context, false)
                }
                command(PlaybackCommand.Play)
                waitUntil { PlaybackController.diagnostics()?.filePlaying == true }
            } finally {
                main { PlaybackController.setCapturing(context, false) }
                command(PlaybackCommand.Clear)
                file.delete(); file.parentFile?.delete()
            }
        }
    }

    @Test fun pendingPlayThenStopDoesNotStartAudioOrLeaveService() {
        val file = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID() + "/voice.wav")
        writeTestWav(file)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                main {
                    PlaybackController.send(context, PlaybackCommand.Load(queue(file, AudioKind.RECORDING), true))
                    PlaybackController.send(context, PlaybackCommand.Stop)
                }
                Thread.sleep(500)
                waitUntil { PlaybackController.diagnostics() == null }
                main { assertFalse(PlaybackBus.snapshot.value.isPlaying) }
            } finally {
                command(PlaybackCommand.Clear)
                file.delete(); file.parentFile?.delete()
            }
        }
    }

    @Test fun pausingAndStoppingDuringPreparationClearBusyTextAndIgnoreCancelledOutput() {
        val file = File(context.filesDir, "sentence-audio/test-" + UUID.randomUUID() + "/voice.wav")
        writeTestWav(file)
        val pendingLookups = mutableListOf<CountDownLatch>()
        fun heldQueue(): Triple<PlaybackSnapshot, CountDownLatch, CountDownLatch> {
            val original = queue(file, AudioKind.RECORDING)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1).also { pendingLookups += it }
            val candidates = object : Map<String, List<SentenceAudio>> by original.audioCandidates {
                override fun get(key: String): List<SentenceAudio>? {
                    // StateFlow compares map values on main; only hold the service's IO lookup.
                    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                        entered.countDown()
                        check(release.await(8, TimeUnit.SECONDS)) { "Test candidate lookup was not released" }
                    }
                    return original.audioCandidates[key]
                }
            }
            return Triple(original.copy(audioCandidates = candidates), entered, release)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                command(PlaybackCommand.Load(queue(file, AudioKind.RECORDING), true))
                waitUntil { PlaybackController.diagnostics()?.filePlaying == true }
                // Hold the real service's test-data lookup; fast IO can otherwise finish inline.
                val (pauseQueue, pauseEntered, pauseRelease) = heldQueue()
                command(PlaybackCommand.Load(pauseQueue, true))
                assertTrue("Service did not enter candidate lookup", pauseEntered.await(5, TimeUnit.SECONDS))
                main {
                    assertEquals("正在准备声音", PlaybackBus.snapshot.value.outputSource)
                    PlaybackController.send(context, PlaybackCommand.Pause)
                    assertEquals("已暂停", PlaybackBus.snapshot.value.outputSource)
                }
                pauseRelease.countDown()
                Thread.sleep(300)
                main {
                    assertFalse(PlaybackBus.snapshot.value.isPlaying)
                    assertEquals("已暂停", PlaybackBus.snapshot.value.outputSource)
                    assertFalse(PlaybackController.diagnostics()!!.filePlaying)
                    assertFalse(PlaybackController.diagnostics()!!.wakeLockHeld)
                }
                command(PlaybackCommand.Play)
                waitUntil { PlaybackController.diagnostics()?.filePlaying == true }
                main { assertEquals("本人录音", PlaybackBus.snapshot.value.outputSource) }
                val (stopQueue, stopEntered, stopRelease) = heldQueue()
                command(PlaybackCommand.Load(stopQueue, true))
                assertTrue("Service did not enter candidate lookup", stopEntered.await(5, TimeUnit.SECONDS))
                main {
                    assertEquals("正在准备声音", PlaybackBus.snapshot.value.outputSource)
                    PlaybackController.send(context, PlaybackCommand.Stop)
                }
                stopRelease.countDown()
                waitUntil { PlaybackController.diagnostics() == null }
                Thread.sleep(300)
                main {
                    assertEquals(PlaybackStatus.PAUSED, PlaybackBus.snapshot.value.status)
                    assertEquals("已停止", PlaybackBus.snapshot.value.outputSource)
                    assertNull(PlaybackBus.snapshot.value.appliedRate)
                }
                command(PlaybackCommand.Move(1))
                main { assertEquals("已暂停", PlaybackBus.snapshot.value.outputSource) }
            } finally {
                pendingLookups.forEach { it.countDown() }
                command(PlaybackCommand.Clear)
                file.delete(); file.parentFile?.delete()
            }
        }
    }
}
