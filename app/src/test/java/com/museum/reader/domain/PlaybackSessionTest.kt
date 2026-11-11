package com.museum.reader.domain

import com.museum.reader.model.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionTest {
    private fun queue(id: String = "a") = PlaybackSnapshot(documentId = id,
        documentTitle = id, contentVersion = "v1", sentences = listOf("甲。", "乙。", "丙。"),
        sentenceIds = listOf("s1", "s2", "s3"), currentIndex = 1)

    @Test fun pausedSeekStaysPausedPlayingSeekContinuesAndOutsideRangeExits() {
        var state = PlaybackSession.reduce(queue(), PlaybackCommand.Seek(2))
        assertFalse(state.isPlaying)
        state = PlaybackSession.reduce(state, PlaybackCommand.Play)
        state = PlaybackSession.reduce(state, PlaybackCommand.Mode(LoopMode.RANGE, 1, 2))
        assertEquals(2, PlaybackSession.reduce(state, PlaybackCommand.Move(1)).currentIndex)
        state = PlaybackSession.reduce(state, PlaybackCommand.Seek(0))
        assertTrue(state.isPlaying)
        assertEquals(LoopMode.NONE, state.loopMode)
    }

    @Test fun completedRestartsButStoppedResumesAtCurrentSentence() {
        val completed = queue().copy(status = PlaybackStatus.COMPLETED, currentIndex = 2)
        assertEquals(0, PlaybackSession.reduce(completed, PlaybackCommand.Play).currentIndex)
        val stopped = PlaybackSession.reduce(completed, PlaybackCommand.Stop)
        assertEquals(2, PlaybackSession.reduce(stopped, PlaybackCommand.Play).currentIndex)
    }

    @Test fun editingAnotherDocumentAndMetadataRefreshCannotResetPlayback() {
        val original = queue().copy(status = PlaybackStatus.PLAYING, loopMode = LoopMode.ALL)
        assertSame(original, PlaybackSession.reduce(original, PlaybackCommand.Refresh(queue("b"))))
        val renamed = PlaybackSession.reduce(original, PlaybackCommand.Refresh(queue().copy(documentTitle = "新标题")))
        assertTrue(renamed.isPlaying)
        assertEquals(LoopMode.ALL, renamed.loopMode)
        assertEquals(1, renamed.currentIndex)
        assertEquals("新标题", renamed.documentTitle)
    }

    @Test fun changedTextStopsAndRetainedSentenceIdentityRemapsPosition() {
        val original = queue().copy(status = PlaybackStatus.PLAYING, loopMode = LoopMode.RANGE)
        val changed = queue().copy(contentVersion = "v2", sentenceIds = listOf("s2", "s1", "s3"))
        val state = PlaybackSession.reduce(original, PlaybackCommand.Refresh(changed))
        assertFalse(state.isPlaying)
        assertEquals(0, state.currentIndex)
        assertEquals(LoopMode.NONE, state.loopMode)
    }

    @Test fun rapidCommandsObeyLastActionAndGenerationRejectsOldOutput() {
        var state = queue()
        repeat(100) {
            state = PlaybackSession.reduce(state, PlaybackCommand.Play)
            state = PlaybackSession.reduce(state, PlaybackCommand.Seek(2))
            state = PlaybackSession.reduce(state, PlaybackCommand.Pause)
            state = PlaybackSession.reduce(state, PlaybackCommand.Move(-1))
        }
        assertFalse(state.isPlaying)
        assertEquals(1, state.currentIndex)
        val generation = OutputGeneration()
        val old = generation.next()
        assertTrue(generation.accepts(old))
        val latest = generation.next()
        assertFalse(generation.accepts(old))
        assertTrue(generation.accepts(latest))
    }

    @Test fun defaultsDoNotOverrideDocumentRateAndAllSourcesUseEffectiveRate() {
        val playing = queue().copy(speechRate = 0.8f, status = PlaybackStatus.PLAYING, appliedRate = 0.8f)
        val updated = PlaybackSession.reduce(playing,
            PlaybackCommand.Defaults(ReaderSettings(speechRate = 1.2f), false, "", ""))
        assertEquals(0.8f, updated.effectiveRate, 0.001f)
        val global = PlaybackSession.reduce(updated, PlaybackCommand.Refresh(queue().copy(speechRate = null)))
        assertEquals(0.8f, global.effectiveRate, 0.001f) // current output remains unchanged
        assertEquals(1.2f, global.requestedRate, 0.001f)
    }

    @Test fun sleepTimerUsesMonotonicTimeAndSurvivesSentenceControls() {
        for (minutes in listOf(15, 30, 60)) {
            val deadline = SleepDeadline.create(1200, minutes)
            var state = PlaybackSession.reduce(queue(), PlaybackCommand.Timer(deadline))
            state = PlaybackSession.reduce(state, PlaybackCommand.Seek(2))
            state = PlaybackSession.reduce(state, PlaybackCommand.Pause)
            assertEquals(deadline, state.timerDeadlineElapsed)
            assertFalse(SleepDeadline.expired(deadline, deadline - 1))
            assertTrue(SleepDeadline.expired(deadline, deadline))
            assertEquals(0, SleepDeadline.remaining(deadline, deadline + 3000))
        }
    }

    @Test fun cancelledOutputAndNewPositionsDoNotRetainPreparingOrPreviousSource() {
        val preparing = queue().copy(status = PlaybackStatus.PLAYING,
            outputSource = "正在准备声音", appliedRate = 1.2f)
        val paused = PlaybackSession.reduce(preparing, PlaybackCommand.Pause)
        assertEquals("已暂停", paused.outputSource)
        assertNull(paused.appliedRate)
        assertEquals("已停止", PlaybackSession.reduce(preparing, PlaybackCommand.Stop).outputSource)
        val previousSource = paused.copy(outputSource = "本人录音")
        assertEquals("已暂停", PlaybackSession.reduce(previousSource, PlaybackCommand.Seek(2)).outputSource)
        assertEquals("已暂停", PlaybackSession.reduce(previousSource, PlaybackCommand.Move(1)).outputSource)
        assertEquals("已暂停", PlaybackSession.reduce(preparing,
            PlaybackCommand.Refresh(queue().copy(contentVersion = "v2"))).outputSource)
    }

    @Test fun movingAfterNaturalEndSelectsSentenceForNextExplicitPlay() {
        val completed = queue().copy(status = PlaybackStatus.COMPLETED, currentIndex = 2,
            outputSource = "全文已结束")
        val moved = PlaybackSession.reduce(completed, PlaybackCommand.Move(-1))
        assertEquals(PlaybackStatus.PAUSED, moved.status)
        assertEquals("已暂停", moved.outputSource)
        assertEquals(1, PlaybackSession.reduce(moved, PlaybackCommand.Play).currentIndex)
    }
}
