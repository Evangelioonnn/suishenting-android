package com.museum.reader.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

internal fun writeTestWav(file: File) {
    file.parentFile!!.mkdirs()
    val samples = 16_000
    val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
    buffer.putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
    buffer.put("data".toByteArray()).putInt(samples * 2)
    repeat(samples) { buffer.putShort((sin(2 * PI * 440 * it / 16_000) * 8_000).toInt().toShort()) }
    file.writeBytes(buffer.array())
}
