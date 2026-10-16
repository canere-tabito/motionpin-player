package com.caneretabito.motionpin.tracking

import android.content.pm.PackageManager
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
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
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(quality.modelFile).build())
                .setRunningMode(RunningMode.IMAGE)
                .setCategoryAllowlist(listOf("person"))
                .setMaxResults(12)
                .build()
            val image = Bitmap.createBitmap(448, 448, Bitmap.Config.ARGB_8888)
            try {
                ObjectDetector.createFromOptions(context, options).use {
                    assertNotNull(it.detect(BitmapImageBuilder(image).build()))
                }
            } finally { image.recycle() }
        }
    }
}
