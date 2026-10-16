package com.caneretabito.motionpin.data

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SceneReelCodecTest {
    @Test fun sceneReelsRoundtripWithOrderAndOptions() {
        val p1 = UUID.randomUUID().toString()
        val p2 = UUID.randomUUID().toString()
        val reel = SceneReel(name = "Dance best moments", pinIds = listOf(p1, p2), shuffle = true, repeat = false)
        assertEquals(listOf(reel), SceneReelCodec.decode(SceneReelCodec.encode(listOf(reel))))
        assertTrue(SceneReelCodec.SETTINGS_KEY.length < 100)
    }

    @Test fun duplicateScenesAreRejected() {
        val pin = UUID.randomUUID().toString()
        val reel = SceneReel(name = "Duplicate", pinIds = listOf(pin, pin))
        assertThrows(IllegalArgumentException::class.java) { SceneReelCodec.encode(listOf(reel)) }
    }

    @Test fun malformedOrOversizedBackupSettingsAreRejected() {
        assertThrows(Exception::class.java) { SceneReelCodec.decode("{broken") }
        assertThrows(IllegalArgumentException::class.java) {
            SceneReelCodec.decode("x".repeat(SceneReelCodec.MAX_VALUE_CHARS + 1))
        }
    }
}
