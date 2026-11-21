package com.example.voiceai.shared

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 最简单的 WAV（RIFF / PCM）读写工具。 */
object WavFile {
    const val HEADER_SIZE = 44

    /** 先写一个占位的 44 字节头，录完后再用 [finalizeHeader] 补上长度。 */
    fun writePlaceholderHeader(out: OutputStream) {
        out.write(header(0))
    }

    /** 录音结束后，按实际 PCM 长度改写文件头。 */
    fun finalizeHeader(file: File) {
        val pcmBytes = (file.length() - HEADER_SIZE).coerceAtLeast(0)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(pcmBytes))
        }
    }

    /** 把 WAV 去掉文件头，只留 raw PCM（Android SpeechRecognizer 读文件时要的是不带头的 PCM）。 */
    fun copyPcm(wav: File, pcmOut: File) {
        wav.inputStream().use { input ->
            skipFully(input, HEADER_SIZE.toLong())
            pcmOut.outputStream().use { input.copyTo(it) }
        }
    }

    fun durationSeconds(wav: File): Double =
        (wav.length() - HEADER_SIZE).coerceAtLeast(0).toDouble() / AudioSpec.BYTES_PER_SECOND

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() == -1) return
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun header(pcmBytes: Long): ByteArray {
        val byteRate = AudioSpec.BYTES_PER_SECOND
        val blockAlign = AudioSpec.CHANNELS * AudioSpec.BITS_PER_SAMPLE / 8
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + pcmBytes).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                       // fmt chunk size
            putShort(1)                      // PCM
            putShort(AudioSpec.CHANNELS.toShort())
            putInt(AudioSpec.SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(AudioSpec.BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes.toInt())
        }.array()
    }
}
