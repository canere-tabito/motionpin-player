package com.caneretabito.motionpin.thumbnail

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * Real Android MediaMetadataRetriever tests, NOT_RUN until executed on an eligible device.
 * Tests the production backend after source authorization; the artificial provider is not
 * added to the application's local-source allowlist just to make tests pass.
 */
@RunWith(AndroidJUnit4::class)
class LocalVideoThumbnailTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun mkvWithoutProviderThumbnailFallsBackToVisibleNativeFrame() = verifyContainer("black-lead.mkv")

    @Test fun mp4WithoutProviderThumbnailFallsBackToVisibleNativeFrame() = verifyContainer("black-lead.mp4")

    private fun verifyContainer(file: String) = runBlocking {
        val uri = ThumbnailFixtureProvider.uri(file)
        val originalHash = hash(uri)
        val request = ThumbnailRequest(sourceId = "fixture-$file", uri = uri.toString(), revision = 1L,
            durationUs = 4_000_000L, width = 320, height = 180, maxWidth = 160, maxHeight = 90)
        AndroidThumbnailBackend(context, request).use { backend ->
            // Establish that this real source starts black: time-zero-only fallback would fail UX.
            val first = checkNotNull(backend.frame(0L))
            try { assertTrue("Fixture starts black", pixelVariance(first) < 2.0) }
            finally { first.recycle() }
        }
        val before = counters(uri)
        AndroidThumbnailBackend(context, request).use { backend ->
            val thumbnail = checkNotNull(chooseThumbnail(request, backend)) {
                "A readable $file must survive missing ContentResolver.loadThumbnail support"
            }
            try {
                assertFalse(thumbnail.isRecycled)
                assertTrue(thumbnail.width in 1..160 && thumbnail.height in 1..90)
                assertTrue("Sample beyond the black lead must contain visible detail", pixelVariance(thumbnail) > 100.0)
            } finally { thumbnail.recycle() }
        }
        val after = counters(uri)
        assertTrue("Provider thumbnail API was attempted and rejected", after.first > before.first)
        assertTrue("Fallback opened original local video bytes", after.second > before.second)
        assertEquals("Thumbnail extraction must not modify source bytes", originalHash, hash(uri))
    }

    private fun counters(uri: Uri): Pair<Int, Int> = context.contentResolver.query(uri, null, null, null, null)!!.use {
        assertTrue(it.moveToFirst())
        it.getInt(it.getColumnIndexOrThrow("typed_calls")) to it.getInt(it.getColumnIndexOrThrow("file_calls"))
    }

    private fun hash(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // Independent pixel check: does not validate the image with the production blank heuristic.
    private fun pixelVariance(bitmap: Bitmap): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luminance = pixels.map { .2126 * Color.red(it) + .7152 * Color.green(it) + .0722 * Color.blue(it) }
        val mean = luminance.average()
        return luminance.sumOf { (it - mean) * (it - mean) } / luminance.size
    }
}
