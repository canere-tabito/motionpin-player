package com.caneretabito.motionpin.player

import org.junit.Assert.*
import org.junit.Test

class ColorGradeSafetyTest {
    @Test fun confirmedEightBitSdrAllowsManualGrading() {
        assertNull(PlaybackInspection(color = "SDR", bitDepth = 8).colorGradeBlockedReason)
    }

    @Test fun unknownTransferOrDepthNeverEnablesEffects() {
        assertNotNull(PlaybackInspection().colorGradeBlockedReason)
        assertNotNull(PlaybackInspection(color = "SDR").colorGradeBlockedReason)
        assertNotNull(PlaybackInspection(color = "transfer不明（SDRと推定しません）",
            bitDepth = 8).colorGradeBlockedReason)
    }

    @Test fun hdrAndTenBitRemainUnmodified() {
        for (color in listOf("HDR10+", "PQ / HDR10", "HLG", "Dolby Vision（実表示は要検証）")) {
            assertNotNull(PlaybackInspection(color = color, bitDepth = 10).colorGradeBlockedReason)
        }
        assertNotNull(PlaybackInspection(color = "SDR", bitDepth = 10).colorGradeBlockedReason)
        assertNotNull(PlaybackInspection(color = "SDR", bitDepth = 8,
            blockedReason = "Unsupported decoder").colorGradeBlockedReason)
    }
}
