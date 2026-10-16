package com.caneretabito.motionpin.ai

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class NativeRifeException(message: String) : RuntimeException(message)

/** Synchronous, single-engine native boundary. Call on a background dispatcher.
 * Only tightly packed, nonlinear SDR RGB8 is accepted. Format gating belongs to
 * QualityContract before any decoder conversion; this API makes no HDR claim.
 */
class NativeRife(private val context: Context) : AutoCloseable {
    data class Capabilities(
        val gpuName: String,
        val backend: String = "ncnn Vulkan",
        val precision: String = "FP32",
        val pixelFormat: String = "RGB8",
        val arbitraryTimestep: Boolean = true,
        val hdrSupported: Boolean = false,
    )

    private var handle = 0L
    private var capabilities: Capabilities? = null

    @Synchronized
    fun open(): Capabilities {
        capabilities?.let { return it }
        try { System.loadLibrary("motionpin_rife") }
        catch (error: UnsatisfiedLinkError) {
            throw NativeRifeException("Native AI library is unavailable on this device: ${error.message}")
        }
        val directory = synchronized(modelLock) { extractVerifiedModel() }
        handle = nativeCreate(directory.absolutePath)
        if (handle == 0L) throw NativeRifeException("RIFE model initialization returned no engine")
        try {
            return Capabilities(nativeDeviceName(handle)).also { capabilities = it }
        } catch (error: Throwable) {
            nativeDestroy(handle)
            handle = 0
            throw error
        }
    }

    /** Native-owned memory released deterministically by close(). The returned
     * buffer must be used only within this engine's open/close scope; do not
     * retain or read aliases after close. At most three frames may be allocated.
     */
    @Synchronized
    fun allocateFrameBuffer(bytes: Int): ByteBuffer {
        check(handle != 0L) { "Call NativeRife.open() before allocation" }
        require(bytes > 0)
        return nativeAllocateFrameBuffer(handle, bytes).order(ByteOrder.nativeOrder())
    }

    @Synchronized
    fun interpolate(first: ByteBuffer, second: ByteBuffer, output: ByteBuffer,
                    width: Int, height: Int, timestep: Float) {
        check(handle != 0L) { "Call NativeRife.open() before interpolation" }
        require(width in 1..16384 && height in 1..16384 && timestep.isFinite() && timestep in 0f..1f)
        val bytes = width.toLong() * height * 3
        require(listOf(first, second, output).all { it.isDirect && it.capacity().toLong() >= bytes && it.position() == 0 }) {
            "Expected direct, tightly packed RGB8 buffers at position zero"
        }
        require(!output.isReadOnly && output !== first && output !== second)
        nativeInterpolate(handle, first, second, output, width, height, timestep)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
        capabilities = null
    }

    private fun extractVerifiedModel(): File {
        val directory = File(context.noBackupFilesDir, "models/$MODEL_ID")
        if (!directory.isDirectory && !directory.mkdirs()) throw NativeRifeException("Cannot create private model directory")
        for ((name, expectedHash) in modelHashes) {
            val target = File(directory, name)
            if (target.isFile && sha256(target) == expectedHash) continue
            val temporary = File.createTempFile("model-", ".partial", directory)
            try {
                context.assets.open("models/rife-v4.6/$name").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
                }
                if (sha256(temporary) != expectedHash) throw NativeRifeException("Bundled RIFE model checksum failed: $name")
                if (!temporary.renameTo(target)) throw NativeRifeException("Cannot atomically install verified RIFE model")
            } finally { temporary.delete() }
        }
        return directory
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val block = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private external fun nativeCreate(modelDirectory: String): Long
    private external fun nativeDeviceName(handle: Long): String
    private external fun nativeAllocateFrameBuffer(handle: Long, bytes: Int): ByteBuffer
    private external fun nativeInterpolate(handle: Long, first: ByteBuffer, second: ByteBuffer,
        output: ByteBuffer, width: Int, height: Int, timestep: Float)
    private external fun nativeDestroy(handle: Long)

    companion object {
        const val MODEL_ID = "rife-v4.6-f334ed226014"
        const val ENGINE_ID = "motionpin-rife-v1-ncnn-b4ba207c-fp32"
        const val MODEL_HASH = "e3990911821fc917c0ca10badb81afdaa1f8bea8794497728f32320ba8b7381e"
        const val ENGINE_HASH = "61f6bfcba88e984e33b878a47ac3e22326d034aa12f51ed284ae27e6acc56f4b"
        private val modelLock = Any()
        private val modelHashes = mapOf(
            "flownet.param" to "724569596bcd1e7b9fa50455c604777ebed99746d2ef40aa86e31b5725f1053c",
            "flownet.bin" to "f334ed2260149ce0188a6dcf049844e8b0cdd912e01cbcfb63553157d2508958",
        )
    }
}
