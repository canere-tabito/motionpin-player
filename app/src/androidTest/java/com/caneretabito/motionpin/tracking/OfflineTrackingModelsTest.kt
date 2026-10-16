package com.caneretabito.motionpin.tracking

import android.content.pm.PackageManager
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * NOT_RUN until executed on an eligible Android device. Fail if either bundled model cannot
 * load/infer fully offline; an empty/no-person test image is intentionally not a quality score.
 */
@RunWith(AndroidJUnit4::class)
class OfflineTrackingModelsTest {
    @Test fun imageWrapperOwnsPixelsAndPreviewMustHaveIndependentStorage() {
        val preview = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        preview.eraseColor(android.graphics.Color.RED)
        val inference = checkNotNull(preview.copy(Bitmap.Config.ARGB_8888, false))
        try {
            BitmapImageBuilder(inference).build().use {
                assertFalse(inference.isRecycled)
                assertEquals(android.graphics.Color.RED, inference.getPixel(8, 8))
            }
            assertTrue("Pinned MediaPipe releases its Bitmap container", inference.isRecycled)
            assertFalse("Displayed preview must remain alive after inference", preview.isRecycled)
            assertEquals(android.graphics.Color.RED, preview.getPixel(8, 8))
        } finally {
            if (!inference.isRecycled) inference.recycle()
            preview.recycle()
        }
    }

    @Test fun exactBundledModelsLoadAndInferWithoutNetworkPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(android.Manifest.permission.INTERNET, context.packageName))
        val expected = mapOf(
            TrackerQuality.HIGH to Pair(23096891L, "ad2abbf2b4e10585e15176fd7b5ef03c28dda959ae26fc142549fdd1814db91d"),
            TrackerQuality.BALANCED to Pair(13836895L, "40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58")
        )
        for ((quality, identity) in expected) {
            context.assets.openFd(quality.modelFile).use { afd ->
                assertEquals("Detector asset must be uncompressed", identity.first, afd.length)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(quality.modelFile).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            assertEquals(identity.second, actualHash)
            ObjectDetector.createFromOptions(context, DancerTracker.detectorOptions(quality)).use { detector ->
                // Exercise both displayed orientations and repeated MPImage ownership, using the
                // exact production allowlist/threshold/options. This is not a person recall test.
                for ((width, height) in listOf(640 to 360, 360 to 640, 448 to 448)) {
                    repeat(3) {
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            BitmapImageBuilder(bitmap).build().use { image ->
                                val detections = detector.detect(image).detections()
                                assertNotNull(detections)
                                assertTrue(detections.all { detection ->
                                    detection.categories().all { it.categoryName() == "person" }
                                })
                            }
                        } finally { if (!bitmap.isRecycled) bitmap.recycle() }
                    }
                }
            }
        }
    }
}
