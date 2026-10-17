package com.caneretabito.motionpin.thumbnail

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import android.util.Size
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Small local library previews only. No playback surface, source mutation, or network data source. */
object LocalVideoThumbnails {
    private const val CACHE_BYTES = 8 * 1024 * 1024
    private val permits = Semaphore(2)
    private val cache = object : LruCache<ThumbnailRequest, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: ThumbnailRequest, value: Bitmap): Int = value.allocationByteCount
        // Eviction drops the cache reference. Compose may still be displaying this bitmap.
    }

    suspend fun load(context: Context, request: ThumbnailRequest): Bitmap? {
        if (!isLocalSource(Uri.parse(request.uri))) return null
        return withContext(Dispatchers.IO) {
            permits.withPermit {
                currentCoroutineContext().ensureActive()
                cache.get(request)?.let { return@withPermit it }
                try {
                    AndroidThumbnailBackend(context.applicationContext, request).use { backend ->
                        var owned: Bitmap? = chooseThumbnail(request, backend)
                        try {
                            currentCoroutineContext().ensureActive()
                            owned?.let { cache.put(request, it) }
                            owned.also { owned = null }
                        } finally { owned?.recycle() }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
            }
        }
    }

    private fun isLocalSource(uri: Uri): Boolean = when (uri.scheme) {
        "file" -> uri.path?.startsWith('/') == true && uri.authority.isNullOrEmpty()
        "content" -> when (uri.authority) {
            "media" -> {
                val parts = uri.pathSegments
                parts.size == 4 && parts[1] == "video" && parts[2] == "media" &&
                    parts[3].toLongOrNull()?.let { it >= 0L } == true &&
                    (parts[0] in setOf("external", "external_primary", "internal") ||
                        parts[0].matches(Regex("[a-fA-F0-9]+(?:-[a-fA-F0-9]+)+")))
            }
            "com.android.externalstorage.documents", "com.android.providers.media.documents",
            "com.android.providers.downloads.documents" -> true
            else -> false // A third-party provider could fetch from a cloud account itself.
        }
        else -> false
    }
}

/** Native calls remain inside the worker permit until they return; release never races decode. */
internal class AndroidThumbnailBackend(context: Context, private val request: ThumbnailRequest) :
    ThumbnailBackend<Bitmap>, Closeable {
    private val resolver = context.applicationContext.contentResolver
    private val uri = Uri.parse(request.uri)
    private val bounds = ThumbnailPolicy.scaledSize(0, 0, request.maxWidth, request.maxHeight)
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverAttempted = false
    private var sourceWidth = request.width
    private var sourceHeight = request.height
    private var rotation = request.rotationDegrees
    private val startedAtMs = SystemClock.elapsedRealtime()

    override suspend fun provider(): Bitmap? {
        if (uri.scheme != "content") return null
        val bitmap = withTimeoutOrNull(2_000L) {
            cancellableResource({ it.recycle() }) { signal ->
                resolver.loadThumbnail(uri, Size(bounds.width, bounds.height), signal)
            }
        } ?: return null
        return normalize(bitmap, correctRetrieverRotation = false)
    }

    override suspend fun frame(timeUs: Long): Bitmap? {
        // A native codec call cannot be force-cancelled. Stop scheduling additional work after
        // the budget; keep the semaphore and close the retriever when that current call finishes.
        if (SystemClock.elapsedRealtime() - startedAtMs > 5_000L) return null
        val decoder = getRetriever() ?: return null
        currentCoroutineContext().ensureActive()
        val bitmap = decoder.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            bounds.width, bounds.height) ?: return null
        try {
            currentCoroutineContext().ensureActive()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            bitmap.recycle()
            throw cancelled
        }
        return normalize(bitmap, correctRetrieverRotation = true)
    }

    private suspend fun getRetriever(): MediaMetadataRetriever? {
        if (retrieverAttempted) return retriever
        retrieverAttempted = true
        val asset = cancellableResource<AssetFileDescriptor>({ it.close() }) { signal ->
            resolver.openAssetFileDescriptor(uri, "r", signal)
        } ?: return null
        return asset.use { descriptor ->
            val decoder = MediaMetadataRetriever()
            try {
                currentCoroutineContext().ensureActive()
                Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
                if (descriptor.declaredLength >= 0L)
                    decoder.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
                else if (descriptor.startOffset == 0L) decoder.setDataSource(descriptor.fileDescriptor)
                else return null
                currentCoroutineContext().ensureActive()
                sourceWidth = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: sourceWidth
                sourceHeight = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: sourceHeight
                rotation = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: rotation
                retriever = decoder
                decoder
            } finally {
                if (retriever !== decoder) decoder.release()
            }
        }
    }

    private fun normalize(bitmap: Bitmap, correctRetrieverRotation: Boolean): Bitmap {
        var owned = bitmap
        try {
            // Android normally applies display rotation. Correct only an unambiguously
            // unrotated quarter-turn result; blindly applying metadata rotates it twice.
            val quarterTurn = ((rotation % 360) + 360) % 360
            val mismatch = sourceWidth > 0 && sourceHeight > 0 && sourceWidth != sourceHeight &&
                (sourceHeight > sourceWidth) == (owned.height > owned.width)
            if (correctRetrieverRotation && quarterTurn in setOf(90, 270) && mismatch) {
                val rotated = Bitmap.createBitmap(owned, 0, 0, owned.width, owned.height,
                    Matrix().apply { postRotate(quarterTurn.toFloat()) }, true)
                if (rotated !== owned) { owned.recycle(); owned = rotated }
            }
            val target = ThumbnailPolicy.scaledSize(owned.width, owned.height, bounds.width, bounds.height)
            if (owned.width != target.width || owned.height != target.height) {
                val scaled = Bitmap.createScaledBitmap(owned, target.width, target.height, true)
                if (scaled !== owned) { owned.recycle(); owned = scaled }
            }
            if (owned.config == Bitmap.Config.HARDWARE) {
                val software = checkNotNull(owned.copy(Bitmap.Config.ARGB_8888, false))
                owned.recycle()
                owned = software
            }
            return owned
        } catch (failure: Throwable) {
            owned.recycle()
            throw failure
        }
    }

    override fun score(image: Bitmap): Float {
        val sample = IntArray(16 * 12)
        for (y in 0 until 12) for (x in 0 until 16) {
            sample[y * 16 + x] = image.getPixel(
                ((x + .5f) * image.width / 16).toInt().coerceAtMost(image.width - 1),
                ((y + .5f) * image.height / 12).toInt().coerceAtMost(image.height - 1))
        }
        return ThumbnailPolicy.detailScore(sample)
    }

    override fun discard(image: Bitmap) { image.recycle() }
    override fun close() { retriever?.release(); retriever = null }

    /** Cancellation reaches provider IPC while its blocking call is still running. */
    private suspend fun <T : Any> cancellableResource(dispose: (T) -> Unit,
        read: (CancellationSignal) -> T?): T? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { runCatching { signal.cancel() } }
        try {
            continuation.resume(read(signal)) { _, resource, _ -> resource?.let { runCatching { dispose(it) } } }
        } catch (failure: Exception) {
            continuation.resumeWith(Result.failure(failure))
        }
    }
}
