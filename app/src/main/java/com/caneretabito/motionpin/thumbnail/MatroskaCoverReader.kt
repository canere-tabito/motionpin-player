package com.caneretabito.motionpin.thumbnail

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Bounded read-only Matroska attachment reader for local SAF-backed seekable files.
 * Some archived videos store their original cover.jpg/png/webp in Matroska Attachments, and Android
 * media thumbnail APIs often return an arbitrary video frame instead.
 *
 * No native tool execution, remote fetch, writes, or complete-file copies.
 * Not a validator of media, provenance or publication status.
 */
internal object MatroskaCoverReader {
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val ATTACHMENTS = 0x1941A469L
    private const val ATTACHED_FILE = 0x61A7L
    private const val FILE_NAME = 0x466EL
    private const val FILE_MIME = 0x4660L
    private const val FILE_DATA = 0x465CL
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val MAX_NODES = 20_000
    private const val MAX_ATTACHMENTS = 128

    /** Return only the original bounded encoded image, never a generated frame. */
    fun originalCover(channel: SeekableByteChannel): ByteArray? = try {
        val reader = Reader(channel)
        val limit = channel.size()
        if (limit < 16) null else reader.readSegments(limit)
    } catch (_: Exception) { null }

    private data class Element(val id: Long, val from: Long, val to: Long, val unknown: Boolean)
    private data class Vint(val value: Long, val unknown: Boolean)
    private class Cover(val position: Long, val size: Int, val name: String, val mime: String, val priority: Int)

    private class Reader(private val input: SeekableByteChannel) {
        private val one = ByteBuffer.allocate(1)
        private var visited = 0

        private fun byte(): Int {
            one.clear()
            if (input.read(one) != 1) throw IllegalStateException("Truncated EBML")
            return one.array()[0].toInt() and 255
        }

        private fun vint(id: Boolean): Vint {
            val first = byte()
            var length = 1
            var marker = 0x80
            val maxLength = if (id) 4 else 8
            while (first and marker == 0) {
                marker = marker ushr 1
                length++
                if (length > maxLength || marker == 0) throw IllegalStateException("Invalid EBML vint")
            }
            var value = (if (id) first else first and (marker - 1)).toLong()
            repeat(length - 1) { value = (value shl 8) or byte().toLong() }
            val allOnes = !id && value == ((1L shl (length * 7)) - 1)
            return Vint(value, allOnes)
        }

        private fun next(parentEnd: Long): Element? {
            if (input.position() >= parentEnd) return null
            if (++visited > MAX_NODES) throw IllegalStateException("Too many MKV elements")
            val id = vint(true)
            val length = vint(false)
            val start = input.position()
            val end = if (length.unknown) parentEnd else Math.addExact(start, length.value)
            if (end > parentEnd || end < start) throw IllegalStateException("Invalid EBML bounds")
            return Element(id.value, start, end, length.unknown)
        }

        fun readSegments(fileEnd: Long): ByteArray? {
            input.position(0)
            var ebmlSeen = false
            repeat(64) {
                val element = next(fileEnd) ?: return null
                when (element.id) {
                    EBML -> ebmlSeen = true
                    SEGMENT -> {
                        if (!ebmlSeen) return null
                        return readSegment(element)
                    }
                }
                input.position(element.to)
            }
            return null
        }

        private fun readSegment(segment: Element): ByteArray? {
            input.position(segment.from)
            var count = 0
            while (input.position() < segment.to && count++ < MAX_NODES) {
                val item = next(segment.to) ?: break
                if (item.id == ATTACHMENTS && !item.unknown) {
                    readAttachments(item)?.let { return it }
                }
                // Unknown-size Cluster cannot be skipped without full parsing.
                if (item.unknown) return null
                input.position(item.to)
            }
            return null
        }

        private fun readAttachments(attachments: Element): ByteArray? {
            val candidates = ArrayList<Cover>(8)
            input.position(attachments.from)
            var count = 0
            while (input.position() < attachments.to && count++ < MAX_ATTACHMENTS) {
                val child = next(attachments.to) ?: break
                if (child.unknown) return null
                if (child.id == ATTACHED_FILE) readAttachedFile(child)?.let(candidates::add)
                input.position(child.to)
            }
            for (candidate in candidates.sortedBy { it.priority }) {
                val contents = try { readImage(candidate) } catch (_: Exception) { null }
                if (contents != null) return contents
            }
            return null
        }

        private fun readAttachedFile(attached: Element): Cover? {
            input.position(attached.from)
            var name: String? = null
            var mime: String? = null
            var dataStart = -1L
            var dataSize = -1L
            var count = 0
            while (input.position() < attached.to && count++ < 64) {
                val element = next(attached.to) ?: break
                if (element.unknown) return null
                val length = element.to - element.from
                when (element.id) {
                    FILE_NAME -> if (length in 1L..180L) name = readText(element, 180)
                    FILE_MIME -> if (length in 1L..80L) mime = readText(element, 80)
                    FILE_DATA -> if (length in 1L..MAX_FILE_BYTES.toLong()) {
                        dataStart = element.from; dataSize = length
                    }
                }
                input.position(element.to)
            }
            val fileName = name ?: return null
            val mediaType = mime?.lowercase(Locale.ROOT) ?: return null
            val lowercase = fileName.lowercase(Locale.ROOT)
            val imageType = when {
                lowercase.endsWith(".jpg") || lowercase.endsWith(".jpeg") -> "image/jpeg"
                lowercase.endsWith(".png") -> "image/png"
                lowercase.endsWith(".webp") -> "image/webp"
                else -> return null
            }
            if (mediaType != imageType || dataStart < 0 || dataSize <= 0) return null
            val base = lowercase.substringBeforeLast('.')
            val priority = when (base) {
                "cover" -> 0
                "poster" -> 1
                "folder", "thumbnail" -> 2
                else -> 3
            }
            return Cover(dataStart, dataSize.toInt(), fileName, mediaType, priority)
        }

        private fun readText(element: Element, limit: Int): String? {
            val length = (element.to - element.from).toInt()
            if (length !in 1..limit) return null
            val bytes = ByteArray(length)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                val count = input.read(buffer)
                if (count <= 0) return null
            }
            val value = String(bytes, StandardCharsets.UTF_8)
            return value.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        }

        private fun readImage(candidate: Cover): ByteArray? {
            input.position(candidate.position)
            val bytes = ByteArray(candidate.size)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) if (input.read(buffer) <= 0) return null
            val valid = when (candidate.mime) {
                "image/png" -> bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
                "image/jpeg" -> bytes.size > 4 && bytes[0] == (-1).toByte() &&
                    bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte()
                "image/webp" -> bytes.size > 12 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP"
                else -> false
            }
            return bytes.takeIf { valid }
        }
    }
}
