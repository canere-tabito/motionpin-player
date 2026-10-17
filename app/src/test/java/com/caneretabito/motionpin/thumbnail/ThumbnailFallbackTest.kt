package com.caneretabito.motionpin.thumbnail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class ThumbnailFallbackTest {
    private data class Image(val label: String, val detail: Float)
    private class Backend(
        val providerRead: suspend () -> Image? = { null },
        val frameRead: suspend (Long) -> Image? = { null },
    ) : ThumbnailBackend<Image> {
        var providerCalls = 0
        val frameTimes = mutableListOf<Long>()
        val discarded = mutableListOf<Image>()
        override suspend fun provider(): Image? { providerCalls++; return providerRead() }
        override suspend fun frame(timeUs: Long): Image? { frameTimes += timeUs; return frameRead(timeUs) }
        override fun score(image: Image) = image.detail
        override fun discard(image: Image) { discarded += image }
    }
    private val request = ThumbnailRequest("video-id", "content://local/video.mkv", 1L,
        durationUs = 4_000_000L)

    @Test fun usefulProviderImageAvoidsSecondDecoderAndTransfersOwnership() = runBlocking {
        val expected = Image("provider", .25f)
        val backend = Backend(providerRead = { expected }, frameRead = { error("Unnecessary decoder") })
        assertSame(expected, chooseThumbnail(request, backend))
        assertEquals(1, backend.providerCalls)
        assertTrue(backend.frameTimes.isEmpty())
        assertTrue(backend.discarded.isEmpty())
    }

    @Test fun unsupportedProviderThumbnailStillReturnsNativeFrame() = runBlocking {
        val expected = Image("decoded MKV frame", .4f)
        val backend = Backend(providerRead = { throw FileNotFoundException("No typed thumbnail asset") },
            frameRead = { expected })
        assertSame(expected, chooseThumbnail(request, backend))
        assertEquals(1, backend.frameTimes.size)
        assertTrue(backend.frameTimes.single() > 0L)
        assertTrue(backend.discarded.isEmpty())
    }

    @Test fun blackProviderAndBlackFirstSampleDoNotHideLaterVisibleFrame() = runBlocking {
        val provider = Image("black provider", 0f)
        val first = Image("black early frame", 0f)
        val visible = Image("visible frame", .3f)
        var attempts = 0
        val backend = Backend(providerRead = { provider }, frameRead = { if (attempts++ == 0) first else visible })
        assertSame(visible, chooseThumbnail(request, backend))
        assertEquals(2, backend.frameTimes.size)
        assertEquals(setOf(provider, first), backend.discarded.toSet())
        assertEquals(2, backend.discarded.size)
        assertFalse(visible in backend.discarded)
    }

    @Test fun allDimFramesReturnBestAvailableRealImageInsteadOfPermanentPlaceholder() = runBlocking {
        val provider = Image("provider", .001f)
        val candidates = listOf(Image("dim 1", .002f), Image("dim best", .020f),
            Image("dim 3", .005f), Image("black start", 0f))
        var attempts = 0
        val backend = Backend(providerRead = { provider }, frameRead = { candidates[attempts++] })
        assertSame(candidates[1], chooseThumbnail(request, backend))
        assertEquals(4, backend.frameTimes.size)
        assertEquals(setOf(provider, candidates[0], candidates[2], candidates[3]), backend.discarded.toSet())
        assertEquals(4, backend.discarded.size)
    }

    @Test fun oneFailedSeekDoesNotSuppressRemainingBoundedAttempts() = runBlocking {
        val expected = Image("later sync frame", .2f)
        var attempts = 0
        val backend = Backend(frameRead = { if (attempts++ == 0) throw IOException("Bad early sample") else expected })
        assertSame(expected, chooseThumbnail(request, backend))
        assertEquals(2, backend.frameTimes.size)
    }

    @Test fun unreadableSourceExhaustsOnlyBoundedAttemptsAndReturnsNull() = runBlocking {
        val backend = Backend(providerRead = { throw SecurityException("Source permission revoked") },
            frameRead = { throw FileNotFoundException("Source unavailable") })
        assertNull(chooseThumbnail(request, backend))
        assertTrue(backend.frameTimes.size in 1..4)
        assertTrue(backend.discarded.isEmpty())
    }

    @Test fun providerCancellationIsNeverConvertedIntoDecoderFallback() {
        val backend = Backend(providerRead = { throw CancellationException("Row scrolled away") })
        assertThrows(CancellationException::class.java) { runBlocking { chooseThumbnail(request, backend) } }
        assertTrue(backend.frameTimes.isEmpty())
    }

    @Test fun decoderCancellationReleasesRetainedCandidateAndDoesNotTryNextFrame() {
        val retained = Image("dim provider", .01f)
        val backend = Backend(providerRead = { retained }, frameRead = { throw CancellationException("Cancelled") })
        assertThrows(CancellationException::class.java) { runBlocking { chooseThumbnail(request, backend) } }
        assertEquals(listOf(retained), backend.discarded)
        assertEquals(1, backend.frameTimes.size)
    }

    @Test fun invalidHeuristicScoreCannotPrematurelyAcceptProvider() = runBlocking {
        val invalid = Image("invalid score", Float.NaN)
        val expected = Image("visible frame", .3f)
        val backend = Backend(providerRead = { invalid }, frameRead = { expected })
        assertSame(expected, chooseThumbnail(request, backend))
        assertEquals(listOf(invalid), backend.discarded)
    }
}
