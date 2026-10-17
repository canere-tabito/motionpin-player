package com.caneretabito.motionpin.thumbnail

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only tiny synthetic EBML buffers; no user media and no Android decoder required. */
class MatroskaCoverReaderTest {
    private fun element(id: ByteArray, bytes: ByteArray): ByteArray {
        require(bytes.size in 0..126)
        return id + byteArrayOf((0x80 or bytes.size).toByte()) + bytes
    }

    private fun attachment(name: String, mime: String, bytes: ByteArray): ByteArray =
        element(byteArrayOf(0x61, 0xA7.toByte()),
            element(byteArrayOf(0x46, 0x6E), name.toByteArray(Charsets.UTF_8)) +
            element(byteArrayOf(0x46, 0x60), mime.toByteArray(Charsets.UTF_8)) +
            element(byteArrayOf(0x46, 0x5C), bytes))

    private fun mkv(vararg attachments: ByteArray): ByteArray {
        val header = element(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), byteArrayOf(0x42))
        val section = element(byteArrayOf(0x19, 0x41, 0xA4.toByte(), 0x69),
            attachments.fold(byteArrayOf()) { buffer, entry -> buffer + entry })
        return header + element(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67), section)
    }

    private fun read(bytes: ByteArray): ByteArray? {
        val file = File.createTempFile("motionpin-cover-", ".mkv")
        try {
            file.writeBytes(bytes)
            return RandomAccessFile(file, "r").use { MatroskaCoverReader.originalCover(it.channel) }
        } finally {
            file.delete()
        }
    }

    @Test fun attachedCoverWinsEvenIfOtherImageOccursEarlier() {
        val logo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x11, 0x00)
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x22, 0x00)
        assertArrayEquals(cover, read(mkv(
            attachment("logo.jpg", "image/jpeg", logo),
            attachment("cover.jpg", "image/jpeg", cover))))
    }

    @Test fun mismatchedMimeAndInvalidContentAreNotTreatedAsCovers() {
        val fake = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x22, 0x00)
        assertNull(read(mkv(attachment("cover.jpg", "image/png", fake))))
        assertNull(read(mkv(attachment("cover.jpg", "image/jpeg", byteArrayOf(1, 2, 3, 4, 5)))))
    }

    @Test fun unrelatedVideoBytesAreRejected() {
        assertNull(read(ByteArray(128) { 0x33 }))
    }
}
