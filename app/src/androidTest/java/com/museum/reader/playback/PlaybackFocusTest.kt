package com.museum.reader.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.MainActivity
import com.museum.reader.data.DocumentRepository
import com.museum.reader.data.writeTestWav
import com.museum.reader.domain.OutputGeneration
import com.museum.reader.domain.PlaybackCommand
import com.museum.reader.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

class PlaybackFocusTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(AudioManager::class.java)
    private val records = JSONArray()
    private var evidenceCase = "unset"
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun command(command: PlaybackCommand) = main { PlaybackController.send(context, command) }
    private fun service() = PlaybackController::class.java.getDeclaredField("service").apply { isAccessible = true }
        .get(PlaybackController) as TtsPlaybackService
    private fun field(name: String) = TtsPlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun player() = field("player").get(service()) as ExoPlayer?
    private fun waitFor(label: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false; main { ready = predicate() }
            if (ready) return
            SystemClock.sleep(30)
        }
        error("Timeout: $label; ${PlaybackBus.snapshot.value.status}")
    }
    private fun external(gain: Int): AudioFocusRequest = AudioFocusRequest.Builder(gain)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { }.build().also { request ->
            main { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(request)) }
        }
    private fun record(stage: String) {
        main { records.put(JSONObject().put("stage", stage).put("status", PlaybackBus.snapshot.value.status)
            .put("sentence", PlaybackBus.snapshot.value.currentIndex).put("source", PlaybackBus.snapshot.value.outputSource)
            .put("fileVolume", if (PlaybackController.diagnostics() != null) player()?.volume else null)) }
        val name = InstrumentationRegistry.getArguments().getString("evidenceName", "playback-focus")!!
        val out = File(context.getExternalFilesDir(null), name).apply { mkdirs() }
        File(out, "focus-$evidenceCase.json").writeText(JSONObject().put("actualAudioManagerRequests", true)
            .put("audioSample", "synthetic WAV; TTS branch explicitly uses a non-speaking engine double")
            .put("audibleVerification", false).put("records", records).toString(2))
    }
    private fun fixture(): Pair<File, PlaybackSnapshot> {
        val file = File(context.filesDir, "sentence-audio/focus-${UUID.randomUUID()}/test.wav")
        writeTestWav(file)
        val bytes = file.readBytes(); val sound = bytes.copyOfRange(44, bytes.size); val header = bytes.copyOf(44)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 36 + sound.size*30).putInt(40, sound.size*30)
        file.outputStream().use { it.write(header); repeat(30) { _ -> it.write(sound) } }
        val sentence = "First synthetic speech."
        val audio = SentenceAudio(UUID.randomUUID().toString(), "focus-test", "s1", AudioKind.RECORDING,
            "local", "self", file.path, DocumentRepository.textHash(sentence), 30000)
        return file to PlaybackSnapshot(documentId = "focus-test", documentTitle = "音频焦点验收",
            sentences = listOf(sentence, "Second synthetic speech."), sentenceIds = listOf("s1", "s2"),
            paragraphIndices = listOf(0, 0), audioCandidates = mapOf("s1" to listOf(audio)), cloudEnabled = true)
    }

    @Test fun realDuckRequestsLowerBothLocalSourcesButActualFocusLossPausesWithoutResume() {
        evidenceCase = "local-sources"
        val (file, base) = fixture()
        var external: AudioFocusRequest? = null
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                for (kind in listOf(AudioKind.RECORDING, AudioKind.CLOUD)) {
                    val audio = base.audioCandidates.getValue("s1").single().copy(kind = kind, provider = "BAIDU")
                    command(PlaybackCommand.Load(base.copy(audioCandidates = mapOf("s1" to listOf(audio))), true))
                    waitFor("output started") { PlaybackController.diagnostics()?.filePlaying == true }
                    var oldPlayer: ExoPlayer? = null
                    main { oldPlayer = player() }
                    external = external(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    waitFor("actual duck") { PlaybackBus.snapshot.value.isPlaying && player()?.volume == 0.2f }
                    record("$kind-ducked")
                    main { manager.abandonAudioFocusRequest(external!!) }; external = null
                    waitFor("volume restored") { player()?.volume == 1f }
                    main { assertSame(oldPlayer, player()); assertTrue(PlaybackBus.snapshot.value.isPlaying) }
                    record("$kind-gain-keeps-same-output")
                    external = external(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    waitFor("real loss pauses") { !PlaybackBus.snapshot.value.isPlaying && player() == null }
                    main { manager.abandonAudioFocusRequest(external!!) }; external = null
                    SystemClock.sleep(300)
                    main { assertFalse(PlaybackBus.snapshot.value.isPlaying) }
                    record("$kind-loss-not-auto-resumed")
                }
                command(PlaybackCommand.Stop)
            } finally {
                external?.let { request -> main { manager.abandonAudioFocusRequest(request) } }
                command(PlaybackCommand.Clear); file.delete(); file.parentFile?.delete()
            }
        }
    }

    private class SilentEngine(context: Context) : TextToSpeech(object : ContextWrapper(context) {
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int) = false
    }, null) {
        val spoken = mutableListOf<String>()
        var stops = 0
        override fun setLanguage(locale: Locale?) = LANG_AVAILABLE
        override fun setSpeechRate(rate: Float) = SUCCESS
        override fun setPitch(pitch: Float) = SUCCESS
        override fun speak(text: CharSequence?, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
            spoken.add(text.toString()); return SUCCESS
        }
        override fun stop(): Int { stops++; return SUCCESS }
        override fun shutdown() = Unit
    }

    @Test fun ttsWaitsForDuckGainUsesTheLatestChunkAndStopRejectsLateFocusCallbacks() {
        evidenceCase = "tts-boundary"
        val (file, base) = fixture()
        var external: AudioFocusRequest? = null
        val engine = SilentEngine(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                command(PlaybackCommand.Load(base, true))
                waitFor("file output") { PlaybackController.diagnostics()?.filePlaying == true }
                lateinit var lateGainAtHandler: () -> Unit
                main {
                    val service = service()
                    TtsPlaybackService::class.java.getDeclaredMethod("invalidateOutput").apply { isAccessible = true }.invoke(service)
                    field("tts").set(service, engine); field("ttsReady").setBoolean(service, true)
                    field("ttsLanguageAvailable").setBoolean(service, true)
                    val token = (field("generation").get(service) as OutputGeneration).next()
                    TtsPlaybackService::class.java.getDeclaredMethod("useSystemVoice", Long::class.javaPrimitiveType, String::class.java)
                        .apply { isAccessible = true }.invoke(service, token, base.sentences[0])
                    val handler = TtsPlaybackService::class.java.getDeclaredMethod("onFocusChanged", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                    lateGainAtHandler = { handler.invoke(service, AudioManager.AUDIOFOCUS_GAIN); Unit }
                    assertEquals(listOf(base.sentences[0]), engine.spoken)
                }
                external = external(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                waitFor("speech waits") { field("ttsWaitingForFocus").getBoolean(service()) && PlaybackBus.snapshot.value.isPlaying }
                command(PlaybackCommand.Seek(1))
                waitFor("new chunk waits") { PlaybackBus.snapshot.value.currentIndex == 1 && field("ttsWaitingForFocus").getBoolean(service()) }
                main { assertEquals(1, engine.spoken.size) }
                record("tts-current-chunk-waits")
                main { manager.abandonAudioFocusRequest(external!!) }; external = null
                waitFor("latest speech resumed") { engine.spoken.size == 2 }
                main { assertEquals(base.sentences[1], engine.spoken.last()) }
                record("tts-gain-speaks-new-chunk")
                external = external(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                waitFor("speech waiting before stop") { field("ttsWaitingForFocus").getBoolean(service()) }
                command(PlaybackCommand.Stop)
                main { manager.abandonAudioFocusRequest(external!!); lateGainAtHandler() }
                external = null
                SystemClock.sleep(300)
                main { assertFalse(PlaybackBus.snapshot.value.isPlaying); assertEquals(2, engine.spoken.size) }
                record("tts-stop-ignores-late-gain")
            } finally {
                external?.let { request -> main { manager.abandonAudioFocusRequest(request) } }
                command(PlaybackCommand.Clear); file.delete(); file.parentFile?.delete()
            }
        }
    }
}
