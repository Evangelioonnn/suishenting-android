package com.museum.reader.cloud

import android.content.Context
import android.media.MediaMetadataRetriever
import com.museum.reader.data.DocumentRepository
import com.museum.reader.model.AudioKind
import com.museum.reader.model.SentenceAudio
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import com.museum.reader.data.AudioFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CloudGenerationResult(
    val audio: SentenceAudio,
    val provider: CloudProvider,
    val fromCache: Boolean,
)

class CloudSpeechManager(
    private val context: Context,
    private val repository: DocumentRepository,
    private val credentialStore: SecureCredentialStore,
    private val preferenceRepository: CloudSpeechPreferencesRepository,
    private val providerFactory: ((CloudProvider, CloudCredentials) -> CloudSpeechProvider?)? = null,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val workMutex = Mutex()
    private val engines = mutableMapOf<CloudProvider, Pair<CloudCredentials, CloudSpeechProvider>>()

    suspend fun synthesizeSentence(documentId: String, sentenceId: String, text: String, expectedContentVersion: String? = null): CloudGenerationResult =
        workMutex.withLock {
            currentCoroutineContext().ensureActive()
            val preferences = preferenceRepository.load()
            require(preferences.enabled) { "请先开启联网自然声音" }
            val hash = DocumentRepository.textHash(text)
            require(repository.isCurrentSentence(documentId, sentenceId, hash, expectedContentVersion)) { "正文已变化，请重新打开后生成" }
            val order = listOf(preferences.primaryProvider, preferences.primaryProvider.other())
            // Reuse either provider's completed cache before making another paid request.
            for (provider in order) {
                val cached = repository.matchingAudio(sentenceId, AudioKind.CLOUD, provider.name, preferences.voiceFor(provider))
                if (cached != null && cached.textHash == hash && runCatching {
                    AudioFiles.inspect(AudioFiles.requireOwned(context, File(cached.path))) != null
                }.getOrDefault(false)) return@withLock CloudGenerationResult(cached, provider, true)
            }
            val credentials = credentialStore.load()
            credentialStore.lastReadError?.let { error(it) }
            val errors = mutableListOf<String>()
            val failures = mutableListOf<CloudProviderFailure>()
            for (provider in order) {
                currentCoroutineContext().ensureActive()
                require(repository.isCurrentSentence(documentId, sentenceId, hash, expectedContentVersion)) { "正文已变化，已停止后续生成" }
                val engine = engine(provider, credentials)
                if (engine == null) {
                    errors += "${provider.label}尚未配置"
                    failures += CloudProviderFailure(provider, IllegalStateException("请先保存${provider.label}的密钥"))
                    continue
                }
                // A local limit is checked before every provider attempt; it is not the provider bill.
                ensureWithinLimit(text.length, preferences.dailyCharacterLimit)
                try {
                    val synthesized = engine.synthesize(text, preferences.voiceFor(provider))
                    currentCoroutineContext().ensureActive()
                    preferenceRepository.addUsage(text.length)
                    val file = writeAudio(documentId, sentenceId, provider, preferences.voiceFor(provider), text, synthesized)
                    val info = AudioFiles.inspect(file) ?: error("服务返回的音频不可播放，请检查音色配置")
                    currentCoroutineContext().ensureActive()
                    val audio = SentenceAudio(UUID.randomUUID().toString(), documentId, sentenceId, AudioKind.CLOUD,
                        provider.name, preferences.voiceFor(provider), file.absolutePath, hash, info.durationMs)
                    if (!repository.putAudioIfCurrent(audio, expectedContentVersion)) {
                        // Keep completed output as archived evidence, never current output for changed text.
                        repository.putAudio(audio.copy(archivedAt = System.currentTimeMillis()))
                        error("正文已变化，已完成的旧声音保留为旧音频，请重新生成")
                    }
                    return@withLock CloudGenerationResult(audio, provider, false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    errors += "${provider.label}：${error.message ?: "请求失败"}"
                    failures += CloudProviderFailure(provider, error)
                }
            }
            throw CloudSynthesisException(errors.joinToString("；").ifBlank { "没有可用的联网语音服务" }, failures)
        }

    suspend fun synthesizePreview(provider: CloudProvider, voiceId: String, text: String): File = workMutex.withLock {
        currentCoroutineContext().ensureActive()
        val preferences = preferenceRepository.load()
        val credentials = credentialStore.load()
        credentialStore.lastReadError?.let { error(it) }
        val engine = engine(provider, credentials) ?: error("请先保存${provider.label}的密钥")
        ensureWithinLimit(text.length, preferences.dailyCharacterLimit)
        val synthesized = engine.synthesize(text, voiceId)
        currentCoroutineContext().ensureActive()
        preferenceRepository.addUsage(text.length)
        val directory = File(context.cacheDir, "cloud-voice-preview").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.${synthesized.extension}")
        writeAtomically(file, synthesized.bytes)
        require(AudioFiles.inspect(file) != null) { "试听音频不可播放，请检查音色配置" }
        file
    }

    private fun engine(provider: CloudProvider, credentials: CloudCredentials): CloudSpeechProvider? {
        engines[provider]?.takeIf { it.first == credentials }?.let { return it.second }
        val instance = providerFactory?.invoke(provider, credentials) ?: createProvider(provider, credentials)
        if (instance != null) engines[provider] = credentials to instance
        return instance
    }

    private fun createProvider(provider: CloudProvider, credentials: CloudCredentials): CloudSpeechProvider? = when (provider) {
        CloudProvider.XUNFEI -> credentials.xunfei?.let { XunfeiSpeechProvider(it, client) }
        CloudProvider.BAIDU -> credentials.baidu?.let {
            val deviceId = preferenceRepository.clientId()
            BaiduSpeechProvider(it, deviceId, client)
        }
    }

    private fun ensureWithinLimit(characters: Int, dailyLimit: Int) {
        val used = preferenceRepository.usedToday()
        require(used + characters <= dailyLimit) {
            "已达到今日联网合成上限（$used/$dailyLimit 字），可明天继续或调整上限"
        }
    }

    private fun writeAudio(
        documentId: String,
        sentenceId: String,
        provider: CloudProvider,
        voiceId: String,
        text: String,
        audio: SynthesizedAudio,
    ): File {
        val directory = File(context.filesDir, "sentence-audio/cloud/$documentId").apply { mkdirs() }
        val safeVoice = voiceId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val hash = DocumentRepository.textHash(text).take(12)
        val file = File(directory, "$sentenceId-${provider.name.lowercase()}-$safeVoice-$hash-${UUID.randomUUID()}.${audio.extension}")
        AudioFiles.requireOwned(context, file)
        writeAtomically(file, audio.bytes)
        return file
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        temporary.outputStream().use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun duration(file: File): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)
}

private fun CloudProvider.other(): CloudProvider =
    if (this == CloudProvider.XUNFEI) CloudProvider.BAIDU else CloudProvider.XUNFEI

private fun CloudSpeechPreferences.voiceFor(provider: CloudProvider): String =
    if (provider == CloudProvider.XUNFEI) xunfeiVoice else baiduVoice
