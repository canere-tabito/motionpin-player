package com.caneretabito.motionpin.domain

import org.junit.Assert.*
import org.junit.Test

class QualityTest {
    private fun sdr() = FrameDescriptor(3840, 2160, pixelFormat = "YUV420", bitDepth = 8,
        primaries = "BT709", transfer = Transfer.SDR, matrix = "BT709", range = "LIMITED", chroma = "420",
        dynamicHdr = DynamicHdr.NONE, inferencePrecision = "fp32", codec = "video/avc", codecProfile = 8)

    @Test fun unknownIsNeverAssumedSdrOrEightBit() {
        val input = FrameDescriptor(3840, 2160)
        assertEquals(Support.UNVERIFIED, QualityContract(input).evaluateInput().support)
        assertThrows(IllegalStateException::class.java) { QualityContract(input).evaluateInput().requireSupported() }
    }

    @Test fun sdrRequiresValidatedPrecisionAndPreservedDimensions() {
        val contract = QualityContract(sdr(), precisionValidated = Support.SUPPORTED)
        assertEquals(Support.SUPPORTED, contract.validateOutput(sdr()).support)
        assertEquals(Support.UNSUPPORTED, contract.validateOutput(sdr().copy(width = 1920, height = 1080, cropRight = 1919, cropBottom = 1079)).support)
        assertEquals(Support.UNVERIFIED, QualityContract(sdr()).evaluateInput().support)
    }

    @Test fun hdrCannotBeConvertedToEightBitOrSdrByRestoringTags() {
        val hdr = sdr().copy(bitDepth = 10, transfer = Transfer.PQ, primaries = "BT2020", matrix = "BT2020", hdrStaticMetadata = "mastering-display")
        val contract = QualityContract(hdr, Support.SUPPORTED, Support.SUPPORTED, precisionValidated = Support.SUPPORTED)
        assertEquals(Support.SUPPORTED, contract.validateOutput(hdr).support)
        assertEquals(Support.UNSUPPORTED, contract.validateOutput(hdr.copy(bitDepth = 8)).support)
        assertEquals(Support.UNSUPPORTED, contract.validateOutput(hdr.copy(transfer = Transfer.SDR)).support)
        assertEquals(Support.UNVERIFIED, contract.copy(modelSupportsHdr = Support.UNVERIFIED).evaluateInput().support)
        assertEquals(Support.UNSUPPORTED, contract.copy(pipelineSupports10Bit = Support.UNSUPPORTED).evaluateInput().support)
    }

    @Test fun dynamicHdrRequiresItsOwnRetimingGate() {
        val hdr = sdr().copy(bitDepth = 10, transfer = Transfer.PQ, dynamicHdr = DynamicHdr.HDR10_PLUS)
        val contract = QualityContract(hdr, Support.SUPPORTED, Support.SUPPORTED, precisionValidated = Support.SUPPORTED)
        assertEquals(Support.UNVERIFIED, contract.evaluateInput().support)
        assertEquals(Support.UNSUPPORTED, contract.validateOutput(hdr.copy(dynamicHdr = DynamicHdr.NONE)).support)
    }

    @Test fun sameFilenameCannotProveStrongIdentity() {
        assertFalse(SourceIdentity.strongMatch(null, null))
        assertFalse(SourceIdentity.strongMatch("dance.mp4", "dance.mp4"))
        assertFalse(SourceIdentity.strongMatch("a".repeat(64), "b".repeat(64)))
        assertTrue(SourceIdentity.strongMatch("a".repeat(64), "A".repeat(64)))
    }
}
