package com.caneretabito.motionpin.player

import org.junit.Assert.*
import org.junit.Test

class ColorGradeTest {
    @Test fun originalIsAnExactNeutralIdentity() {
        assertTrue(ColorGrade.ORIGINAL.valid())
        assertTrue(ColorGrade.ORIGINAL.isOriginal)
        assertEquals("v1,0,0,0,0,0", ColorGradeSettings.encode(ColorGrade.ORIGINAL))
        assertEquals(ColorGrade.ORIGINAL, ColorGradeSettings.decode("v1,0,0,0,0,0"))
    }

    @Test fun allFiveSlidersRoundTripIncludingNegatives() {
        val expected = ColorGrade(brightness = -72, contrast = 33, saturation = -10,
            temperature = 100, hue = -100)
        assertEquals(expected, ColorGradeSettings.decode(ColorGradeSettings.encode(expected)))
        assertEquals(ColorGrade.VIVID,
            ColorGradeSettings.decode(ColorGradeSettings.encode(ColorGrade.VIVID)))
        assertEquals(ColorGrade.DARK_STAGE,
            ColorGradeSettings.decode(ColorGradeSettings.encode(ColorGrade.DARK_STAGE)))
    }

    @Test fun invalidOrFutureSettingsMustFailClosed() {
        val invalid = listOf(null, "", "v2,0,0,0,0,0", "v1,0,0,0,0", "v1,101,0,0,0,0",
            "v1,-101,0,0,0,0", "v1,NaN,0,0,0,0", "v1,0,0,0,0,0,0",
            "v1,0,0,0,0,0\n", "v1,0,0,0,0,99999999999999999")
        invalid.forEach { assertNull("Incorrectly accepted: $it", ColorGradeSettings.decode(it)) }
        assertThrows(IllegalArgumentException::class.java) {
            ColorGradeSettings.encode(ColorGrade(brightness = 101))
        }
    }

    @Test fun pinOverridesVideoAndWithoutOverrideFallsBackSafely() {
        val video = ColorGradeSettings.encode(ColorGrade.WARM)
        val pin = ColorGradeSettings.encode(ColorGrade.VIVID)
        assertEquals(ColorGrade.VIVID, ColorGradeSettings.resolve(video, pin))
        assertEquals(ColorGrade.WARM, ColorGradeSettings.resolve(video, null))
        assertEquals(ColorGrade.WARM, ColorGradeSettings.resolve(video, "bad"))
        assertEquals(ColorGrade.ORIGINAL, ColorGradeSettings.resolve(null, null))
        assertEquals("grade.video.video-uuid", ColorGradeSettings.videoKey("video-uuid"))
        assertEquals("grade.pin.pin-uuid", ColorGradeSettings.pinKey("pin-uuid"))
    }
}
