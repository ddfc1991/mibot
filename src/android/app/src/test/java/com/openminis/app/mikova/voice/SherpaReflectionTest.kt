package com.openminis.app.mikova.voice

import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual reflection adapter against the official Kotlin method shape, without JNI. */
class SherpaReflectionTest {
    class Result(val text: String)
    class Stream {
        var samples = floatArrayOf()
        var rate = 0
        var finished = false
        var released = false
        fun acceptWaveform(samples: FloatArray, sampleRate: Int) { this.samples = samples; rate = sampleRate }
        fun inputFinished() { finished = true }
        fun release() { released = true }
    }
    class Recognizer {
        val stream = Stream()
        var decoded = false
        var released = false
        fun createStream(hotwords: String): Stream { check(hotwords.isEmpty()); return stream }
        fun isReady(stream: Stream): Boolean { check(stream === this.stream); return !decoded }
        fun decode(stream: Stream) { check(stream === this.stream); decoded = true }
        fun getResult(stream: Stream): Result { check(stream === this.stream); return Result("测试") }
        fun release() { released = true }
    }
    class GeneratedAudio(val samples: FloatArray, val sampleRate: Int)
    class Tts {
        var sid = -1
        fun numSpeakers(): Int = 3
        fun generate(text: String, sid: Int, speed: Float): GeneratedAudio {
            require(text.isNotEmpty() && speed == 1f && sid in 0 until numSpeakers())
            this.sid = sid
            return GeneratedAudio(floatArrayOf(0.1f, -0.2f), 8_000)
        }
    }

    @Test fun onlineMethodsReceiveTheirStreamAndResourcesRelease() {
        val rec = Recognizer()
        val stream = SherpaJni.createStream(rec)
        SherpaJni.acceptWaveform(stream, floatArrayOf(0.5f), 16_000)
        assertTrue(SherpaJni.isReady(rec, stream))
        SherpaJni.decode(rec, stream)
        assertFalse(SherpaJni.isReady(rec, stream))
        assertEquals("测试", SherpaJni.resultText(rec, stream))
        assertEquals(16_000, rec.stream.rate)
        SherpaJni.inputFinished(stream)
        SherpaJni.release(stream)
        SherpaJni.release(rec)
        assertTrue(rec.stream.finished)
        assertTrue(rec.stream.released)
        assertTrue(rec.released)
    }

    @Test fun generatedAudioUsesGettersAndRuntimeSpeakerCount() {
        val tts = Tts()
        assertEquals(3, SherpaJni.numSpeakers(tts))
        val (samples, rate) = SherpaJni.generate(tts, "你好", 2)
        assertEquals(2, tts.sid)
        assertEquals(8_000, rate)
        assertArrayEquals(floatArrayOf(0.1f, -0.2f), samples, 0f)
    }

    @Test fun incompatibleReflectionDoesNotSilentlyReturnEmptyRecognition() {
        assertThrows(NoSuchMethodException::class.java) { SherpaJni.isReady(Any(), Stream()) }
        assertThrows(NoSuchMethodException::class.java) { SherpaJni.generate(Any(), "你好", 0) }
    }
}
