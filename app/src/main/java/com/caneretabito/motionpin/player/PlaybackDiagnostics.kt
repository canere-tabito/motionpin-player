package com.caneretabito.motionpin.player

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.Display

data class PlaybackInspection(
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val fps: Double? = null,
    val mime: String = "不明",
    val profile: Int? = null,
    val level: Int? = null,
    val bitDepth: Int? = null,
    val color: String = "未確認",
    val bitrate: Int? = null,
    val candidates: List<String> = emptyList(),
    val displayRefresh: Float = 0f,
    val displayHdr: String = "未確認",
    val blockedReason: String? = null,
    val notes: String = "能力照会は実測の代わりではありません。",
) {
    val aspect: Float get() = if (width <= 0 || height <= 0) 16f / 9f else if (rotation % 180 != 0) height.toFloat() / width else width.toFloat() / height
    val is4K: Boolean get() = maxOf(width, height) >= 3840
}

object PlaybackDiagnostics {
    fun inspect(context: Context, uri: Uri, display: Display?): PlaybackInspection {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return PlaybackInspection(blockedReason = "このファイルに動画トラックがありません。")
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "不明"
            fun int(key: String): Int? = if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull() else null
            val width = int(MediaFormat.KEY_WIDTH) ?: 0
            val height = int(MediaFormat.KEY_HEIGHT) ?: 0
            val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                runCatching { format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() }.getOrNull() else null
            val profile = int(MediaFormat.KEY_PROFILE)
            val transfer = int(MediaFormat.KEY_COLOR_TRANSFER)
            val hdrPq = transfer == MediaFormat.COLOR_TRANSFER_ST2084
            val hdrHlg = transfer == MediaFormat.COLOR_TRANSFER_HLG
            val dolby = mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION
            val hdr10Plus = profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            val hdrTypes = display?.hdrCapabilities?.supportedHdrTypes?.toSet()
            val requiredHdr = when {
                dolby -> Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
                hdr10Plus -> Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
                hdrPq -> Display.HdrCapabilities.HDR_TYPE_HDR10
                hdrHlg -> Display.HdrCapabilities.HDR_TYPE_HLG
                else -> null
            }
            val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
            val suitable = codecInfos.filter { codec ->
                runCatching { codec.getCapabilitiesForType(mime).isFormatSupported(format) }.getOrDefault(false)
            }
            val reason = when {
                requiredHdr != null && hdrTypes != null && requiredHdr !in hdrTypes -> "この画面は入力のHDR形式に対応していません。無断のSDR変換を避けるため再生を停止しました。"
                requiredHdr != null && suitable.isEmpty() -> "入力HDRのprofile・解像度に対応するdecoderを確認できません。画質を変更せず停止しました。"
                codecInfos.isEmpty() -> "この端末に $mime 用のdecoderがありません。"
                else -> null
            }
            val depth = when {
                hdrPq || hdrHlg || profile in setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) -> 10
                else -> null
            }
            val color = when {
                dolby -> "Dolby Vision（実表示は要検証）"
                hdr10Plus -> "HDR10+"
                hdrPq -> "PQ / HDR10"
                hdrHlg -> "HLG"
                transfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
                else -> "transfer不明（SDRと推定しません）"
            }
            return PlaybackInspection(width, height, int(MediaFormat.KEY_ROTATION) ?: 0, fps, mime,
                profile, int(MediaFormat.KEY_LEVEL), depth, color, int(MediaFormat.KEY_BIT_RATE),
                suitable.map { "${it.name} · ${if (it.isHardwareAccelerated) "HW" else "SW"}" },
                display?.mode?.refreshRate ?: 0f,
                hdrTypes?.joinToString { when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> it.toString() } }?.ifBlank { "HDR非対応" } ?: "未確認",
                reason,
                "OS・画面による表示変換は完全には制御できません。59.94fps等の入力レートと実表示の継続性能は実測が必要です。")
        } finally {
            extractor.release()
        }
    }
}
