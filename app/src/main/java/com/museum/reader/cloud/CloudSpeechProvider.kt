package com.museum.reader.cloud

data class SynthesizedAudio(
    val bytes: ByteArray,
    val extension: String,
)

interface CloudSpeechProvider {
    val provider: CloudProvider
    suspend fun synthesize(text: String, voiceId: String): SynthesizedAudio
}
