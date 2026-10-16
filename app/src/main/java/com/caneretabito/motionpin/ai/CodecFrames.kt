package com.caneretabito.motionpin.ai

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.caneretabito.motionpin.domain.FrameDescriptor
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Three reusable RGB8 buffers total: before, after, inference result. Codec queues supply back pressure. */
internal class StreamingDecoder(
    context: Context, uri: Uri, private val source: ProbedVideo,
    startUs: Long, private val boundary: () -> Unit
) : AutoCloseable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var inputDone = false
    private var outputDone = false
    private var lastPts = Long.MIN_VALUE
    private val info = MediaCodec.BufferInfo()
    var decodedFrames = 0L; private set
    var duplicatePts = 0L; private set

    init {
        try {
            extractor.setDataSource(context, uri, null); extractor.selectTrack(source.track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val format = extractor.getTrackFormat(source.track)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            // Keep native coded orientation; the cache keeps the source rotation metadata.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder; decoder.configure(format, null, null, 0); decoder.start()
        } catch (error: Throwable) { close(); throw error }
    }

    fun next(rgb: ByteBuffer): Long? {
        val decoder = checkNotNull(codec)
        var stalls = 0
        while (!outputDone) {
            boundary()
            if (!inputDone) {
                val input = decoder.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val bytes = checkNotNull(decoder.getInputBuffer(input)); bytes.clear()
                    val count = extractor.readSampleData(bytes, 0)
                    if (count < 0) {
                        decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                    } else {
                        decoder.queueInputBuffer(input, 0, count, extractor.sampleTime, 0); extractor.advance()
                    }
                }
            }
            when (val output = decoder.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> { if (++stalls > 3_000) throw QualityGateException("Decoder stalled for 30 seconds") }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    stalls = 0
                    val actual = decoder.outputFormat
                    val range = actual.intOrNull(MediaFormat.KEY_COLOR_RANGE)
                    val standard = actual.intOrNull(MediaFormat.KEY_COLOR_STANDARD)
                    val transfer = actual.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
                    if (range != source.format.intOrNull(MediaFormat.KEY_COLOR_RANGE) || standard != MediaFormat.COLOR_STANDARD_BT709 || transfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                        throw QualityGateException("Decoder changed or omitted the required source BT.709 SDR range/color contract")
                }
                else -> if (output >= 0) {
                    stalls = 0
                    val pts = info.presentationTimeUs
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    try {
                        outputDone = eos
                        if (info.size == 0 || pts < 0) continue
                        if (pts == lastPts) { duplicatePts++; continue }
                        if (pts < lastPts) throw QualityGateException("Decoder returned non-monotonic presentation PTS; unsupported/corrupt timing")
                        val image = decoder.getOutputImage(output) ?: throw QualityGateException("Decoder did not expose a full-resolution YUV image")
                        image.use { Yuv709.toRgb(it, rgb, source.descriptor) }
                        decodedFrames++; lastPts = pts
                        return pts
                    } finally { decoder.releaseOutputBuffer(output, false) }
                }
            }
        }
        return null
    }

    override fun close() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }; codec = null
        runCatching { extractor.release() }
    }
}

internal data class EncoderPlan(val name: String, val bitrate: Int, val profile: Int, val width: Int, val height: Int) {
    val cacheIdentity: String get() = "$name|video/avc|profile=$profile|$width:$height|60fps|bitrate=$bitrate|VBR|gop=1|maxB=0|YUV420Flexible|BT709|v2"
}

internal class StreamingEncoder(
    file: File, private val descriptor: FrameDescriptor, val plan: EncoderPlan,
    private val boundary: () -> Unit
) : AutoCloseable {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxerStarted = false
    private var outputDone = false
    private var finished = false
    private val info = MediaCodec.BufferInfo()
    var submitted = 0L; private set

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, descriptor.width, descriptor.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, if (descriptor.range == "FULL") MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_PROFILE, plan.profile)
                setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrate)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            val encoder = MediaCodec.createByCodecName(plan.name); codec = encoder
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                it.setOrientationHint(descriptor.rotationDegrees)
            }
        } catch (error: Throwable) { close(); throw error }
    }

    fun put(rgb: ByteBuffer, ptsUs: Long) {
        val encoder = checkNotNull(codec)
        var stalls = 0
        while (true) {
            boundary(); drain(false)
            val input = encoder.dequeueInputBuffer(10_000)
            if (input >= 0) {
                // Capture allocation size before acquiring Image: getInputImage invalidates the
                // earlier ByteBuffer. Padded Image planes may extend past packed w*h*3/2 bytes.
                val allocationBytes = encoder.getInputBuffer(input)?.capacity()
                    ?: throw QualityGateException("Encoder input allocation size is unknown")
                val image = encoder.getInputImage(input) ?: throw QualityGateException("Encoder cannot accept explicit YUV420 image planes")
                image.use { Yuv709.fromRgb(rgb, it, descriptor) }
                encoder.queueInputBuffer(input, 0, allocationBytes, ptsUs, 0)
                submitted++; drain(false); return
            }
            if (++stalls > 3_000) throw QualityGateException("Encoder input stalled")
        }
    }

    fun finish(durationUs: Long) {
        val encoder = checkNotNull(codec)
        var stalls = 0
        while (true) {
            boundary(); drain(false)
            val input = encoder.dequeueInputBuffer(10_000)
            if (input >= 0) { encoder.queueInputBuffer(input, 0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
            if (++stalls > 3_000) throw QualityGateException("Encoder EOS submission stalled")
        }
        while (!outputDone) { boundary(); drain(true) }
        if (!muxerStarted) throw QualityGateException("Encoder produced no playable video track")
        muxer?.stop(); muxerStarted = false; finished = true
    }

    private fun drain(wait: Boolean) {
        val encoder = checkNotNull(codec)
        var stalls = 0
        while (!outputDone) {
            boundary()
            when (val output = encoder.dequeueOutputBuffer(info, if (wait) 10_000 else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!wait) return
                    if (++stalls > 3_000) throw QualityGateException("Encoder failed to drain EOS")
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Encoder format changed after mux started" }
                    val actual = encoder.outputFormat
                    val outputDescriptor = VideoQualityProbe.describe(actual).copy(rotationDegrees = descriptor.rotationDegrees)
                    val contract = com.caneretabito.motionpin.domain.QualityContract(descriptor,
                        precisionValidated = com.caneretabito.motionpin.domain.Support.SUPPORTED)
                    contract.validateOutput(outputDescriptor).requireSupported()
                    if (outputDescriptor.codecProfile != plan.profile) throw QualityGateException("Encoder did not honor AVC High profile")
                    track = checkNotNull(muxer).addTrack(actual); muxer?.start(); muxerStarted = true
                }
                else -> if (output >= 0) {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(muxerStarted)
                            val bytes = checkNotNull(encoder.getOutputBuffer(output)); bytes.position(info.offset); bytes.limit(info.offset + info.size)
                            muxer?.writeSampleData(track, bytes, info)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { encoder.releaseOutputBuffer(output, false) }
                }
            }
        }
    }

    override fun close() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }; codec = null
        muxer?.let { if (muxerStarted && !finished) runCatching { it.stop() }; runCatching { it.release() } }; muxer = null
    }

    companion object {
        fun plan(descriptor: FrameDescriptor, sourceBitrate: Int?): EncoderPlan {
            // A high-quality target, not a promise of lossless recompression. Never silently lower it.
            val required = maxOf(sourceBitrate?.toLong()?.times(2) ?: 0L,
                (descriptor.width.toLong() * descriptor.height * 60 * .22).toLong(), 12_000_000L)
            if (required > Int.MAX_VALUE) throw QualityGateException("Required encoder bitrate exceeds supported integer range")
            val profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            val selected = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { codec ->
                codec.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in codec.supportedTypes && runCatching {
                    val caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) &&
                        caps.profileLevels.any { it.profile == profile } && caps.videoCapabilities.areSizeAndRateSupported(descriptor.width, descriptor.height, 60.0) &&
                        caps.videoCapabilities.bitrateRange.contains(required.toInt()) &&
                        caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }.getOrDefault(false)
            } ?: throw QualityGateException("No encoder supports native ${descriptor.width}×${descriptor.height} AVC High 60fps YUV420 at ${required / 1_000_000} Mbps; no quality fallback")
            return EncoderPlan(selected.name, required.toInt(), profile, descriptor.width, descriptor.height)
        }
    }
}

/** BT.709 non-linear Y'CbCr ↔ R'G'B', keeping full/limited range explicit. No Bitmap or resize. */
internal object Yuv709 {
    private fun requireImage(image: Image, descriptor: FrameDescriptor) {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size != 3 || image.cropRect.width() != descriptor.width || image.cropRect.height() != descriptor.height)
            throw QualityGateException("Codec plane format/crop differs from the required full-resolution 8-bit YUV420 contract")
    }
    fun toRgb(image: Image, rgb: ByteBuffer, descriptor: FrameDescriptor) {
        requireImage(image, descriptor); rgb.clear()
        val crop = image.cropRect; val full = descriptor.range == "FULL"
        val planes = image.planes; val offsets = planes.map { it.buffer.position() }
        fun sample(p: Int, x: Int, y: Int): Int = planes[p].buffer.get(offsets[p] + y * planes[p].rowStride + x * planes[p].pixelStride).toInt() and 255
        for (row in 0 until descriptor.height) for (column in 0 until descriptor.width) {
            val x = column + crop.left; val y = row + crop.top
            val yCode = sample(0, x, y); val uCode = sample(1, x / 2, y / 2); val vCode = sample(2, x / 2, y / 2)
            val packed = rgbFromCodes(yCode, uCode, vCode, full)
            rgb.put((packed ushr 16).toByte()); rgb.put((packed ushr 8).toByte()); rgb.put(packed.toByte())
        }
        rgb.flip()
    }

    fun rgbFromCodes(yCode: Int, uCode: Int, vCode: Int, full: Boolean): Int {
        require(yCode in 0..255 && uCode in 0..255 && vCode in 0..255)
        if (!full && (yCode !in 16..235 || uCode !in 16..240 || vCode !in 16..240))
            throw QualityGateException("Source contains subblack/superwhite or chroma excursions. RGB8 cannot preserve these; a high-precision SDR path is required.")
        val luma = if (full) yCode.toDouble() else (yCode - 16) * (255.0 / 219.0)
        val u = (uCode - 128) * if (full) 1.0 else 255.0 / 224.0
        val v = (vCode - 128) * if (full) 1.0 else 255.0 / 224.0
        val r = luma + 1.5748 * v; val g = luma - .187324 * u - .468124 * v; val b = luma + 1.8556 * u
        if (r < -.5 || g < -.5 || b < -.5 || r > 255.5 || g > 255.5 || b > 255.5)
            throw QualityGateException("Source YUV samples exceed nominal RGB8 gamut. Generation stopped before clipping; high-precision color processing is required.")
        return ((clamp(r).toInt() and 255) shl 16) or ((clamp(g).toInt() and 255) shl 8) or (clamp(b).toInt() and 255)
    }

    fun fromRgb(rgb: ByteBuffer, image: Image, descriptor: FrameDescriptor) {
        requireImage(image, descriptor)
        val planes = image.planes; val offsets = planes.map { it.buffer.position() }; val crop = image.cropRect
        val full = descriptor.range == "FULL"; val width = descriptor.width
        fun put(p: Int, x: Int, y: Int, value: Double) { planes[p].buffer.put(offsets[p] + y * planes[p].rowStride + x * planes[p].pixelStride, clamp(value)) }
        for (y in 0 until descriptor.height step 2) for (x in 0 until width step 2) {
            var sumU = 0.0; var sumV = 0.0
            for (dy in 0..1) for (dx in 0..1) {
                val base = ((y + dy) * width + x + dx) * 3
                val r = rgb.get(base).toInt() and 255; val g = rgb.get(base + 1).toInt() and 255; val b = rgb.get(base + 2).toInt() and 255
                val luma = .2126 * r + .7152 * g + .0722 * b
                put(0, x + dx + crop.left, y + dy + crop.top, if (full) luma else 16 + luma * 219 / 255)
                sumU += (b - luma) / 1.8556; sumV += (r - luma) / 1.5748
            }
            val scale = if (full) 1.0 else 224.0 / 255.0
            put(1, (x + crop.left) / 2, (y + crop.top) / 2, 128 + sumU / 4 * scale)
            put(2, (x + crop.left) / 2, (y + crop.top) / 2, 128 + sumV / 4 * scale)
        }
    }
    private fun clamp(value: Double): Byte = value.roundToInt().coerceIn(0, 255).toByte()
}

internal object SceneCutDetector {
    /** Conservative sampled luma difference plus histogram distance; never counted as inference. */
    fun isCut(first: ByteBuffer, second: ByteBuffer, width: Int, height: Int): Boolean {
        val a = IntArray(32); val b = IntArray(32); var difference = 0L; var samples = 0
        val stepX = maxOf(1, width / 64); val stepY = maxOf(1, height / 64)
        for (y in 0 until height step stepY) for (x in 0 until width step stepX) {
            val i = (y * width + x) * 3
            fun luma(buffer: ByteBuffer): Int = ((buffer.get(i).toInt() and 255) * 54 + (buffer.get(i + 1).toInt() and 255) * 183 + (buffer.get(i + 2).toInt() and 255) * 19) / 256
            val l0 = luma(first); val l1 = luma(second)
            a[l0 / 8]++; b[l1 / 8]++; difference += abs(l0 - l1); samples++
        }
        val meanDifference = difference.toDouble() / samples / 255
        val histogramDistance = a.indices.sumOf { abs(a[it] - b[it]) }.toDouble() / samples / 2
        return meanDifference > .38 || meanDifference > .18 && histogramDistance > .50
    }
}
