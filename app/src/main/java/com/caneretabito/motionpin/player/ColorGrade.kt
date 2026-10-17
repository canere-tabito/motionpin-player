package com.caneretabito.motionpin.player

/**
 * Non-destructive, display-only look for one local video or one pin.
 * Each slider runs from -100 to 100; zero always means the decoded original.
 */
data class ColorGrade(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val temperature: Int = 0,
    val hue: Int = 0,
) {
    fun valid(): Boolean = listOf(brightness, contrast, saturation, temperature, hue)
        .all { it in -100..100 }
    val isOriginal: Boolean get() = this == ORIGINAL

    companion object {
        val ORIGINAL = ColorGrade()
        val VIVID = ColorGrade(brightness = 4, contrast = 18, saturation = 22)
        val DARK_STAGE = ColorGrade(brightness = 24, contrast = -9, saturation = 9)
        val WARM = ColorGrade(brightness = 5, contrast = 8, saturation = 8, temperature = 24)
    }
}

/**
 * Use existing Room settings/JSON backup. No schema migration or write to the media is needed.
 * Compact versioned values are strictly decoded, including negative numbers and bounds.
 */
object ColorGradeSettings {
    fun videoKey(videoId: String): String = "grade.video.$videoId"
    fun pinKey(pinId: String): String = "grade.pin.$pinId"

    fun encode(grade: ColorGrade): String {
        require(grade.valid()) { "Invalid color adjustment" }
        return listOf("v1", grade.brightness, grade.contrast, grade.saturation,
            grade.temperature, grade.hue).joinToString(",")
    }

    fun decode(encoded: String?): ColorGrade? {
        if (encoded == null || encoded.length > 64) return null
        val fields = encoded.split(",")
        if (fields.size != 6 || fields[0] != "v1") return null
        val n = fields.drop(1).map { field ->
            if (!field.matches(Regex("-?(?:0|[1-9][0-9]{0,2})"))) return null
            field.toIntOrNull() ?: return null
        }
        return ColorGrade(n[0], n[1], n[2], n[3], n[4]).takeIf { it.valid() }
    }
}
