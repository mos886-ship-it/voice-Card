package com.example.wristai

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * 在 m4a 里加上三星的「录音」标记，让三星录音机把它当成录音档列出来。
 *
 * Fold5 实机比对（2026-11-17）：录音机只列出 moov/udta/smta 里有 `saut` 子 box 的档案
 * （录音机自己录的、Galaxy Watch 同步过来的都有）。没有这个标记，即使放在
 * Recordings/Voice Recorder/ 也不会出现；加上之后就会列出，Galaxy AI 转录正常。
 *
 * 只改档案最后的 moov，音频数据（mdat）位置不变，所以不用改 chunk offset。
 */
object SamsungRecordingTag {
    private val SAUT = box("saut", byteArrayOf(0, 0, 0, 1, 0, 1))

    private class Atom(val type: String, val offset: Long, val size: Long, val headerSize: Int)

    /** 成功加上（或本来就有）返回 true；档案结构不支持（moov 不在最后）返回 false。 */
    fun apply(file: File): Boolean = RandomAccessFile(file, "rw").use { raf ->
        val top = atoms(raf, 0, raf.length())
        val moov = top.lastOrNull()?.takeIf { it.type == "moov" } ?: return false
        val moovBytes = read(raf, moov.offset + moov.headerSize, (moov.size - moov.headerSize).toInt())

        val newMoovBody = rewriteChildren(moovBytes, "udta", createIfMissing = true) { udta ->
            rewriteChildren(udta, "smta", createIfMissing = true, fullBox = true) { smta ->
                if (childTypes(smta, 4).contains("saut")) smta
                else smta.copyOfRange(0, 4) + SAUT + smta.copyOfRange(4, smta.size)
            }
        }
        if (newMoovBody.contentEquals(moovBytes)) return true
        raf.setLength(moov.offset)
        raf.seek(moov.offset)
        raf.write(box("moov", newMoovBody))
        true
    }

    /** 测试用：档案里是否已有 saut 标记。 */
    fun hasTag(file: File): Boolean = RandomAccessFile(file, "r").use { raf ->
        val moov = atoms(raf, 0, raf.length()).firstOrNull { it.type == "moov" } ?: return false
        val body = read(raf, moov.offset + moov.headerSize, (moov.size - moov.headerSize).toInt())
        val udta = child(body, 0, "udta") ?: return false
        val smta = child(udta, 0, "smta") ?: return false
        childTypes(smta, 4).contains("saut")
    }

    /** 把 [parent] 里第一个 [type] 子 box 的内容交给 [transform]；没有就新建一个空的。 */
    private fun rewriteChildren(
        parent: ByteArray, type: String, createIfMissing: Boolean, fullBox: Boolean = false,
        transform: (ByteArray) -> ByteArray,
    ): ByteArray {
        val bb = ByteBuffer.wrap(parent)
        var p = 0
        while (p + 8 <= parent.size) {
            val size = bb.getInt(p).toLong() and 0xffffffffL
            val t = String(parent, p + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || p + size > parent.size) break
            if (t == type) {
                val body = parent.copyOfRange(p + 8, (p + size).toInt())
                return parent.copyOfRange(0, p) + box(type, transform(body)) +
                    parent.copyOfRange((p + size).toInt(), parent.size)
            }
            p += size.toInt()
        }
        if (!createIfMissing) return parent
        val empty = if (fullBox) ByteArray(4) else ByteArray(0)
        return parent + box(type, transform(empty))
    }

    private fun child(parent: ByteArray, start: Int, type: String): ByteArray? {
        val bb = ByteBuffer.wrap(parent)
        var p = start
        while (p + 8 <= parent.size) {
            val size = bb.getInt(p)
            if (size < 8 || p + size > parent.size) return null
            if (String(parent, p + 4, 4, Charsets.ISO_8859_1) == type) return parent.copyOfRange(p + 8, p + size)
            p += size
        }
        return null
    }

    private fun childTypes(parent: ByteArray, start: Int): List<String> {
        val bb = ByteBuffer.wrap(parent)
        val out = mutableListOf<String>()
        var p = start
        while (p + 8 <= parent.size) {
            val size = bb.getInt(p)
            if (size < 8) break
            out += String(parent, p + 4, 4, Charsets.ISO_8859_1)
            p += size
        }
        return out
    }

    private fun atoms(raf: RandomAccessFile, start: Long, end: Long): List<Atom> {
        val out = mutableListOf<Atom>()
        var p = start
        val h = ByteArray(16)
        while (p + 8 <= end) {
            raf.seek(p); raf.readFully(h, 0, 8)
            var size = ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xffffffffL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { raf.readFully(h, 8, 8); size = ByteBuffer.wrap(h, 8, 8).long; header = 16 }
            if (size == 0L) size = end - p
            if (size < header) break
            out += Atom(type, p, size, header)
            p += size
        }
        return out
    }

    private fun read(raf: RandomAccessFile, offset: Long, len: Int): ByteArray {
        raf.seek(offset)
        return ByteArray(len).also { raf.readFully(it) }
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()
}
