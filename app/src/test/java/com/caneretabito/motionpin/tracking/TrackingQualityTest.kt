package com.caneretabito.motionpin.tracking

import com.caneretabito.motionpin.player.VideoViewport
import org.junit.Assert.*
import org.junit.Test

class TrackingQualityTest {
    private fun person(x: Float, y: Float = .2f, color: Float = .45f,
                       w: Float = .18f, h: Float = .55f): PersonBox =
        PersonBox(x, y, x + w, y + h, .91f, List(36) { color })

    @Test fun smoothSoloAndCameraMovementStaysOnSelectedIdentity() {
        val initial = person(.2f)
        val match = StablePersonMatcher(initial, 0)
        assertEquals(MatchStatus.MATCHED, match.step(250_000, listOf(person(.25f))).status)
        assertEquals(MatchStatus.MATCHED, match.step(500_000, listOf(person(.31f))).status)
        assertEquals(MatchStatus.MATCHED, match.step(750_000, listOf(person(.38f))).status)
    }

    @Test fun closeIdenticallyDressedDancersRemainAmbiguous() {
        val match = StablePersonMatcher(person(.2f), 0)
        assertEquals(MatchStatus.AMBIGUOUS,
            match.step(250_000, listOf(person(.21f), person(.212f))).status)
        assertEquals(MatchStatus.MATCHED, match.step(500_000, listOf(person(.23f))).status)
    }

    @Test fun failedOcclusionNeverReassignsToOtherDancer() {
        val match = StablePersonMatcher(person(.2f), 0)
        assertEquals(MatchStatus.MISSING, match.step(250_000, emptyList()).status)
        assertEquals(MatchStatus.MISSING, match.step(500_000, emptyList()).status)
        assertEquals(MatchStatus.LOST, match.step(750_000, listOf(person(.95f, w = .03f))).status)
        assertEquals(MatchStatus.LOST, match.step(1_000_000, listOf(person(.2f))).status)
    }

    @Test fun mismatchedCostumeIsNotAValidRecovery() {
        val match = StablePersonMatcher(person(.2f, color = .14f), 0)
        assertEquals(MatchStatus.MISSING,
            match.step(250_000, listOf(person(.21f, color = .95f))).status)
    }

    @Test fun detectedCrossingIsRejectedInsteadOfSilentSwitch() {
        val match = StablePersonMatcher(person(.2f), 0)
        assertEquals(MatchStatus.MATCHED,
            match.step(250_000, listOf(person(.22f), person(.66f))).status)
        assertEquals(MatchStatus.AMBIGUOUS,
            match.step(500_000, listOf(person(.24f), person(.242f))).status)
    }

    @Test fun invalidAndOutOfFrameBoxesCannotBeAssociated() {
        val start = person(.2f)
        val bad = PersonBox(-.1f, .2f, .15f, .8f, .9f, List(36) { .4f })
        assertFalse(bad.valid())
        assertNull(PersonAssociation.choose(start, listOf(bad)))
    }

    @Test fun timeInterpolationDoesNotInventPositionsAcrossOcclusions() {
        val keys = listOf(TrackingKeyframe(0L, person(.2f)),
            TrackingKeyframe(250_000L, person(.3f)),
            TrackingKeyframe(1_500_000L, person(.6f)))
        assertEquals(.25f, TrackingTrajectory.at(keys, 125_000L)!!.left, .0001f)
        assertNull(TrackingTrajectory.at(keys, 900_000L))
        assertEquals(.6f, TrackingTrajectory.at(keys, 1_500_000L)!!.left, .0001f)
        assertNull(TrackingTrajectory.at(keys, 1_800_001L))
        assertNull(TrackingTrajectory.at(keys, -1))
    }

    @Test fun cameraUsesSmoothCropButSnapsAfterSeek() {
        val camera = SmoothFollowCamera()
        val start = camera.follow(0, person(.2f), VideoViewport(),
            DancerFraming.FULL)
        assertEquals(.29f, start.centerX, .0001f)
        assertTrue(start.zoom in 1f..1.65f) // full-body visibility takes priority over minimum zoom
        val easing = camera.follow(200_000, person(.55f), start, DancerFraming.FULL)
        assertTrue(easing.centerX > start.centerX)
        assertTrue(easing.centerX < .64f)
        val jump = camera.follow(2_000_000, person(.65f), easing, DancerFraming.FULL)
        assertEquals(.74f, jump.centerX, .0001f)
    }

    @Test fun fullBodyPresetDoesNotCropTallDancer() {
        val tall = person(.25f, y = .05f, w = .3f, h = .90f)
        val cam = SmoothFollowCamera()
        cam.setUserZoom(3f)
        val result = cam.follow(0, tall, VideoViewport(zoom = 3f), DancerFraming.FULL)
        assertEquals(1f, result.zoom, .0001f)
    }

    @Test fun upperFramingCanZoomWithoutMovingWrongDancer() {
        val p = person(.25f, y = .15f)
        val cam = SmoothFollowCamera()
        val full = cam.follow(0L, p, VideoViewport(), DancerFraming.FULL)
        val focus = cam.follow(250_000L, p, full, DancerFraming.UPPER)
        assertTrue(focus.centerY < full.centerY)
        assertTrue(focus.zoom > full.zoom)
    }

    @Test fun framingTargetsDifferentPartsOfTheSamePerson() {
        val body = person(.2f, y = .1f)
        val full = SmoothFollowCamera().follow(0L, body, VideoViewport(), DancerFraming.FULL)
        val upper = SmoothFollowCamera().follow(0L, body, VideoViewport(), DancerFraming.UPPER)
        val lower = SmoothFollowCamera().follow(0L, body, VideoViewport(), DancerFraming.LOWER)
        assertTrue(upper.centerY < full.centerY)
        assertTrue(lower.centerY > full.centerY)
    }
}
