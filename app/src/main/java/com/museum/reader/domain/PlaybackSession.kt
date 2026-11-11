package com.museum.reader.domain

import com.museum.reader.model.*

sealed interface PlaybackCommand {
    data class Load(val queue: PlaybackSnapshot, val play: Boolean = false) : PlaybackCommand
    data object Play : PlaybackCommand
    data object Pause : PlaybackCommand
    data object Stop : PlaybackCommand
    data class Seek(val index: Int, val play: Boolean? = null) : PlaybackCommand
    data class Move(val delta: Int) : PlaybackCommand
    data class Mode(val mode: LoopMode, val start: Int, val end: Int) : PlaybackCommand
    data class Refresh(val queue: PlaybackSnapshot) : PlaybackCommand
    data class Defaults(val settings: ReaderSettings, val cloudEnabled: Boolean, val provider: String, val voice: String) : PlaybackCommand
    data class Timer(val deadlineElapsed: Long?) : PlaybackCommand
    data object Clear : PlaybackCommand
}

/** Shared transitions for foreground, notification and reader controls. */
object PlaybackSession {
    fun reduce(state: PlaybackSnapshot, command: PlaybackCommand): PlaybackSnapshot = when (command) {
        is PlaybackCommand.Load -> command.queue.copy(
            status = if (command.play) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
            currentIndex = command.queue.currentIndex.coerceIn(0, command.queue.sentences.lastIndex.coerceAtLeast(0)),
            timerDeadlineElapsed = state.timerDeadlineElapsed, error = null,
            outputSource = if (command.play) "正在准备声音" else "待播放", appliedRate = null)
        PlaybackCommand.Play -> if (state.sentences.isEmpty()) state else state.copy(
            status = PlaybackStatus.PLAYING,
            currentIndex = if (state.status == PlaybackStatus.COMPLETED) 0 else state.currentIndex, error = null,
            outputSource = "正在准备声音", appliedRate = null)
        PlaybackCommand.Pause -> state.copy(status = PlaybackStatus.PAUSED, outputSource = "已暂停", appliedRate = null)
        PlaybackCommand.Stop -> state.copy(status = PlaybackStatus.PAUSED, timerDeadlineElapsed = null,
            outputSource = "已停止", appliedRate = null)
        is PlaybackCommand.Seek -> {
            val index = command.index.coerceIn(0, state.sentences.lastIndex.coerceAtLeast(0))
            val outside = state.loopMode == LoopMode.RANGE &&
                index !in PlaybackRules.normalizeRange(state.rangeStart, state.rangeEnd, state.sentences.size)
            val play = command.play ?: state.isPlaying
            state.copy(currentIndex = index,
                status = if (play) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                loopMode = if (outside) LoopMode.NONE else state.loopMode, error = null,
                outputSource = if (play) "正在准备声音" else "已暂停", appliedRate = null)
        }
        is PlaybackCommand.Move -> {
            val range = if (state.loopMode == LoopMode.RANGE)
                PlaybackRules.normalizeRange(state.rangeStart, state.rangeEnd, state.sentences.size)
                else 0..state.sentences.lastIndex.coerceAtLeast(0)
            state.copy(currentIndex = (state.currentIndex + command.delta).coerceIn(range.first, range.last), error = null,
                status = if (state.isPlaying) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                outputSource = if (state.isPlaying) "正在准备声音" else "已暂停", appliedRate = null)
        }
        is PlaybackCommand.Mode -> {
            val range = PlaybackRules.normalizeRange(command.start, command.end, state.sentences.size)
            state.copy(loopMode = command.mode, rangeStart = range.first.coerceAtLeast(0),
                rangeEnd = range.last.coerceAtLeast(0))
        }
        is PlaybackCommand.Refresh -> if (state.documentId != command.queue.documentId) state else {
            val q = command.queue
            val changed = state.contentVersion != q.contentVersion
            val stableId = state.sentenceIds.getOrNull(state.currentIndex)
            val index = q.sentenceIds.indexOf(stableId).takeIf { it >= 0 }
                ?: q.currentIndex.coerceIn(0, q.sentences.lastIndex.coerceAtLeast(0))
            state.copy(documentTitle = q.documentTitle, sentences = q.sentences,
                paragraphIndices = q.paragraphIndices, sentenceIds = q.sentenceIds,
                contentVersion = q.contentVersion, audioCandidates = q.audioCandidates,
                speechRate = q.speechRate, sentencePauseMs = q.sentencePauseMs,
                paragraphPauseMs = q.paragraphPauseMs, currentIndex = index,
                status = if (changed) PlaybackStatus.PAUSED else state.status,
                loopMode = if (changed && state.loopMode == LoopMode.RANGE) LoopMode.NONE else state.loopMode,
                outputSource = if (changed) "已暂停" else state.outputSource,
                appliedRate = if (changed) null else state.appliedRate)
        }
        is PlaybackCommand.Defaults -> state.copy(globalSettings = command.settings, cloudEnabled = command.cloudEnabled,
            preferredCloudProvider = command.provider, preferredCloudVoice = command.voice)
        is PlaybackCommand.Timer -> state.copy(timerDeadlineElapsed = command.deadlineElapsed)
        PlaybackCommand.Clear -> PlaybackSnapshot(globalSettings = state.globalSettings)
    }
}

/** Invalidate callbacks even when the same sentence is replayed. */
class OutputGeneration {
    private var value = 0L
    fun next(): Long = ++value
    fun accepts(token: Long): Boolean = token == value
}

object SleepDeadline {
    fun create(now: Long, minutes: Int): Long = now + minutes.coerceIn(1, 120) * 60_000L
    fun remaining(deadline: Long?, now: Long): Long = deadline?.let { (it - now).coerceAtLeast(0L) } ?: 0L
    fun expired(deadline: Long?, now: Long): Boolean = deadline != null && now >= deadline
}
