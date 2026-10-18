package com.caneretabito.motionpin.player

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment

/**
 * Display-only Media3 GPU effects. They never touch the original encoded media, pin
 * timestamps, AI RIFE cache, resolution selection, MediaStore, or stored video bytes.
 *
 * This pipeline must only be entered for an explicitly eligible SDR input. Do not run
 * these RGB operations on HDR / >8-bit / unknown-color sources and pretend HDR survived.
 * The default empty list keeps the original decode-to-SurfaceView playback route.
 */
@UnstableApi
internal object ColorGradeEffects {
    fun create(grade: ColorGrade): List<Effect> {
        require(grade.valid())
        if (grade.isOriginal) return emptyList()

        val effects = mutableListOf<Effect>()
        // Conservative slider mapping; bounds satisfy Media3's public effect contracts.
        if (grade.brightness != 0) effects.add(Brightness(grade.brightness / 350f))
        if (grade.contrast != 0) effects.add(Contrast(grade.contrast / 250f))
        if (grade.hue != 0 || grade.saturation != 0) {
            effects.add(HslAdjustment.Builder()
                .adjustHue(grade.hue * .35f)
                .adjustSaturation(grade.saturation * .55f)
                .build())
        }
        if (grade.temperature != 0) {
            // Relative channel scaling for warmer/cooler color balance, not a Kelvin claim.
            val change = grade.temperature / 500f
            effects.add(RgbAdjustment.Builder()
                .setRedScale(1f + change)
                .setBlueScale(1f - change)
                .build())
        }
        return effects
    }
}
