package com.museum.reader.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object M4aTrimmer {
    fun trimTapPadding(input: File, output: File, leadingMs: Long = 220, trailingMs: Long = 180): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return runCatching {
            extractor.setDataSource(input.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("录音中没有音频轨道")
            val format = extractor.getTrackFormat(trackIndex)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val startUs = leadingMs * 1_000L
            val endUs = (durationUs - trailingMs * 1_000L).coerceAtLeast(startUs + 100_000L)
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else 1024 * 1024
            val buffer = ByteBuffer.allocateDirect(capacity)
            val info = MediaCodec.BufferInfo()
            var firstSampleUs = -1L
            var wroteSample = false
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                val sampleTime = extractor.sampleTime
                if (size < 0 || sampleTime < 0 || sampleTime > endUs) break
                if (extractor.sampleTrackIndex == trackIndex) {
                    if (firstSampleUs < 0) firstSampleUs = sampleTime
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else 0
                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "不支持加密录音" }
                    info.set(0, size, (sampleTime - firstSampleUs).coerceAtLeast(0L), flags)
                    muxer.writeSampleData(outputTrack, buffer, info)
                    wroteSample = true
                }
                extractor.advance()
            }
            muxer.stop()
            muxer.release()
            muxer = null
            wroteSample && output.length() > 256
        }.getOrDefault(false).also {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
