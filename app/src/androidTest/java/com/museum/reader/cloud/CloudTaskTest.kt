package com.museum.reader.cloud

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.data.*
import com.museum.reader.data.db.ReaderDatabase
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic provider doubles; these are never evidence of real-provider acceptance. */
@RunWith(AndroidJUnit4::class)
class CloudTaskTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun provider(kind: CloudProvider, block: suspend () -> SynthesizedAudio) = object : CloudSpeechProvider {
        override val provider = kind
        override suspend fun synthesize(text: String, voiceId: String) = block()
    }

    private suspend fun fixture(block: suspend (
        DocumentRepository, CloudSpeechPreferencesRepository, ReaderDocument, String, ByteArray,
    ) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val repository = DocumentRepository(context, db)
        val prefs = CloudSpeechPreferencesRepository(context)
        val previous = prefs.load()
        val file = File(context.cacheDir, "cloud-fixture-" + UUID.randomUUID() + ".wav")
        try {
            prefs.save(CloudSpeechPreferences(enabled = true, dailyCharacterLimit = 200_000), synchronous = true)
            val doc = repository.create("云任务测试", "测试句。", DocumentSource.TXT)
            val sentence = repository.load(doc.id)!!.content.sentences.single()
            writeTestWav(file)
            block(repository, prefs, doc, sentence.id, file.readBytes())
        } finally {
            repository.allAudioAssets().forEach { File(it.path).let { f -> f.delete(); f.parentFile?.delete() } }
            file.delete(); db.close(); prefs.save(previous, synchronous = true)
        }
    }

    @Test fun primaryFailureFallsBackAndRetryReusesCompletedBackupCache() = runBlocking {
        fixture { repo, prefs, doc, sentence, bytes ->
            val primary = AtomicInteger(); val backup = AtomicInteger()
            val manager = CloudSpeechManager(context, repo, SecureCredentialStore(context), prefs) { kind, _ ->
                provider(kind) {
                    if (kind == CloudProvider.XUNFEI) { primary.incrementAndGet(); error("模拟鉴权失败") }
                    backup.incrementAndGet(); SynthesizedAudio(bytes, "wav")
                }
            }
            assertEquals(CloudProvider.BAIDU, manager.synthesizeSentence(doc.id, sentence, doc.text).provider)
            val retry = manager.synthesizeSentence(doc.id, sentence, doc.text)
            assertTrue(retry.fromCache)
            assertEquals(1, primary.get())
            assertEquals(1, backup.get())
        }
    }

    @Test fun cancellingInFlightRequestCannotStartBackupProvider() = runBlocking {
        fixture { repo, prefs, doc, sentence, _ ->
            val entered = CompletableDeferred<Unit>()
            val backup = AtomicInteger()
            val manager = CloudSpeechManager(context, repo, SecureCredentialStore(context), prefs) { kind, _ ->
                provider(kind) {
                    if (kind == CloudProvider.BAIDU) backup.incrementAndGet()
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val job = launch(Dispatchers.IO) { manager.synthesizeSentence(doc.id, sentence, doc.text) }
            withTimeout(2000) { entered.await() }
            val start = android.os.SystemClock.elapsedRealtime()
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(android.os.SystemClock.elapsedRealtime() - start < 2000)
            assertEquals(0, backup.get())
            assertTrue(repo.allAudioAssets().isEmpty())
        }
    }

    @Test fun editingWhileSynthesizingCannotBindOldAudioToNewText() = runBlocking {
        fixture { repo, prefs, doc, sentence, bytes ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val manager = CloudSpeechManager(context, repo, SecureCredentialStore(context), prefs) { kind, _ ->
                provider(kind) { calls.incrementAndGet(); entered.complete(Unit); release.await(); SynthesizedAudio(bytes, "wav") }
            }
            val task = async(Dispatchers.IO) { runCatching { manager.synthesizeSentence(doc.id, sentence, doc.text) } }
            withTimeout(2000) { entered.await() }
            repo.updateContent(doc.id, doc.title, "已经修改的句子。")
            release.complete(Unit)
            assertTrue(task.await().isFailure)
            assertEquals(1, calls.get())
            assertTrue(repo.allAudioAssets().all { it.archivedAt != null })
            assertNotEquals(sentence, repo.load(doc.id)!!.content.sentences.single().id)
        }
    }

    @Test fun changingAnotherSentenceStillInvalidatesCapturedContentVersion() = runBlocking<Unit> {
        fixture { repo, prefs, doc, sentence, bytes ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val version = repo.contentVersion(doc.id)
            val manager = CloudSpeechManager(context, repo, SecureCredentialStore(context), prefs) { kind, _ ->
                provider(kind) { calls.incrementAndGet(); entered.complete(Unit); release.await(); SynthesizedAudio(bytes, "wav") }
            }
            val task = async(Dispatchers.IO) { runCatching { manager.synthesizeSentence(doc.id, sentence, doc.text, version) } }
            withTimeout(2000) { entered.await() }
            repo.updateContent(doc.id, doc.title, doc.text + "另外一句已改变。")
            release.complete(Unit)
            assertTrue(task.await().isFailure)
            assertEquals(1, calls.get())
            assertEquals(sentence, repo.load(doc.id)!!.content.sentences.first().id)
            assertTrue(repo.allAudioAssets().all { it.archivedAt != null })
        }
    }

    @Test fun bothProvidersFailAndDailyLimitStopsNewRequests() = runBlocking {
        fixture { repo, prefs, doc, sentence, _ ->
            val calls = AtomicInteger()
            val manager = CloudSpeechManager(context, repo, SecureCredentialStore(context), prefs) { kind, _ ->
                provider(kind) { calls.incrementAndGet(); error("模拟超时或额度不足") }
            }
            assertTrue(runCatching { manager.synthesizeSentence(doc.id, sentence, doc.text) }.isFailure)
            assertEquals(2, calls.get())
            val before = prefs.usedToday()
            prefs.addUsage(200_000)
            try {
                assertTrue(runCatching { manager.synthesizeSentence(doc.id, sentence, doc.text) }.isFailure)
                assertEquals(2, calls.get())
            } finally {
                val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                context.getSharedPreferences("cloud_speech_settings", 0).edit().putInt("usage_$date", before).commit()
            }
        }
    }
}
