package com.openminis.app.mikova.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * [mikova/voice] 平台引擎:android.speech.SpeechRecognizer(听) + TextToSpeech(说)。
 * sherpa 的兜底 —— native so 加载失败、模型缺失,或设备没有离线引擎时用。
 * 行为与 [SherpaVoiceEngine] 一致(同一 [VoiceEngine] 接口),换实现就是降级。
 */
class PlatformVoiceEngine(private val appContext: Context) : VoiceEngine {

    override val id get() = VoiceEngineId.PLATFORM
    override val displayName get() = "系统识别"
    override val supportsPartialResults get() = true

    private val degradedFlag = AtomicBoolean(false)
    override val degraded get() = degradedFlag.get()
    override fun markDegraded() { degradedFlag.set(true) }
    override fun clearDegraded() { degradedFlag.set(false) }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = AtomicBoolean(false)
    private val ttsInit = kotlinx.coroutines.CompletableDeferred<Boolean>()
    private var capturing = false
    private var playback: AudioTrack? = null
    private val playbackEpoch = java.util.concurrent.atomic.AtomicInteger(0)
    private val isSpeaking = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val playbackLevel = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val speaking: kotlinx.coroutines.flow.StateFlow<Boolean> get() = isSpeaking
    override val outputLevel: kotlinx.coroutines.flow.StateFlow<Float> get() = playbackLevel

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun ttsVoices(context: Context): List<TtsVoiceChoice> {
        ensureVoiceEngine()
        val engine = tts
        return runCatching {
            engine?.voices.orEmpty().filter { it.locale.language == "zh" }
                .sortedWith(compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }.thenBy { it.name })
                .map { voice ->
                    TtsVoiceChoice(
                        id = "system:${voice.name}",
                        label = voice.name.substringAfterLast('-').replace('_', ' '),
                        detail = listOf(voice.locale.toLanguageTag(), if (voice.isNetworkConnectionRequired) "Online" else "On-device").joinToString(" · "),
                        online = voice.isNetworkConnectionRequired,
                    )
                }
        }.getOrDefault(emptyList())
    }

    private fun ensureVoiceEngine(): TextToSpeech? = synchronized(this) {
        tts ?: TextToSpeech(appContext) { status ->
            ttsReady.set(status == TextToSpeech.SUCCESS)
            if (!ttsInit.isCompleted) ttsInit.complete(status == TextToSpeech.SUCCESS)
        }.also { tts = it }
    }

    override fun setTtsVoice(context: Context, voiceId: String?) {
        val target = tts ?: return
        val name = voiceId?.removePrefix("system:") ?: return
        target.voices.orEmpty().firstOrNull { it.name == name && it.locale.language == "zh" }?.let(target::setVoice)
    }

    @android.annotation.SuppressLint("MissingPermission")
    override fun startListen(locale: VoiceEngine.LocaleCompat, listener: VoiceEngine.Listener) {
        if (!isAvailable) {
            listener.onError(VoiceEngine.VoiceError.UNAVAILABLE, "这台设备不支持语音识别")
            return
        }
        if (capturing) return
        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = sr
        capturing = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                capturing = false
                listener.onError(mapError(error))
            }

            override fun onResults(results: Bundle?) {
                capturing = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) listener.onError(VoiceEngine.VoiceError.NO_MATCH)
                else listener.onFinal(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) listener.onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        sr.startListening(intent)
    }

    override fun stopListen() {
        capturing = false
        recognizer?.stopListening()
    }

    override fun cancel() {
        capturing = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    /** 平台引擎没有"喂文件"的公开接口:明确不支持。 */
    override suspend fun transcribeFile(wavPath: String): String? = null

    override suspend fun synthesize(text: String, play: Boolean, voiceId: String?): ByteArray? = withContext(Dispatchers.IO) {
        val engine = ensureTts() ?: return@withContext null
        if (voiceId != null) setTtsVoice(appContext, voiceId)
        val out = File(appContext.cacheDir, "tts_${UUID.randomUUID()}.wav")
        out.delete()
        val bytes = suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    val b = runCatching { if (out.exists()) out.readBytes() else null }.getOrNull()
                    if (cont.isActive) cont.resume(b)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(null)
                }
            })
            // 平台引擎只能合成到文件再读字节;拿不到就返回 null,只把播放留给调用方。
            val ok = engine.synthesizeToFile(text, null, out, UUID.randomUUID().toString()) == TextToSpeech.SUCCESS
            if (!ok && cont.isActive) cont.resume(null)
        }
        if (play && bytes != null) playWav(bytes)
        bytes
    }

    /** 首次创建并等初始化完成;之后复用。初始化失败返回 null。 */
    private suspend fun ensureTts(): TextToSpeech? {
        synchronized(this) {
            if (tts == null) {
                val engine = TextToSpeech(appContext) { status ->
                    ttsReady.set(status == TextToSpeech.SUCCESS)
                    ttsInit.complete(status == TextToSpeech.SUCCESS)
                }
                tts = engine
            }
        }
        val ok = ttsInit.await()
        if (!ok) return null
        val engine = tts ?: return null
        runCatching { engine.language = Locale.getDefault() }
        return engine
    }

    private fun playWav(bytes: ByteArray) {
        // 平台引擎合成出来的是 wav(含头);剥掉 44 字节头直接喂 AudioTrack。
        val pcm = if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else bytes
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            16_000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            pcm.size,
            AudioTrack.MODE_STATIC,
        )
        track.write(pcm, 0, pcm.size)
        playback = track
        val epoch = playbackEpoch.incrementAndGet()
        isSpeaking.value = true
        track.play()
        Thread({
            while (playbackEpoch.get() == epoch && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val sample = (track.playbackHeadPosition.toLong() * 2).toInt().coerceIn(0, (pcm.size - 1).coerceAtLeast(0))
                val end = (sample + 1000).coerceAtMost(pcm.size - 1)
                var peak = 0
                for (i in sample..end step 2) peak = maxOf(peak, kotlin.math.abs(((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()))
                playbackLevel.value = (peak / 32767f).coerceIn(0f, 1f)
                try { Thread.sleep(32L) } catch (_: InterruptedException) { break }
            }
            if (playbackEpoch.get() == epoch) {
                runCatching { track.stop() }; runCatching { track.release() }
                playback = null; isSpeaking.value = false; playbackLevel.value = 0f
            }
        }, "mikova-platform-tts-level").start()
    }

    override fun stopSpeaking() {
        playbackEpoch.incrementAndGet()
        runCatching { tts?.stop() }
        val t = playback
        playback = null
        isSpeaking.value = false
        playbackLevel.value = 0f
        runCatching { t?.pause() }
        runCatching { t?.flush() }
        runCatching { t?.stop() }
        runCatching { t?.release() }
    }

    override fun shutdown() {
        cancel()
        stopSpeaking()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady.set(false)
    }

    private fun mapError(code: Int): VoiceEngine.VoiceError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> VoiceEngine.VoiceError.NO_MATCH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceEngine.VoiceError.SPEECH_TIMEOUT
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceEngine.VoiceError.NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceEngine.VoiceError.BUSY
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceEngine.VoiceError.PERMISSION
        else -> VoiceEngine.VoiceError.UNKNOWN
    }

    private companion object {
        const val TAG = "PlatformVoice"
    }
}
