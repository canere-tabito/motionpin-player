package com.caneretabito.motionpin.ai

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.caneretabito.motionpin.domain.DynamicHdr
import com.caneretabito.motionpin.domain.FrameDescriptor
import com.caneretabito.motionpin.domain.QualityContract
import com.caneretabito.motionpin.domain.Support
import com.caneretabito.motionpin.domain.Transfer
import java.nio.ByteBuffer
import java.security.MessageDigest

class QualityGateException(message: String) : IllegalStateException(message)

internal fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
internal fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

data class HdrPipelineProbe(
    val p010Decoders: List<String>, val main10Encoders: List<String>,
    val modelGate: Support = Support.UNSUPPORTED,
    val intermediateGate: Support = Support.UNSUPPORTED,
    val staticMetadataGate: Support = Support.UNVERIFIED,
    val dynamicMetadataGate: Support = Support.UNSUPPORTED
) {
    val explanation: String get() = "HDR blocked: model is SDR RGB8; high precision color conversion/inference and metadata retiming are not validated. " +
        "Device P010 decoders=${p010Decoders.joinToString().ifEmpty { "none" }}; HEVC Main10 encoders=${main10Encoders.joinToString().ifEmpty { "none" }}. " +
        "Codec availability alone does not establish HDR preservation."
}

data class ProbedVideo(val track: Int, val format: MediaFormat, val descriptor: FrameDescriptor, val durationUs: Long, val bitrate: Int?)

/** Strict, explicitly tagged BT.709 8-bit 4:2:0 path. Unknown is never inferred to mean SDR. */
object VideoQualityProbe {
    fun requireLocalSeekable(context: Context, uri: Uri) {
        val localAuthorities = setOf("media", "com.android.externalstorage.documents", "com.android.providers.media.documents", "com.android.providers.downloads.documents")
        if (uri.scheme != "content" || uri.authority !in localAuthorities)
            throw QualityGateException("AI needs a verified local provider URI. This provider may be remote; no automatic download or copy is performed.")
        if (uri.authority == "media" && !isLocalMediaVideoPath(uri.pathSegments))
            throw QualityGateException("Cloud Photo Picker/proxy URIs are not a verified local MediaStore video. Select a local video; no provider download is requested.")
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            try { Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_CUR) }
            catch (_: Exception) { throw QualityGateException("Source is not seekable; choose an offline local file.") }
        } ?: throw QualityGateException("Source permission or file is unavailable")
    }

    internal fun isLocalMediaVideoPath(segments: List<String>): Boolean = segments.size == 4 &&
        (segments[0] in setOf("external", "external_primary", "internal") || segments[0].matches(Regex("[a-fA-F0-9]+(?:-[a-fA-F0-9]+)+"))) &&
        segments[1] == "video" && segments[2] == "media" && segments[3].toLongOrNull()?.let { it >= 0 } == true

    fun inspect(extractor: MediaExtractor): ProbedVideo {
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw QualityGateException("No video track")
        val format = extractor.getTrackFormat(track)
        return ProbedVideo(track, format, describe(format), format.longOrNull(MediaFormat.KEY_DURATION)
            ?: throw QualityGateException("Source duration is unknown"), format.intOrNull(MediaFormat.KEY_BIT_RATE))
    }

    fun describe(format: MediaFormat): FrameDescriptor {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "UNKNOWN"
        val width = format.intOrNull(MediaFormat.KEY_WIDTH) ?: throw QualityGateException("Width unknown")
        val height = format.intOrNull(MediaFormat.KEY_HEIGHT) ?: throw QualityGateException("Height unknown")
        val standard = format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)
        val transfer = when (format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)) {
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> Transfer.SDR
            MediaFormat.COLOR_TRANSFER_ST2084 -> Transfer.PQ
            MediaFormat.COLOR_TRANSFER_HLG -> Transfer.HLG
            else -> Transfer.UNKNOWN
        }
        val codecProfile = format.intOrNull(MediaFormat.KEY_PROFILE)
        val avc = if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) parseAvcSps(format.getByteBuffer("csd-0")) else null
        val bitDepth = when {
            avc != null -> avc.bitDepth
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC && codecProfile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> 8
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC && codecProfile in setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) -> 10
            else -> null
        }
        val color = when (standard) {
            MediaFormat.COLOR_STANDARD_BT709 -> "BT709"
            MediaFormat.COLOR_STANDARD_BT2020 -> "BT2020"
            MediaFormat.COLOR_STANDARD_BT601_NTSC -> "BT601_NTSC"
            MediaFormat.COLOR_STANDARD_BT601_PAL -> "BT601_PAL"
            else -> "UNKNOWN"
        }
        val hdr = format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)?.duplicate()?.let { buffer ->
            val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); bytes.joinToString("") { "%02x".format(it) }
        }
        return FrameDescriptor(width = width, height = height,
            cropLeft = format.intOrNull("crop-left") ?: 0, cropTop = format.intOrNull("crop-top") ?: 0,
            cropRight = format.intOrNull("crop-right") ?: (width - 1), cropBottom = format.intOrNull("crop-bottom") ?: (height - 1),
            rotationDegrees = format.intOrNull(MediaFormat.KEY_ROTATION) ?: 0,
            pixelFormat = if (bitDepth == 8) "YUV420_8" else if (bitDepth == 10) "YUV420_10" else "UNKNOWN",
            bitDepth = bitDepth, primaries = color, transfer = transfer, matrix = color,
            range = when (format.intOrNull(MediaFormat.KEY_COLOR_RANGE)) {
                MediaFormat.COLOR_RANGE_LIMITED -> "LIMITED"; MediaFormat.COLOR_RANGE_FULL -> "FULL"; else -> "UNKNOWN"
            },
            chroma = if (avc?.chroma == 1 || mime == MediaFormat.MIMETYPE_VIDEO_HEVC && bitDepth != null) "420" else "UNKNOWN",
            hdrStaticMetadata = hdr,
            dynamicHdr = when {
                mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION -> DynamicHdr.DOLBY_VISION
                format.containsKey("hdr10-plus-info") || codecProfile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> DynamicHdr.HDR10_PLUS
                transfer == Transfer.SDR && hdr == null -> DynamicHdr.NONE
                else -> DynamicHdr.UNKNOWN
            },
            inferencePrecision = "FP32", codec = mime,
            codecProfile = codecProfile ?: avc?.androidProfile)
    }

    fun requireSdr(video: ProbedVideo): QualityContract {
        val descriptor = video.descriptor
        if (descriptor.isHdr || descriptor.bitDepth?.let { it > 8 } == true || descriptor.hdrStaticMetadata != null)
            throw QualityGateException(probeHdr().explanation)
        // FP32 is the reference execution path, not an FP16-versus-FP32 quality claim.
        val contract = QualityContract(descriptor, modelSupportsHdr = Support.UNSUPPORTED,
            pipelineSupports10Bit = Support.UNSUPPORTED, dynamicMetadataRetiming = Support.UNSUPPORTED,
            precisionValidated = Support.SUPPORTED)
        contract.evaluateInput().requireSupported()
        if (descriptor.primaries != "BT709" || descriptor.matrix != "BT709" || descriptor.bitDepth != 8 || descriptor.transfer != Transfer.SDR || descriptor.chroma != "420")
            throw QualityGateException("Only explicitly known BT.709 SDR 8-bit 4:2:0 is supported. No color/bit-depth fallback is applied.")
        if (descriptor.width % 2 != 0 || descriptor.height % 2 != 0 || descriptor.cropLeft != 0 || descriptor.cropTop != 0 ||
            descriptor.cropRight != descriptor.width - 1 || descriptor.cropBottom != descriptor.height - 1)
            throw QualityGateException("This crop/odd-size layout cannot be preserved by the current encoder path")
        return contract
    }

    fun probeHdr(): HdrPipelineProbe {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val p010 = mutableListOf<String>(); val main10 = mutableListOf<String>()
        codecs.forEach { codec -> codec.supportedTypes.filter { it.startsWith("video/") }.forEach { mime ->
            runCatching { codec.getCapabilitiesForType(mime) }.getOrNull()?.let { caps ->
                if (!codec.isEncoder && Build.VERSION.SDK_INT >= 31 && caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)) p010 += codec.name
                if (codec.isEncoder && mime == MediaFormat.MIMETYPE_VIDEO_HEVC && caps.profileLevels.any { it.profile in setOf(
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) }) main10 += codec.name
            }
        } }
        return HdrPipelineProbe(p010.distinct(), main10.distinct())
    }

    fun sha256(context: Context, uri: Uri, boundary: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val chunk = ByteArray(1024 * 1024)
            while (true) { boundary(); val count = stream.read(chunk); if (count < 0) break; digest.update(chunk, 0, count) }
        } ?: throw QualityGateException("Cannot read source for strong fingerprint")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal data class AvcInfo(val bitDepth: Int, val chroma: Int, val androidProfile: Int?)
    internal fun parseAvcSps(csd: ByteBuffer?): AvcInfo? = runCatching {
        if (csd == null) return null
        val buffer = csd.duplicate(); val data = ByteArray(buffer.remaining()); buffer.get(data)
        var header = -1
        for (i in 0 until data.size - 4) {
            val startLength = when {
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() -> 3
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (startLength > 0 && (data[i + startLength].toInt() and 31) == 7) { header = i + startLength; break }
        }
        if (header < 0) return null
        val rbsp = ArrayList<Byte>(); var zeros = 0
        for (i in header + 1 until data.size) {
            val value = data[i].toInt() and 255
            if (zeros >= 2 && value == 3) { zeros = 0; continue }
            rbsp += data[i]; zeros = if (value == 0) zeros + 1 else 0
        }
        val bits = Bits(rbsp.toByteArray()); val profile = bits.read(8); bits.read(16); bits.ue()
        var chroma = 1; var depth = 8
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = bits.ue(); if (chroma == 3) bits.read(1)
            val lumaDepth = bits.ue() + 8; val chromaDepth = bits.ue() + 8
            if (lumaDepth != chromaDepth) return null
            depth = lumaDepth
        } else if (profile !in setOf(66, 77, 88)) return null
        AvcInfo(depth, chroma, when (profile) {
            66 -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            77 -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
            88 -> MediaCodecInfo.CodecProfileLevel.AVCProfileExtended
            100 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            else -> null
        })
    }.getOrNull()

    private class Bits(val data: ByteArray) {
        private var position = 0
        fun read(count: Int): Int { require(count in 0..31 && position + count <= data.size * 8); var result = 0
            repeat(count) { result = (result shl 1) or ((data[position / 8].toInt() ushr (7 - position % 8)) and 1); position++ }; return result }
        fun ue(): Int { var count = 0; while (read(1) == 0) { count++; require(count < 30) }; return (1 shl count) - 1 + read(count) }
    }
}
