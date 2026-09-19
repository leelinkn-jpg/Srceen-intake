package com.linkn.screenintake.meeting

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class MeetingAudioTest {
    @Test fun wavHeaderContainsCorrectPcmFormat() {
        val data = WavAudio.header(64000)
        assertEquals(44, data.size)
        assertEquals("RIFF", String(data, 0, 4))
        assertEquals("WAVE", String(data, 8, 4))
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28))
        assertEquals(64000, b.getInt(40))
    }
    @Test fun chunksCoverLongRecordingExactlyWithoutUnboundedMemory() {
        val file = File.createTempFile("meeting-test", ".wav")
        try {
            val firstSize = WavAudio.BYTES_PER_SECOND * 120
            val total = firstSize + 32000
            RandomAccessFile(file, "rw").use {
                it.write(WavAudio.header(total.toLong()))
                it.write(ByteArray(total) { i -> (i % 251).toByte() })
            }
            assertEquals(2, WavAudio.chunkCount(file))
            val first = WavAudio.chunk(file, 0)
            val last = WavAudio.chunk(file, 1)
            assertEquals(firstSize + 44, first.size)
            assertEquals(32000 + 44, last.size)
            assertTrue(Base64.getEncoder().encode(first).size < 10_000_000)
            assertEquals((firstSize % 251).toByte(), last[44])
            assertEquals(((total - 1) % 251).toByte(), last.last())
            assertThrows(IllegalArgumentException::class.java) { WavAudio.chunk(file, 2) }
        } finally { file.delete() }
    }
    @Test fun interruptedWavCanBeRepairedWithoutDiscardingSamples() {
        val file = File.createTempFile("meeting-recover", ".wav")
        try {
            file.writeBytes(WavAudio.header(0) + ByteArray(32001) { 1 })
            assertEquals(1000L, WavAudio.repair(file))
            assertEquals(32044L, file.length())
            val header = file.readBytes()
            assertEquals(32000, ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
            assertEquals(1.toByte(), header[44])
        } finally { file.delete() }
    }
    @Test fun silenceDetectionDoesNotReadWavHeaderAsSound() {
        val wav = WavAudio.header(3200) + ByteArray(3200)
        assertEquals(0.0, WavAudio.rms(wav, 3200, 44), 0.0)
        assertTrue(WavAudio.rms(byteArrayOf(0, 64, 0, -64)) > 0.4)
    }
    @Test fun meetingMetadataAndPartialTranscriptsSurviveRoundTrip() {
        val record = MeetingRecord("bfc58a99-3f85-4451-b63f-b7027eefbe38", 123456789, "content://folder",
            status = "failed", durationMs = 130000, silencedMs = 3000, parts = listOf("第一段"), error = "网络失败")
        assertEquals(record, MeetingRecord.fromJson(record.toJson()))
        assertTrue(record.markdown().contains("第一段"))
        assertTrue(record.markdown().contains("3 秒被系统静音"))
        assertTrue(record.markdown().contains("00:00:00"))
        assertEquals("01:01:01", MeetingRecord.clock(3661000))
    }
    @Test fun quietSpeechIsRaisedWithoutAmplifyingSilenceOrClipping() {
        fun pcm(sample: Int) = ByteArray(3200).also { data ->
            for (i in data.indices step 2) { data[i] = sample.toByte(); data[i + 1] = (sample shr 8).toByte() }
        }
        val gain = WavAudio.SpeechAutoGain()
        val silence = pcm(0)
        repeat(20) { gain.process(silence, silence.size) }
        assertEquals(0.0, WavAudio.rms(silence), 0.0)
        val quiet = pcm(800)
        repeat(20) { gain.process(quiet, quiet.size) }
        assertTrue(WavAudio.rms(quiet) > 0.04)
        val loud = pcm(20000)
        repeat(4) { gain.process(loud, loud.size) }
        assertTrue(loud.indices.step(2).all { i ->
            val value = ((loud[i].toInt() and 255) or (loud[i + 1].toInt() shl 8)).toShort().toInt()
            value in -30000..30000
        })
    }

    @Test fun miniReadyRecordReplacesPhonePendingRecord() {
        val base = MeetingRecord("bfc58a99-3f85-4451-b63f-b7027eefbe38", 123, "content://folder",
            status = "pending_local", synced = true)
        assertTrue(MeetingRepository.shouldUseRemote(base, base.copy(status = "ready", parts = listOf("转录结果"))))
        assertFalse(MeetingRepository.shouldUseRemote(base, base.copy(status = "recorded", synced = false)))
    }
}
