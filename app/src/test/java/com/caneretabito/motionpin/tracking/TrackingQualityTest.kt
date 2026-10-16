package com.caneretabito.motionpin.tracking

import com.caneretabito.motionpin.player.VideoViewport
import com.caneretabito.motionpin.player.ViewportGeometry
import com.caneretabito.motionpin.player.viewportTransform
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
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
        // A merged/single remaining detection could be either of the identical dancers.
        assertEquals(MatchStatus.AMBIGUOUS, match.step(500_000, listOf(person(.23f))).status)
    }

    @Test fun failedOcclusionNeverReassignsToOtherDancer() {
        val match = StablePersonMatcher(person(.2f), 0)
        assertEquals(MatchStatus.MISSING, match.step(250_000, emptyList()).status)
        assertEquals(MatchStatus.MISSING, match.step(500_000, emptyList()).status)
        assertEquals(MatchStatus.MISSING, match.step(750_000, listOf(person(.95f, w = .03f))).status)
        assertEquals(MatchStatus.MISSING, match.step(1_000_000, emptyList()).status)
        assertEquals(MatchStatus.LOST, match.step(1_250_000, emptyList()).status)
        assertEquals(MatchStatus.LOST, match.step(1_500_000, listOf(person(.2f))).status)
    }

    @Test fun shortOcclusionCanRecoverWithoutInventingCameraPositions() {
        val tracker = StablePersonMatcher(person(.2f), 0)
        assertEquals(MatchStatus.MISSING, tracker.step(250_000, emptyList()).status)
        assertEquals(MatchStatus.MATCHED, tracker.step(500_000, listOf(person(.23f))).status)
        val frames = listOf(TrackingKeyframe(0, person(.2f)),
            TrackingKeyframe(750_000L, person(.3f)))
        assertNull(TrackingTrajectory.at(frames, 300_000L, 500_000L))
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

    @Test fun confirmedRecoveryAnchorIsAvailableEvenBeforeAnotherConfirmedFrame() {
        val keys = listOf(TrackingKeyframe(0L, person(.2f)),
            TrackingKeyframe(1_500_000L, person(.3f)),
            TrackingKeyframe(1_750_000L, person(.32f)))
        assertNull(TrackingTrajectory.at(keys, 1_000_000L))
        assertEquals(.3f, TrackingTrajectory.at(keys, 1_500_000L)!!.left, .00001f)
    }

    @Test fun vfrTrajectoryUsesElapsedTimeInsteadOfFrameIndexAndNeverInventsALostPosition() {
        val keys = listOf(TrackingKeyframe(100_000L, person(.2f)),
            TrackingKeyframe(420_000L, person(.36f)),
            TrackingKeyframe(610_000L, person(.455f)))
        assertEquals(.24f, TrackingTrajectory.at(keys, 180_000L)!!.left, .00001f)
        assertEquals(.4075f, TrackingTrajectory.at(keys, 515_000L)!!.left, .00001f)
        assertEquals(.455f, TrackingTrajectory.at(keys, 860_000L)!!.left, .00001f)
        assertNull(TrackingTrajectory.at(keys, 860_001L))
        assertNull(TrackingTrajectory.at(keys, 99_999L))
    }

    @Test fun repeatedPausedTimestampsDoNotContinueEasing() {
        val camera = SmoothFollowCamera()
        val start = camera.follow(0L, person(.1f), VideoViewport(), DancerFraming.FULL)
        val moving = camera.follow(33_333L, person(.4f), start, DancerFraming.FULL)
        repeat(100) {
            assertEquals(moving, camera.follow(33_333L, person(.4f), moving, DancerFraming.FULL))
        }
    }

    @Test fun explicitSeekResetAlsoSnapsASmallForwardSeek() {
        val camera = SmoothFollowCamera()
        val start = camera.follow(0L, person(.1f), VideoViewport(), DancerFraming.FULL)
        camera.reset()
        val sought = camera.follow(100_000L, person(.6f), start, DancerFraming.FULL)
        assertEquals(.69f, sought.centerX, .00001f)
        val backwards = camera.follow(50_000L, person(.2f), sought, DancerFraming.FULL)
        assertEquals(.29f, backwards.centerX, .00001f)
    }

    @Test fun pausedFramingAndGeometryChangeStillApply() {
        val camera = SmoothFollowCamera()
        val box = person(.2f)
        val full = camera.follow(0L, box, VideoViewport(), DancerFraming.FULL)
        val upper = camera.follow(0L, box, full, DancerFraming.UPPER)
        assertTrue(upper.centerY < full.centerY)
        assertTrue(upper.zoom > full.zoom)
        val rotated = camera.follow(0L, box, upper, DancerFraming.FULL,
            ViewportGeometry(1080f, 1920f, 16f / 9f))
        assertTrue(rotated.zoom > full.zoom)
    }

    @Test fun fullBodyFitsActualScreenInAllFourOrientationsAndNearEdges() {
        val videos = listOf(16f / 9f, 9f / 16f)
        val containers = listOf(1080f to 1920f, 1920f to 1080f)
        val boxes = listOf(person(.01f, .01f, w = .20f, h = .90f),
            person(.78f, .08f, w = .21f, h = .90f), person(.42f, .4f, w = .07f, h = .20f))
        for (aspect in videos) for ((width, height) in containers) for (box in boxes) {
            val geometry = ViewportGeometry(width, height, aspect)
            val camera = SmoothFollowCamera().apply { setUserZoom(8f) }
            val view = camera.follow(0L, box, VideoViewport(), DancerFraming.FULL, geometry)
            assertVisible(box, view, geometry)
        }
    }

    @Test fun fullBodySafetyWinsImmediatelyOverZoomDampingAfterApproachOrJump() {
        val camera = SmoothFollowCamera().apply { setUserZoom(5f) }
        val small = person(.42f, .4f, w = .07f, h = .20f)
        val start = camera.follow(0L, small, VideoViewport(), DancerFraming.FULL)
        assertTrue(start.zoom > 3f)
        val close = person(.05f, .02f, w = .4f, h = .95f)
        val approached = camera.follow(33_333L, close, start, DancerFraming.FULL)
        assertTrue(approached.zoom <= 1f / close.height)
        assertVisible(close, approached, ViewportGeometry())
    }

    @Test fun smallBodySizeNoiseUsesComfortMarginInsteadOfForcingZoomChanges() {
        val camera = SmoothFollowCamera()
        var view = camera.follow(0L, person(.41f), VideoViewport(), DancerFraming.FULL)
        var largestStep = 0f
        for (i in 1..300) {
            val noisyHeight = .55f + if (i % 2 == 0) .012f else -.012f
            val box = person(.41f, y = .475f - noisyHeight / 2f, h = noisyHeight)
            val next = camera.follow(i * 33_333L, box, view, DancerFraming.FULL)
            largestStep = maxOf(largestStep, abs(next.zoom - view.zoom))
            assertVisible(box, next, ViewportGeometry())
            view = next
        }
        assertTrue("Small box noise bypassed zoom damping: $largestStep", largestStep < .003f)
    }

    @Test fun distantDancerGetsUsefulSubjectRelativeFramingWithoutAdditionalSourcePixels() {
        val small = person(.45f, .4f, w = .06f, h = .20f)
        val view = SmoothFollowCamera().follow(0L, small, VideoViewport(), DancerFraming.FULL)
        assertEquals(.72f, small.height * view.zoom, .0001f)
        assertVisible(small, view, ViewportGeometry())
    }

    @Test fun upperKeepsHeadAndLowerKeepsFeetEvenForTallSubjects() {
        val box = person(.3f, .03f, w = .25f, h = .94f)
        for (framing in listOf(DancerFraming.UPPER, DancerFraming.LOWER)) {
            val view = SmoothFollowCamera().follow(0L, box, VideoViewport(), framing)
            assertVisible(box.copy(top = box.top + box.height * framing.regionTop,
                bottom = box.top + box.height * framing.regionBottom), view, ViewportGeometry())
        }
    }

    @Test fun manualResumeEasesFromHeldViewportRatherThanSnappingToTarget() {
        val camera = SmoothFollowCamera()
        val held = VideoViewport(zoom = 3f, centerX = .8f, centerY = .6f)
        camera.resumeFrom(held, 1_000_000L)
        assertEquals(held, camera.follow(1_000_000L, person(.1f), held, DancerFraming.FULL))
        val next = camera.follow(1_033_333L, person(.1f), held, DancerFraming.FULL)
        assertTrue(next.centerX < held.centerX && next.centerX > .6f)
        assertTrue(next.zoom < held.zoom && next.zoom > 2.8f)
        var settled = next
        for (i in 2..160) settled = camera.follow(1_000_000L + i * 33_333L,
            person(.1f), settled, DancerFraming.FULL)
        assertVisible(person(.1f), settled, ViewportGeometry())
    }

    @Test fun invalidCameraObservationCannotPoisonTheViewport() {
        val current = VideoViewport(2f, .4f, .3f)
        val invalid = person(.2f).copy(top = Float.NaN)
        assertEquals(current, SmoothFollowCamera().follow(0L, invalid, current, DancerFraming.FULL))
    }

    @Test fun stationaryDetectionJitterIsLowerThanPreviousCameraAndFastReversalRecovers() {
        val camera = SmoothFollowCamera()
        var view = camera.follow(0L, person(.41f), VideoViewport(), DancerFraming.FULL)
        var legacyX = .5f
        var newSquared = 0.0
        var oldSquared = 0.0
        val oldWeight = (1.0 - exp(-.033333 / .13)).toFloat()
        for (i in 1..360) {
            val noisy = person(.41f + if (i % 2 == 0) .007f else -.007f)
            view = camera.follow(i * 33_333L, noisy, view, DancerFraming.FULL)
            if (abs(noisy.cx - legacyX) > .0025f) legacyX += (noisy.cx - legacyX) * oldWeight
            if (i > 60) {
                newSquared += (view.centerX - .5f).toDouble().let { it * it }
                oldSquared += (legacyX - .5f).toDouble().let { it * it }
            }
        }
        val newRms = sqrt(newSquared / 300)
        val oldRms = sqrt(oldSquared / 300)
        println("camera.stationary_jitter_rms.normalized baseline=$oldRms current=$newRms samples=300")
        assertTrue("new jitter $newRms vs baseline $oldRms", newRms < oldRms * .85)

        // A sustained fast pan then reversal must quickly move back towards the new observation.
        for (i in 1..10) view = camera.follow((360 + i) * 33_333L,
            person(.41f + i * .025f), view, DancerFraming.FULL)
        val beforeTurn = view.centerX
        for (i in 1..5) view = camera.follow((370 + i) * 33_333L,
            person(.66f - i * .04f), view, DancerFraming.FULL)
        assertTrue(view.centerX < beforeTurn)
        assertTrue(abs(view.centerX - .55f) < .10f)
    }

    private fun assertVisible(box: PersonBox, view: VideoViewport, geometry: ViewportGeometry) {
        val transform = viewportTransform(geometry.width, geometry.height, geometry.aspect, geometry.fill, view)
        val width = transform.baseWidth * view.zoom
        val height = transform.baseHeight * view.zoom
        val left = geometry.width / 2f + (box.left - .5f) * width + transform.translateX
        val right = geometry.width / 2f + (box.right - .5f) * width + transform.translateX
        val top = geometry.height / 2f + (box.top - .5f) * height + transform.translateY
        val bottom = geometry.height / 2f + (box.bottom - .5f) * height + transform.translateY
        val epsilon = .001f
        assertTrue("left=$left geometry=$geometry view=$view", left >= -epsilon)
        assertTrue("right=$right geometry=$geometry view=$view", right <= geometry.width + epsilon)
        assertTrue("top=$top geometry=$geometry view=$view", top >= -epsilon)
        assertTrue("bottom=$bottom geometry=$geometry view=$view", bottom <= geometry.height + epsilon)
    }
}
