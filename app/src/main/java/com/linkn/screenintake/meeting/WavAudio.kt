package com.linkn.screenintake.meeting

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 固定 16 kHz / 单声道 / PCM16；只处理本 App 生成的 WAV，不猜测外部文件格式。 */
object WavAudio {
    const val SAMPLE_RATE = 16000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    const val CHUNK_MS = 120000L
    const val HEADER_SIZE = 44

    fun header(size: Long): ByteArray {
        require(size in 0..(0xffffffffL - 36) && size % 2 == 0L)
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + size).toInt()); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(BYTES_PER_SECOND)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(size.toInt())
        }.array()
    }

    fun repair(file: File): Long {
        if (!file.exists() || file.length() < HEADER_SIZE) return 0
        return RandomAccessFile(file, "rw").use { out ->
            val bytes = (out.length() - HEADER_SIZE) / 2 * 2
            out.setLength(HEADER_SIZE + bytes)
            out.seek(0); out.write(header(bytes)); out.fd.sync()
            bytes * 1000 / BYTES_PER_SECOND
        }
    }

    fun chunkCount(file: File): Int {
        val size = (file.length() - HEADER_SIZE).coerceAtLeast(0)
        val bytes = BYTES_PER_SECOND * CHUNK_MS / 1000
        return ((size + bytes - 1) / bytes).toInt()
    }

    fun chunk(file: File, index: Int): ByteArray {
        require(index >= 0)
        val maxBytes = BYTES_PER_SECOND * CHUNK_MS / 1000
        val offset = HEADER_SIZE + index * maxBytes
        val remaining = file.length() - offset
        require(remaining > 0) { "音频片段不存在" }
        val size = minOf(maxBytes, remaining).toInt() / 2 * 2
        return header(size.toLong()) + ByteArray(size).also { data ->
            RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(data) }
        }
    }

    fun rms(data: ByteArray, length: Int = data.size, offset: Int = 0): Double {
        if (length <= 1) return 0.0
        var sum = 0.0
        for (i in offset until (offset + length - 1) step 2) {
            val sample = ((data[i].toInt() and 255) or (data[i + 1].toInt() shl 8)).toShort().toDouble() / 32768
            sum += sample * sample
        }
        return sqrt(sum / (length / 2))
    }

    /**
     * 针对车内远场人声的温和自动增益。只抬升确实有人声的安静帧，静音和底噪不放大；
     * 遇到企业微信外放等较响信号时快速退回，最后限幅避免削波。
     */
    class SpeechAutoGain {
        private var gain = 1.0

        fun process(data: ByteArray, length: Int): Double {
            val before = rms(data, length)
            val desired = when {
                before < 0.003 -> 1.0
                before < 0.08 -> (0.08 / before).coerceIn(1.0, 3.2)
                else -> 1.0
            }
            val smoothing = if (desired < gain) 0.55 else 0.08
            gain += (desired - gain) * smoothing
            for (i in 0 until (length - 1) step 2) {
                val sample = ((data[i].toInt() and 255) or (data[i + 1].toInt() shl 8)).toShort().toInt()
                val amplified = (sample * gain).toInt().coerceIn(-30000, 30000)
                data[i] = amplified.toByte()
                data[i + 1] = (amplified shr 8).toByte()
            }
            return gain
        }
    }
}
