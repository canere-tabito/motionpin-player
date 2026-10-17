package com.caneretabito.motionpin.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.room.withTransaction
import com.caneretabito.motionpin.domain.PinBounds
import com.caneretabito.motionpin.domain.SourceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.security.MessageDigest

data class LibraryState(val status: String = "empty", val message: String? = null, val scanned: Int = 0)
sealed class RelinkResult {
    data class Linked(val video: VideoEntity, val strongMatch: Boolean) : RelinkResult()
    data class ConfirmationRequired(val message: String, val candidate: VideoEntity) : RelinkResult()
    data class Rejected(val message: String) : RelinkResult()
}

class MotionRepository(context: Context) {
    private val context = context.applicationContext
    internal val resolver = this.context.contentResolver
    internal val database = MotionPinDatabase.get(this.context)
    internal val dao = database.dao()
    val videos = dao.observeVideos()
    val pins = dao.observePins()
    val channels = dao.observeChannels()
    val jobs = dao.observeJobs()
    val channelVideos = dao.observeChannelVideos()
    val settings = dao.observeSettings()
    private val _libraryState = MutableStateFlow(LibraryState())
    val libraryState = _libraryState.asStateFlow()
    private val scanMutex = Mutex()
    private val sourceMutex = Mutex()

    suspend fun getVideo(id: String) = dao.video(id)
    suspend fun getPin(id: String) = dao.pin(id)
    suspend fun getJob(id: String) = dao.job(id)
    suspend fun allJobs() = dao.allJobs()
    suspend fun updateVideo(video: VideoEntity) {
        require(video.durationUs >= 0 && video.resumePositionUs >= 0 && video.revision > 0)
        // UI may hold an earlier Flow snapshot. Playback updates must not overwrite a new fingerprint,
        // changed-source flag or revoked grant discovered while the video was open.
        database.withTransaction {
            val latest = dao.video(video.id)
            if (latest == null) dao.putVideo(video) else dao.putVideo(latest.copy(
                resumePositionUs = video.resumePositionUs.coerceAtMost(latest.durationUs), lastPlayedAt = video.lastPlayedAt))
        }
    }
    suspend fun updateJob(job: AiJobEntity) {
        database.withTransaction {
            val video = requireNotNull(dao.video(job.videoId)) { "Source video is missing" }
            JobPersistencePolicy.validate(job, dao.job(job.id), video.durationUs)
            dao.putJob(job)
        }
    }
    suspend fun savePin(pin: PinEntity) {
        val video = requireNotNull(dao.video(pin.videoId)) { "Source video is missing" }
        PinBounds.validate(pin.startUs, pin.endUs, video.durationUs)
        pin.zoom()
        require(pin.rationalSpeed().toDouble() in .25..2.0)
        require(pin.title.length <= 500 && pin.note.length <= 16_384 && pin.tagsJson.length <= 4096)
        require(pin.audioMode in setOf("original", "muted", "pitch-preserved"))
        dao.putPin(pin)
        // The pin is observable immediately; hashing a multi-gigabyte source does not delay its save.
        if (video.fingerprintSha256 == null && video.availability == Availability.AVAILABLE && !video.needsRelink)
            ensureFingerprint(video.id)
    }
    suspend fun deletePin(id: String) = database.withTransaction {
        dao.deletePin(id)
        dao.deleteSetting(com.caneretabito.motionpin.player.ColorGradeSettings.pinKey(id))
    }
    suspend fun addChannel(name: String): ChannelEntity {
        require(name.isNotBlank() && name.length <= 200)
        return ChannelEntity(name = name.trim()).also { dao.putChannel(it) }
    }
    suspend fun deleteChannel(id: String) = dao.deleteChannel(id)
    suspend fun addToChannel(channelId: String, videoId: String) {
        requireNotNull(dao.channel(channelId)); requireNotNull(dao.video(videoId))
        dao.putChannelVideo(ChannelVideoEntity(channelId, videoId))
    }
    suspend fun removeFromChannel(channelId: String, videoId: String) = dao.removeFromChannel(channelId, videoId)
    suspend fun setSetting(key: String, value: String) {
        require(key.length in 1..100 && value.length <= 4096)
        dao.putSetting(SettingEntity(key, value))
    }
    suspend fun getSetting(key: String): String? = dao.setting(key)?.value

    /** Never infer deletion from absence: Android 14's partial selection is not a whole-library view. */
    suspend fun scanLibrary() = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            _libraryState.value = LibraryState("loading")
            var count = 0
            var failures = 0
            try {
                val previous = dao.allVideos().associateBy { it.uri }
                val found = mutableSetOf<String>()
                val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.MIME_TYPE,
                    MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.DATE_ADDED)
                resolver.query(collection, projection, "${MediaStore.Video.Media.IS_PENDING}=0", null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        try {
                            val mediaId = cursor.getLong(0)
                            val uri = ContentUris.withAppendedId(collection, mediaId).toString()
                            found += uri
                            val old = dao.videoForUri(uri)
                            val durationUs = Math.multiplyExact(cursor.getLong(2).coerceAtLeast(0), 1000)
                            val size = cursor.getLong(5).coerceAtLeast(0)
                            val modified = cursor.getLong(6).coerceAtLeast(0)
                            val addedSeconds = cursor.getLong(9)
                            val addedAt = if (addedSeconds in 1..Long.MAX_VALUE / 1000) addedSeconds * 1000 else System.currentTimeMillis()
                            if (old != null && ((old.sizeBytes > 0 && old.sizeBytes != size) ||
                                (old.modifiedSeconds > 0 && old.modifiedSeconds != modified) ||
                                (old.durationUs > 0 && old.durationUs / 1000 != durationUs / 1000))) {
                                // Reused URI/changed file is not proof of identity. Preserve the old pins and fingerprint.
                                dao.requireRelink(old.id)
                                failures++
                            } else {
                                if (old != null) dao.setAvailability(old.id, Availability.AVAILABLE)
                                else dao.putVideo(VideoEntity(uri = uri, displayName = cursor.getString(1) ?: "Video", durationUs = durationUs,
                                        width = cursor.getInt(3), height = cursor.getInt(4), sizeBytes = size,
                                        modifiedSeconds = modified, mimeType = cursor.getString(7) ?: "video/unknown",
                                        folder = cursor.getString(8) ?: "", mediaStoreId = mediaId, createdAt = addedAt))
                            }
                            count++
                            if (count % 32 == 0) _libraryState.value = LibraryState("loading", scanned = count)
                        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (_: Exception) { failures++ }
                    }
                } ?: throw IllegalStateException("The media provider did not return a library")
                for (old in previous.values) {
                    if (old.mediaStoreId != null && old.uri !in found && !old.needsRelink)
                        dao.setAvailability(old.id, Availability.PERMISSION_REQUIRED)
                }
                val partial = Build.VERSION.SDK_INT >= 34 && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
                _libraryState.value = LibraryState(when { failures > 0 || partial -> "partial"; count == 0 -> "empty"; else -> "ready" },
                    when { failures > 0 -> "$failures items need permission, relinking or another attempt. Existing pins are retained."
                        partial -> "Only selected videos are visible. Missing items have not been marked deleted."; else -> null }, count)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                _libraryState.value = LibraryState("partial", "Library scan was interrupted; existing pins are safe", count)
                throw cancel
            } catch (error: Exception) {
                _libraryState.value = LibraryState(if (count > 0) "partial" else "error",
                    if (error is SecurityException) "Grant video access or open a local video with the file picker" else error.message ?: "Library unavailable", count)
            }
        }
    }

    suspend fun importVideo(uri: Uri): VideoEntity = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val existing = dao.videoForUri(uri.toString())
            val probed = probeVideo(uri)
            val hash = fullHash(uri)
            if (existing != null) {
                check(!existing.needsRelink) { "This source needs explicit relinking to protect its existing pins" }
                check(existing.sizeBytes == 0L || existing.sizeBytes == probed.sizeBytes) { "The source changed; relink it explicitly" }
                if (existing.fingerprintSha256 != null && !SourceIdentity.strongMatch(existing.fingerprintSha256, hash)) {
                    dao.requireRelink(existing.id)
                    error("This URI now contains different video bytes. Relink it explicitly to protect existing pins.")
                }
            }
            val value = if (existing == null) probed.copy(fingerprintSha256 = hash) else probed.copy(id = existing.id, fingerprintSha256 = hash,
                revision = existing.revision, resumePositionUs = existing.resumePositionUs, lastPlayedAt = existing.lastPlayedAt, createdAt = existing.createdAt)
            dao.putVideo(value)
            value
        }
    }

    /** Called on resume as well as after grants change; permission failures never delete metadata. */
    suspend fun refreshAvailability() = withContext(Dispatchers.IO) {
        for (video in dao.allVideos()) {
            currentCoroutineContext().ensureActive()
            if (video.needsRelink) continue
            val status = try {
                assertLocalSeekable(Uri.parse(video.uri))
                Availability.AVAILABLE
            } catch (_: SecurityException) { Availability.PERMISSION_REQUIRED }
            catch (_: FileNotFoundException) { Availability.TEMPORARILY_UNAVAILABLE }
            catch (_: Exception) { Availability.TEMPORARILY_UNAVAILABLE }
            if (status != video.availability) dao.setAvailability(video.id, status)
        }
    }

    suspend fun ensureFingerprint(videoId: String): VideoEntity = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val video = requireNotNull(dao.video(videoId))
            check(!video.needsRelink && video.availability == Availability.AVAILABLE) { "Source needs permission or relinking" }
            // This operation runs at an explicit pin/AI/cache reuse boundary, never on every startup.
            // URI, size and modification time are insufficient when a provider can replace bytes.
            val hash = fullHash(Uri.parse(video.uri))
            if (video.fingerprintSha256 != null && !SourceIdentity.strongMatch(video.fingerprintSha256, hash)) {
                dao.requireRelink(video.id)
                error("Source content changed. Relink before using its pins or AI cache.")
            }
            check(dao.setVerifiedHash(video.id, video.revision, video.uri, hash) == 1) { "Source association changed while hashing" }
            requireNotNull(dao.video(video.id))
        }
    }

    suspend fun verifiedFingerprint(videoId: String): VideoEntity = ensureFingerprint(videoId)
    suspend fun verifyFingerprint(videoId: String): VideoEntity = ensureFingerprint(videoId)

    /** Records a full hash already computed by the streaming pipeline, without racing stale UI state. */
    suspend fun recordVerifiedFingerprint(videoId: String, expectedRevision: Long, expectedUri: String, sha256: String): VideoEntity =
        sourceMutex.withLock {
            require(sha256.matches(Regex("[a-fA-F0-9]{64}")))
            val latest = requireNotNull(dao.video(videoId))
            check(latest.revision == expectedRevision && latest.uri == expectedUri && !latest.needsRelink) { "Source association changed during verification" }
            if (latest.fingerprintSha256 != null && !SourceIdentity.strongMatch(latest.fingerprintSha256, sha256)) {
                dao.requireRelink(videoId)
                error("Source bytes changed. Relink before generating or playing AI caches.")
            }
            check(dao.setVerifiedHash(videoId, expectedRevision, expectedUri, sha256) == 1) { "Source association changed during verification" }
            requireNotNull(dao.video(videoId))
        }

    suspend fun relink(videoId: String, uri: Uri, confirmWeak: Boolean = false): RelinkResult = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val original = dao.video(videoId) ?: return@withLock RelinkResult.Rejected("Video does not exist")
            val candidate = probeVideo(uri)
            val hash = fullHash(uri)
            val strong = SourceIdentity.strongMatch(original.fingerprintSha256, hash)
            if (!strong && !confirmWeak) return@withLock RelinkResult.ConfirmationRequired(
                if (original.fingerprintSha256 != null) "The full file hash is different. This may be a different video. Confirm only if you intentionally want to move these pins; AI caches will be invalidated."
                else "No original full hash is available. Name, size and duration cannot prove identity. Confirm this video before moving its pins; AI caches will be invalidated.", candidate)
            val existingPins = dao.allPins().filter { it.videoId == videoId }
            if (existingPins.any { it.startUs >= candidate.durationUs || (it.endUs ?: 0) > candidate.durationUs })
                return@withLock RelinkResult.Rejected("Some pins fall outside this video's duration; relinking would make them invalid")
            val linked = candidate.copy(id = original.id, fingerprintSha256 = hash, revision = if (strong) original.revision else original.revision + 1,
                resumePositionUs = original.resumePositionUs.coerceAtMost((candidate.durationUs - 1).coerceAtLeast(0)),
                lastPlayedAt = original.lastPlayedAt, createdAt = original.createdAt, needsRelink = false)
            database.withTransaction {
                dao.putVideo(linked)
                if (!strong) {
                    existingPins.forEach { dao.putPin(it.copy(aiJobId = null)) }
                    dao.allJobs().filter { it.videoId == videoId }.forEach {
                        dao.putJob(it.copy(status = JobStatus.INTERRUPTED, outputPath = null, error = "Source revision changed; regenerate this interval"))
                    }
                }
            }
            RelinkResult.Linked(linked, strong)
        }
    }

    suspend fun exportBackup(uri: Uri) = withContext(Dispatchers.IO) { BackupCodec.export(this@MotionRepository, uri) }
    suspend fun importBackup(uri: Uri): BackupImportResult = withContext(Dispatchers.IO) { BackupCodec.import(this@MotionRepository, uri) }

    private fun assertLocalSeekable(uri: Uri) {
        require(uri.scheme == "content") { "Choose a local video through Android's file picker" }
        require(uri.authority in LOCAL_AUTHORITIES) { "This provider is not confirmed local/offline. Save the file to device storage, then select the local copy; MotionPin will not download or copy it automatically." }
        require(uri.pathSegments.firstOrNull() != "picker" && uri.pathSegments.firstOrNull() != "picker_get_content") {
            "Photo Picker sources may be cloud-backed. Select the local file using the device storage provider."
        }
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            try { Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR) }
            catch (_: Exception) { throw IllegalArgumentException("This source cannot seek. Select a seekable local file; no automatic copy is made.") }
        } ?: throw FileNotFoundException("Video is temporarily unavailable")
    }

    private suspend fun fullHash(uri: Uri): String {
        assertLocalSeekable(uri)
        val hash = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) hash.update(buffer, 0, count)
            }
        } ?: throw FileNotFoundException("Cannot read source")
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun probeVideo(uri: Uri): VideoEntity {
        assertLocalSeekable(uri)
        var name = "Local video"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) { name = it.getString(0) ?: name; size = if (it.isNull(1)) 0 else it.getLong(1) }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000) ?: 0
            require(duration > 0) { "Video duration is unavailable" }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.firstOrNull {
                    it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: throw IllegalArgumentException("No video track found")
                fun integer(key: String): Int? = if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull() else null
                val profile = integer(MediaFormat.KEY_PROFILE)
                // A missing profile is unknown, never inferred to be 8-bit from absence of HDR tags.
                val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/unknown"
                val bitDepth = when {
                    mime == "video/hevc" && profile in setOf(2, 4096, 8192) -> 10
                    mime == "video/avc" && profile in setOf(1, 2, 4, 8) -> 8
                    mime == "video/hevc" && profile == 1 -> 8
                    else -> null
                }
                val rate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() }.getOrNull() else null
                val ratePair = when {
                    rate == null -> 0L to 1L
                    kotlin.math.abs(rate - 59.94) < .02 -> 60_000L to 1_001L
                    kotlin.math.abs(rate - 29.97) < .02 -> 30_000L to 1_001L
                    kotlin.math.abs(rate - 23.976) < .02 -> 24_000L to 1_001L
                    else -> (rate * 1000).toLong() to 1000L
                }
                val preciseDuration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else duration
                return VideoEntity(uri = uri.toString(), displayName = name, durationUs = preciseDuration,
                    width = integer(MediaFormat.KEY_WIDTH) ?: 0, height = integer(MediaFormat.KEY_HEIGHT) ?: 0,
                    rotationDegrees = integer(MediaFormat.KEY_ROTATION) ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                    mimeType = mime, sizeBytes = size, frameRateNumerator = ratePair.first, frameRateDenominator = ratePair.second,
                    colorTransfer = integer(MediaFormat.KEY_COLOR_TRANSFER), colorStandard = integer(MediaFormat.KEY_COLOR_STANDARD),
                    colorRange = integer(MediaFormat.KEY_COLOR_RANGE), bitDepth = bitDepth, codecProfile = profile)
            } finally { extractor.release() }
        } finally { retriever.release() }
    }

    companion object {
        private val LOCAL_AUTHORITIES = setOf("media", "com.android.externalstorage.documents", "com.android.providers.media.documents", "com.android.providers.downloads.documents")
    }
}
