package com.openminis.app.mikova.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PcmPlaybackTest {
    private fun le(value: Int, width: Int) = ByteArray(width) { (value ushr (it * 8)).toByte() }
    private fun chunk(name: String, bytes: ByteArray) = name.toByteArray() + le(bytes.size, 4) + bytes +
        if (bytes.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    private fun format(rate: Int, channels: Int, bits: Int = 16, encoding: Int = 1): ByteArray =
        le(encoding, 2) + le(channels, 2) + le(rate, 4) + le(rate * channels * bits / 8, 4) +
            le(channels * bits / 8, 2) + le(bits, 2)
    private fun wav(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write("WAVE".toByteArray())
            chunks.forEach { write(it) }
        }.toByteArray()
        return "RIFF".toByteArray() + le(body.size, 4) + body
    }

    @Test fun preservesSampleRateAndStereoPastOddMetadataChunks() {
        val data = byteArrayOf(0, -128, -1, 127, 0, 0, 0, 0)
        val pcm = WavUtil.decode(wav(chunk("JUNK", byteArrayOf(1, 2, 3)),
            chunk("fmt ", format(48_000, 2)), chunk("LIST", byteArrayOf(4)), chunk("data", data)))
        assertEquals(48_000, pcm.sampleRate)
        assertEquals(2, pcm.channels)
        assertEquals(2, pcm.frames)
        assertArrayEquals(data, pcm.bytes)
        assertTrue(pcm.level(0, 1) > 0.99f)
        assertEquals(0f, pcm.level(2), 0f)
        assertEquals(0f, pcm.level(Long.MAX_VALUE), 0f)
    }

    @Test fun acceptsDataBeforeFormatAndExtendedPcmFormat() {
        val pcm = WavUtil.decode(wav(chunk("data", byteArrayOf(0, 0)),
            chunk("fmt ", format(22_050, 1) + byteArrayOf(0, 0))))
        assertEquals(22_050, pcm.sampleRate)
        assertEquals(1, pcm.frames)
    }

    @Test fun unsignedEightBitAndFloatAreConvertedToPcm16() {
        val pcm8 = WavUtil.decode(wav(chunk("fmt ", format(8_000, 1, 8)), chunk("data", byteArrayOf(0, -128, -1))))
        assertArrayEquals(byteArrayOf(0, -128, 0, 0, 0, 127), pcm8.bytes)
        val floats = le(0.5f.toRawBits(), 4) + le((-0.5f).toRawBits(), 4)
        val pcmFloat = WavUtil.decode(wav(chunk("fmt ", format(44_100, 1, 32, 3)), chunk("data", floats)))
        assertEquals(0.5f, pcmFloat.level(0), 0.001f)
    }

    @Test fun pcmRoundTripAndLastSampleHaveNoOutOfBoundsAccess() {
        val pcm = WavUtil.decode(WavUtil.encode(floatArrayOf(-1f, 0f, 0.5f), 8_000))
        assertEquals(8_000, pcm.sampleRate)
        assertEquals(3, pcm.frames)
        assertEquals(0.5f, pcm.level(2), 0.001f)
        assertEquals(0f, pcm.level(3), 0f)
    }

    @Test fun rejectsMalformedChunksBeforeReadingSamples() {
        val valid = wav(chunk("fmt ", format(16_000, 1)), chunk("data", byteArrayOf(0, 0)))
        assertThrows(IllegalArgumentException::class.java) { WavUtil.decode(valid.copyOf(valid.size - 1)) }
        val huge = valid.copyOf().apply { repeat(4) { this[16 + it] = -1 } }
        assertThrows(IllegalArgumentException::class.java) { WavUtil.decode(huge) }
        assertThrows(IllegalArgumentException::class.java) {
            WavUtil.decode(wav(chunk("fmt ", byteArrayOf(1, 0)), chunk("data", byteArrayOf(0, 0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavUtil.decode(wav(chunk("fmt ", format(16_000, 2)), chunk("data", byteArrayOf(0, 0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavUtil.decode(wav(chunk("fmt ", format(16_000, 1)), chunk("data", byteArrayOf(0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavUtil.decode(wav(chunk("fmt ", format(16_000, 1, 16, 6)), chunk("data", byteArrayOf(0, 0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavUtil.decode(wav(chunk("fmt ", format(16_000, 1, 32, 3)), chunk("data", le(Float.NaN.toRawBits(), 4))))
        }
    }

    @Test fun normalizedInputLevelsAreFiniteBoundedAndMonotonic() {
        assertEquals(-60f, VoiceLevels.systemRmsToDb(Float.NaN), 0f)
        assertEquals(-60f, VoiceLevels.systemRmsToDb(Float.POSITIVE_INFINITY), 0f)
        assertEquals(-60f, VoiceLevels.systemRmsToDb(-12f), 0f)
        assertEquals(-30f, VoiceLevels.systemRmsToDb(5f), 0f)
        assertEquals(0f, VoiceLevels.systemRmsToDb(30f), 0f)
        assertEquals(-60f, VoiceLevels.rmsToDb(0.0), 0f)
        assertEquals(-20f, VoiceLevels.rmsToDb(0.1), 0.001f)
        assertEquals(0f, VoiceLevels.rmsToDb(2.0), 0f)
    }

    private class FakeSink(private val frames: Int) : PcmSink {
        val played = CountDownLatch(1)
        val released = CountDownLatch(1)
        val releases = AtomicInteger()
        @Volatile var head = 0L
        override fun play() { played.countDown() }
        override fun headFrames() = head
        override fun stop() = Unit
        override fun release() { releases.incrementAndGet(); released.countDown() }
        fun finish() { head = frames.toLong() }
    }
    private fun await(latch: CountDownLatch) { assertTrue("Worker timed out", latch.await(3, TimeUnit.SECONDS)) }
    private fun silentAudio() = PcmAudio(ByteArray(160), 8_000, 1)

    @Test fun playbackEndsAtFrameCountEvenIfDeviceNeverChangesPlayState() {
        val pcm = silentAudio()
        val sink = FakeSink(pcm.frames)
        val player = PcmPlayback { sink }
        val token = player.beginRequest()
        player.play(pcm, token)
        await(sink.played)
        sink.finish()
        await(sink.released)
        // Read under the same lock after release; do NOT call stop to manufacture completion.
        player.ifCurrent(token) {
            assertFalse(player.speaking.value)
            assertEquals(0f, player.outputLevel.value, 0f)
        }
        assertEquals(1, sink.releases.get())
    }

    @Test fun lateSynthesisCannotPlayAfterStopAndOldCancelCannotStopNewRequest() {
        val calls = AtomicInteger()
        val player = PcmPlayback { calls.incrementAndGet(); FakeSink(it.frames) }
        val old = player.beginRequest()
        player.stop()
        player.play(silentAudio(), old)
        assertEquals(0, calls.get())
        val fresh = player.beginRequest()
        player.cancel(old)
        assertTrue(player.isCurrent(fresh))
        assertFalse(player.ifCurrent(old) { fail("Late publication") })
    }

    @Test fun stoppingDuringDeviceCreationPreventsPlayAndReleasesExactlyOnce() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val sink = FakeSink(80)
        val player = PcmPlayback {
            entered.countDown()
            check(proceed.await(3, TimeUnit.SECONDS))
            sink
        }
        player.play(silentAudio(), player.beginRequest())
        await(entered)
        player.stop()
        proceed.countDown()
        await(sink.released)
        assertEquals(1L, sink.played.count)
        assertEquals(1, sink.releases.get())
        assertFalse(player.speaking.value)
    }

    @Test fun oldPlaybackFinallyCannotClearNewPlaybackState() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val first = FakeSink(80)
        val second = FakeSink(80)
        val factories = AtomicInteger()
        val player = PcmPlayback {
            if (factories.incrementAndGet() == 1) {
                entered.countDown()
                check(proceed.await(3, TimeUnit.SECONDS))
                first
            } else second
        }
        player.play(silentAudio(), player.beginRequest())
        await(entered)
        val fresh = player.beginRequest()
        player.play(silentAudio(), fresh)
        await(second.played)
        proceed.countDown()
        await(first.released)
        player.ifCurrent(fresh) { assertTrue(player.speaking.value) }
        player.stop()
        await(second.released)
        assertEquals(1, first.releases.get())
        assertEquals(1, second.releases.get())
    }
}
