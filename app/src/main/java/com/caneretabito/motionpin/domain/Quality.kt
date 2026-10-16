package com.caneretabito.motionpin.domain

enum class Support { SUPPORTED, UNSUPPORTED, UNVERIFIED }
enum class Transfer { SDR, PQ, HLG, UNKNOWN }
enum class DynamicHdr { NONE, HDR10_PLUS, DOLBY_VISION, UNKNOWN }

data class FrameDescriptor(
    val width: Int,
    val height: Int,
    val ptsUs: Long = 0,
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropRight: Int = width - 1,
    val cropBottom: Int = height - 1,
    val rotationDegrees: Int = 0,
    val pixelFormat: String = "UNKNOWN",
    val bitDepth: Int? = null,
    val primaries: String = "UNKNOWN",
    val transfer: Transfer = Transfer.UNKNOWN,
    val matrix: String = "UNKNOWN",
    val range: String = "UNKNOWN",
    val chroma: String = "UNKNOWN",
    val hdrStaticMetadata: String? = null,
    val dynamicHdr: DynamicHdr = DynamicHdr.UNKNOWN,
    val inferencePrecision: String = "UNVERIFIED",
    val codec: String = "UNKNOWN",
    val codecProfile: Int? = null
) {
    init {
        require(width > 0 && height > 0 && ptsUs >= 0)
        require(cropLeft >= 0 && cropTop >= 0 && cropRight in cropLeft until width && cropBottom in cropTop until height)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(bitDepth == null || bitDepth in 8..32)
    }
    val isHdr: Boolean get() = transfer == Transfer.PQ || transfer == Transfer.HLG || dynamicHdr == DynamicHdr.DOLBY_VISION || dynamicHdr == DynamicHdr.HDR10_PLUS
}

data class QualityDecision(val support: Support, val reason: String) {
    fun requireSupported() { check(support == Support.SUPPORTED) { reason } }
}

/** Unknown color or precision blocks AI. Attribute restoration cannot undo lost image data. */
data class QualityContract(
    val source: FrameDescriptor,
    val modelSupportsHdr: Support = Support.UNVERIFIED,
    val pipelineSupports10Bit: Support = Support.UNVERIFIED,
    val dynamicMetadataRetiming: Support = Support.UNVERIFIED,
    val precisionValidated: Support = Support.UNVERIFIED
) {
    fun evaluateInput(): QualityDecision {
        if (source.bitDepth == null || source.transfer == Transfer.UNKNOWN || source.dynamicHdr == DynamicHdr.UNKNOWN ||
            listOf(source.pixelFormat, source.primaries, source.matrix, source.range, source.chroma).any { it == "UNKNOWN" })
            return QualityDecision(Support.UNVERIFIED, "Source bit depth, pixel format or color metadata is unknown; AI is blocked")
        if (source.dynamicHdr != DynamicHdr.NONE && dynamicMetadataRetiming != Support.SUPPORTED)
            return QualityDecision(dynamicMetadataRetiming, "Dynamic HDR metadata cannot be copied to interpolated timestamps")
        if (source.isHdr && modelSupportsHdr != Support.SUPPORTED)
            return QualityDecision(modelSupportsHdr, "Model HDR suitability has not passed its quality gate")
        if (source.bitDepth > 8 && pipelineSupports10Bit != Support.SUPPORTED)
            return QualityDecision(pipelineSupports10Bit, "High bit depth decode, inference and encode are not validated end to end")
        if (precisionValidated != Support.SUPPORTED)
            return QualityDecision(precisionValidated, "Inference precision has not been validated")
        return QualityDecision(Support.SUPPORTED, "Input matches the declared quality contract")
    }
    fun validateOutput(output: FrameDescriptor): QualityDecision {
        if (output.width != source.width || output.height != source.height ||
            output.cropLeft != source.cropLeft || output.cropTop != source.cropTop ||
            output.cropRight != source.cropRight || output.cropBottom != source.cropBottom || output.rotationDegrees != source.rotationDegrees)
            return QualityDecision(Support.UNSUPPORTED, "Source dimensions, crop or rotation changed")
        if (source.bitDepth == null || output.bitDepth == null)
            return QualityDecision(Support.UNVERIFIED, "Output/source bit depth is unknown")
        if (output.bitDepth < source.bitDepth || output.transfer != source.transfer || output.primaries != source.primaries ||
            output.matrix != source.matrix || output.range != source.range || output.chroma != source.chroma ||
            output.dynamicHdr != source.dynamicHdr || output.hdrStaticMetadata != source.hdrStaticMetadata)
            return QualityDecision(Support.UNSUPPORTED, "Output would downgrade or change source bit depth or color/HDR information")
        if (output.codec == "UNKNOWN" || output.codecProfile == null)
            return QualityDecision(Support.UNVERIFIED, "Actual output codec/profile has not been inspected")
        return evaluateInput()
    }
}

/** Weak metadata is for candidate discovery only. Full SHA-256 is the automatic relink authority. */
object SourceIdentity {
    fun strongMatch(expectedSha256: String?, actualSha256: String?): Boolean =
        expectedSha256 != null && actualSha256 != null &&
            expectedSha256.matches(Regex("[a-fA-F0-9]{64}")) && expectedSha256.equals(actualSha256, true)
}
