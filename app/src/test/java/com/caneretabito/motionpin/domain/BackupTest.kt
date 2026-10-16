package com.caneretabito.motionpin.domain

import com.caneretabito.motionpin.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    private val video = VideoEntity(uri = "content://media/external/video/media/12", displayName = "dance.mp4", durationUs = 10_000_000,
        width = 3840, height = 2160, fingerprintSha256 = "a".repeat(64))
    private val pin = PinEntity(videoId = video.id, startUs = 123_456, endUs = 5_000_000, speedNumerator = 1, speedDenominator = 4,
        zoomScale = 2f, zoomCenterX = .3f, zoomCenterY = .8f, aiJobId = "private-cache-reference", tagsJson = "[\"dance\"]")
    private val channel = ChannelEntity(name = "Favorites")
    private fun snapshot() = BackupCodec.Snapshot(listOf(video), listOf(pin), listOf(channel), listOf(ChannelVideoEntity(channel.id, video.id)), listOf(SettingEntity("sort", "recent")))

    @Test fun backupRoundTripPreservesSourceTimeZoomAndCollectionsButNotCache() {
        val bytes = BackupCodec.encode(snapshot())
        val decoded = BackupCodec.decode(bytes)
        assertTrue(BackupCodec.sameVideoIdentity(video, decoded.videos.single()))
        assertTrue(BackupCodec.samePin(pin, decoded.pins.single()))
        assertEquals(pin.startUs, decoded.pins.single().startUs)
        assertEquals(pin.zoom(), decoded.pins.single().zoom())
        assertNull(decoded.pins.single().aiJobId)
        assertTrue(decoded.videos.single().needsRelink)
        assertEquals(channel, decoded.channels.single())
        assertFalse(bytes.toString(Charsets.UTF_8).contains("private-cache-reference"))
    }

    @Test fun repeatedBackupKeepsLogicalIdentityAndDetectsUuidCollision() {
        val first = BackupCodec.decode(BackupCodec.encode(snapshot()))
        val second = BackupCodec.decode(BackupCodec.encode(first))
        assertTrue(BackupCodec.sameVideoIdentity(first.videos.single(), second.videos.single()))
        assertTrue(BackupCodec.samePin(first.pins.single(), second.pins.single()))
        assertFalse(BackupCodec.sameVideoIdentity(video, video.copy(fingerprintSha256 = "b".repeat(64))))
        assertFalse(BackupCodec.samePin(pin, pin.copy(startUs = pin.startUs + 1)))
    }

    @Test fun invalidVersionAndOutOfRangePinAreRejectedBeforeDatabaseMutation() {
        val json = JSONObject(BackupCodec.encode(snapshot()).toString(Charsets.UTF_8))
        json.put("version", 999)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.toString().toByteArray()) }
        json.put("version", 1)
        json.getJSONArray("pins").getJSONObject(0).put("endUs", 20_000_000)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.toString().toByteArray()) }
    }

    @Test fun duplicateUuidAndFloatingPointTimestampAreRejected() {
        val json = JSONObject(BackupCodec.encode(snapshot()).toString(Charsets.UTF_8))
        val videos = json.getJSONArray("videos")
        videos.put(videos.getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.toString().toByteArray()) }
        val other = JSONObject(BackupCodec.encode(snapshot()).toString(Charsets.UTF_8))
        other.getJSONArray("pins").getJSONObject(0).put("startUs", 1.5)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(other.toString().toByteArray()) }
    }

    @Test fun oversizedAndDeeplyNestedInputAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(ByteArray(BackupCodec.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(("[".repeat(100) + "]".repeat(100)).toByteArray()) }
    }
}
