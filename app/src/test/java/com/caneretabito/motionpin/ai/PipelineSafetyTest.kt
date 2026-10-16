package com.caneretabito.motionpin.ai

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineSafetyTest {
    @Test fun avcSpsReadsActualBitDepthRatherThanAssumingHighMeansEightBit() {
        val eight = sps(100, 0xAC)
        val ten = sps(110, 0xA6, 0xC0)
        assertEquals(8, VideoQualityProbe.parseAvcSps(eight)?.bitDepth)
        assertEquals(10, VideoQualityProbe.parseAvcSps(ten)?.bitDepth)
        assertEquals(1, VideoQualityProbe.parseAvcSps(ten)?.chroma)
    }

    @Test fun missingMalformedOrUnknownSpsNeverBecomesSdrEightBit() {
        assertNull(VideoQualityProbe.parseAvcSps(null))
        assertNull(VideoQualityProbe.parseAvcSps(ByteBuffer.wrap(byteArrayOf(0, 0, 1, 0x67))))
        assertNull(VideoQualityProbe.parseAvcSps(sps(255, 0x80)))
    }

    @Test fun cutDetectorHoldsAcrossLargeSceneChangeButNotIdenticalFrames() {
        val dark = ByteBuffer.allocateDirect(64 * 64 * 3)
        val bright = ByteBuffer.allocateDirect(64 * 64 * 3).apply { repeat(capacity()) { put(it, 240.toByte()) } }
        assertFalse(SceneCutDetector.isCut(dark, dark, 64, 64))
        assertTrue(SceneCutDetector.isCut(dark, bright, 64, 64))
    }

    @Test fun limitedAndFullRangeNeutralEndpointsRemainDistinctContracts() {
        assertEquals(0, Yuv709.rgbFromCodes(16, 128, 128, false))
        assertEquals(0xFFFFFF, Yuv709.rgbFromCodes(235, 128, 128, false))
        assertEquals(0x101010, Yuv709.rgbFromCodes(16, 128, 128, true))
        assertEquals(0xEBEBEB, Yuv709.rgbFromCodes(235, 128, 128, true))
    }

    @Test(expected = QualityGateException::class)
    fun superwhiteIsBlockedBeforeRgbClipping() { Yuv709.rgbFromCodes(236, 128, 128, false) }

    @Test(expected = QualityGateException::class)
    fun nominalYuvOutsideRgbGamutIsBlockedBeforeClipping() { Yuv709.rgbFromCodes(16, 128, 240, false) }

    @Test fun mediaAuthorityAloneDoesNotAuthorizeCloudPickerRead() {
        assertTrue(VideoQualityProbe.isLocalMediaVideoPath(listOf("external", "video", "media", "42")))
        assertTrue(VideoQualityProbe.isLocalMediaVideoPath(listOf("external_primary", "video", "media", "42")))
        assertFalse(VideoQualityProbe.isLocalMediaVideoPath(listOf("picker", "0", "cloud.provider", "media", "42")))
        assertFalse(VideoQualityProbe.isLocalMediaVideoPath(listOf("picker", "video", "media", "42")))
        assertFalse(VideoQualityProbe.isLocalMediaVideoPath(listOf("cloud", "video", "media", "42")))
        assertFalse(VideoQualityProbe.isLocalMediaVideoPath(listOf("external", "video", "media", "unknown")))
    }

    private fun sps(profile: Int, vararg suffix: Int): ByteBuffer = ByteBuffer.wrap(
        byteArrayOf(0, 0, 0, 1, 0x67, profile.toByte(), 0, 40) + suffix.map { it.toByte() }.toByteArray())
}
