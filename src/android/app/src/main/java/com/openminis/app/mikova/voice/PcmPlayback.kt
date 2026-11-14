package com.openminis.app.mikova.voice

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/** Serializes request invalidation with publication, including synthesis that cannot be cancelled in JNI. */
internal class VoiceGeneration {
    private var epoch = 0L
    @Synchronized fun next(): Long = ++epoch
    @Synchronized fun isCurrent(token: Long): Boolean = epoch == token
    @Synchronized fun current(): Long = epoch
    @Synchronized fun runIfCurrent(token: Long, action: () -> Unit): Boolean {
        if (epoch != token) return false
        action()
        return true
    }
}

internal object VoiceLevels {
    /** SpeechRecognizer reports an engine-dependent relative RMS, NOT calibrated dBFS. */
    fun systemRmsToDb(rms: Float): Float =
        if (rms.isFinite()) ((rms.coerceIn(0f, 10f) / 10f) * 60f - 60f) else -60f

    fun rmsToDb(rms: Double): Float =
        if (rms.isFinite() && rms > 0.0) (20.0 * log10(rms)).toFloat().coerceIn(-60f, 0f) else -60f
}

internal data class PcmAudio(val bytes: ByteArray, val sampleRate: Int, val channels: Int) {
    init {
        require(sampleRate in 8_000..192_000 && channels in 1..2)
        require(bytes.isNotEmpty() && bytes.size % (channels * 2) == 0) { "Incomplete PCM frame" }
    }
    val frames: Int get() = bytes.size / (channels * 2)
    fun level(frame: Long, window: Int = maxOf(1, sampleRate / 30)): Float {
        if (frame < 0 || frame >= frames) return 0f
        val start = frame.toInt() * channels * 2
        val end = minOf(frames.toLong(), frame + window.coerceAtLeast(1)).toInt() * channels * 2
        var square = 0.0
        for (i in start until end step 2) {
            val s = (((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort() / 32768.0)
            square += s * s
        }
        return sqrt(square / ((end - start) / 2)).toFloat().coerceIn(0f, 1f)
    }
}

/** Bounded RIFF parser. Chunk sizes are unsigned, chunks are padded, format/data need not be adjacent. */
internal object WavUtil {
    fun decode(bytes: ByteArray): PcmAudio {
        fun u16(i: Int) = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8)
        fun u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
        fun tag(i: Int) = String(bytes, i, 4, Charsets.US_ASCII)
        require(bytes.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE") { "Not a RIFF/WAVE file" }
        val limit = 8L + u32(4)
        require(limit in 12L..bytes.size.toLong()) { "Truncated RIFF" }
        var pos = 12L
        var format = -1
        var channels = 0
        var rate = 0
        var bits = 0
        var align = 0
        var data: IntRange? = null
        while (pos + 8 <= limit) {
            val at = pos.toInt()
            val size = u32(at + 4)
            val start = pos + 8
            val end = start + size
            require(end <= limit && end + (size and 1) <= limit) { "Truncated WAV chunk" }
            when (tag(at)) {
                "fmt " -> {
                    require(format == -1 && size >= 16) { "Invalid fmt chunk" }
                    val f = start.toInt()
                    format = u16(f)
                    channels = u16(f + 2)
                    require(u32(f + 4) in 8_000L..192_000L) { "Unsupported sample rate" }
                    rate = u32(f + 4).toInt()
                    align = u16(f + 12)
                    bits = u16(f + 14)
                    if (format == 0xfffe) {
                        require(size >= 40 && u16(f + 16) >= 22 && u16(f + 18) == bits) { "Unsupported extensible WAV" }
                        val guidTail = byteArrayOf(0, 0, 0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113)
                        require(bytes.copyOfRange(f + 26, f + 40).contentEquals(guidTail)) { "Unsupported WAV subformat" }
                        format = u16(f + 24)
                    }
                    require(channels in 1..2 && align == channels * (bits / 8)) { "Invalid PCM layout" }
                    require((format == 1 && bits in listOf(8, 16, 24, 32)) || (format == 3 && bits == 32)) { "Unsupported WAV encoding" }
                    require(u32(f + 8) == rate.toLong() * align) { "Invalid byte rate" }
                }
                "data" -> {
                    require(data == null) { "Multiple data chunks are unsupported" }
                    data = start.toInt() until end.toInt()
                }
            }
            pos = end + (size and 1)
        }
        require(pos == limit && format != -1) { "Missing format or trailing incomplete chunk" }
        val raw = data ?: error("Missing WAV data")
        val size = raw.last - raw.first + 1
        require(size > 0 && align > 0 && size % align == 0) { "Incomplete PCM frame" }
        if (format == 1 && bits == 16) return PcmAudio(bytes.copyOfRange(raw.first, raw.last + 1), rate, channels)
        val width = bits / 8
        val result = ByteArray(size / width * 2)
        for (i in 0 until size / width) {
            val p = raw.first + i * width
            val sample = when {
                format == 3 -> {
                    val f = Float.fromBits(u32(p).toInt())
                    require(f.isFinite()) { "Non-finite WAV sample" }
                    (f.coerceIn(-1f, 1f) * 32767f).toInt()
                }
                bits == 8 -> ((bytes[p].toInt() and 255) - 128) shl 8
                bits == 24 -> ((bytes[p + 1].toInt() and 255) or (bytes[p + 2].toInt() shl 8)).toShort().toInt()
                else -> u32(p).toInt() shr 16
            }
            result[i * 2] = sample.toByte()
            result[i * 2 + 1] = (sample shr 8).toByte()
        }
        return PcmAudio(result, rate, channels)
    }

    fun pcmBytes(samples: FloatArray): ByteArray = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, value ->
            require(value.isFinite()) { "Non-finite PCM sample" }
            val s = (value.coerceIn(-1f, 1f) * 32767f).toInt()
            out[i * 2] = s.toByte()
            out[i * 2 + 1] = (s shr 8).toByte()
        }
    }

    fun encode(samples: FloatArray, sampleRate: Int): ByteArray {
        require(sampleRate in 8_000..192_000 && samples.isNotEmpty())
        val pcm = pcmBytes(samples)
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun le(value: Int, n: Int) { repeat(n) { out.write(value ushr (it * 8) and 255) } }
        fun text(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
        text("RIFF"); le(36 + pcm.size, 4); text("WAVE")
        text("fmt "); le(16, 4); le(1, 2); le(1, 2); le(sampleRate, 4)
        le(sampleRate * 2, 4); le(2, 2); le(16, 2)
        text("data"); le(pcm.size, 4); out.write(pcm)
        return out.toByteArray()
    }

    fun readMono16k(path: String): FloatArray {
        val pcm = decode(File(path).readBytes())
        val mono = FloatArray(pcm.frames) { frame ->
            var sum = 0f
            repeat(pcm.channels) { channel ->
                val p = (frame * pcm.channels + channel) * 2
                sum += ((pcm.bytes[p].toInt() and 255) or (pcm.bytes[p + 1].toInt() shl 8)).toShort() / 32768f
            }
            sum / pcm.channels
        }
        if (pcm.sampleRate == 16_000) return mono
        val ratio = pcm.sampleRate / 16_000.0
        return FloatArray((mono.size / ratio).toInt().coerceAtLeast(1)) { i ->
            val at = i * ratio
            val left = at.toInt().coerceAtMost(mono.lastIndex)
            mono[left] + (mono[minOf(left + 1, mono.lastIndex)] - mono[left]) * (at - left).toFloat()
        }
    }
}

/** Injectable device boundary; tests exercise completion and cancellation without Android audio. */
internal interface PcmSink {
    fun play()
    fun headFrames(): Long
    fun stop()
    fun release()
}

/** One worker owns release. Stop never releases a track under a poll/write on another thread. */
internal class PcmPlayback(private val createSink: (PcmAudio) -> PcmSink = { AndroidPcmSink(it) }) {
    private val lock = Any()
    private val generation = VoiceGeneration()
    private var active: PcmSink? = null
    private val playing = MutableStateFlow(false)
    private val level = MutableStateFlow(0f)
    val speaking: StateFlow<Boolean> get() = playing
    val outputLevel: StateFlow<Float> get() = level

    fun beginRequest(): Long = synchronized(lock) {
        val token = generation.next()
        stopLocked()
        token
    }
    fun isCurrent(token: Long): Boolean = generation.isCurrent(token)
    fun stop() { beginRequest() }
    fun cancel(token: Long) = synchronized(lock) {
        if (generation.isCurrent(token)) {
            generation.next()
            stopLocked()
        }
    }
    fun ifCurrent(token: Long, action: () -> Unit): Boolean = synchronized(lock) {
        generation.runIfCurrent(token, action)
    }
    private fun stopLocked() {
        try { active?.stop() } catch (e: Exception) { Log.w("PcmPlayback", "Stop failed", e) }
        active = null
        playing.value = false
        level.value = 0f
    }

    fun play(audio: PcmAudio, token: Long) {
        synchronized(lock) {
            if (!generation.isCurrent(token)) return
            // Publish before returning to the UI; output remains zero until the device starts.
            playing.value = true
        }
        Thread({
            var sink: PcmSink? = null
            try {
                if (!isCurrent(token)) return@Thread
                val device = createSink(audio)
                sink = device
                synchronized(lock) {
                    if (!generation.isCurrent(token)) return@Thread
                    active = device
                    device.play()
                    playing.value = true
                }
                var lastHead = -1L
                var lastProgress = System.nanoTime()
                while (true) {
                    val keepPlaying = synchronized(lock) {
                        if (!generation.isCurrent(token) || active !== device) false
                        else {
                            val head = device.headFrames()
                            if (head != lastHead) {
                                lastHead = head
                                lastProgress = System.nanoTime()
                            }
                            // MODE_STATIC remains PLAYSTATE_PLAYING at EOF. Count actual frames instead.
                            val pending = head < audio.frames && System.nanoTime() - lastProgress < 5_000_000_000L
                            level.value = if (pending) audio.level(head) else 0f
                            pending
                        }
                    }
                    if (!keepPlaying) break
                    Thread.sleep(32)
                }
            } catch (e: Exception) {
                Log.w("PcmPlayback", "PCM playback failed", e)
            } finally {
                synchronized(lock) {
                    sink?.let { device ->
                        try { device.stop() } catch (e: Exception) { Log.w("PcmPlayback", "Stop failed", e) }
                        try { device.release() } catch (e: Exception) { Log.w("PcmPlayback", "Release failed", e) }
                    }
                    if (active === sink && generation.isCurrent(token)) {
                        active = null
                        playing.value = false
                        level.value = 0f
                    }
                }
            }
        }, "mikova-pcm").start()
    }
}

@Suppress("DEPRECATION")
private class AndroidPcmSink(audio: PcmAudio) : PcmSink {
    private val track = AudioTrack(
        AudioManager.STREAM_MUSIC,
        audio.sampleRate,
        if (audio.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        audio.bytes.size,
        AudioTrack.MODE_STATIC,
    )
    init {
        try {
            check(track.state != AudioTrack.STATE_UNINITIALIZED) { "AudioTrack initialization failed" }
            // MODE_STATIC starts in STATE_NO_STATIC_DATA and becomes initialized after the first write.
            check(track.write(audio.bytes, 0, audio.bytes.size) == audio.bytes.size) { "Short AudioTrack write" }
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack has no static data" }
        } catch (e: Exception) {
            track.release()
            throw e
        }
    }
    override fun play() { track.play() }
    override fun headFrames(): Long = track.playbackHeadPosition.toLong() and 0xffffffffL
    override fun stop() { track.pause(); track.stop() }
    override fun release() { track.release() }
}
