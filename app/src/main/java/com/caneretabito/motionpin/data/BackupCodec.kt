package com.caneretabito.motionpin.data

import android.net.Uri
import androidx.room.withTransaction
import com.caneretabito.motionpin.domain.PinBounds
import com.caneretabito.motionpin.domain.SourceIdentity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

data class BackupImportResult(
    val importedVideos: Int = 0,
    val importedPins: Int = 0,
    val importedChannels: Int = 0,
    val skipped: Int = 0,
    val conflicts: List<String> = emptyList()
) {
    val successful: Boolean get() = conflicts.isEmpty()
    val message: String get() = if (!successful) "No changes imported. UUID/settings conflicts: ${conflicts.take(8).joinToString()}. Keep the original backup and resolve these records explicitly."
        else "Restored $importedVideos videos, $importedPins pins and $importedChannels collections; $skipped existing records kept. Regrant access and relink restored videos. Video files, AI caches and permissions are not in this backup."
}

/** All parsing/validation precedes a single database transaction. Export observes one DB snapshot. */
internal object BackupCodec {
    const val VERSION = 1
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_RECORDS = 20_000
    internal data class Snapshot(val videos: List<VideoEntity>, val pins: List<PinEntity>, val channels: List<ChannelEntity>, val relations: List<ChannelVideoEntity>, val settings: List<SettingEntity>)

    suspend fun export(repository: MotionRepository, uri: Uri) {
        // Hash only during an explicit operation, never all media on startup. Unavailable sources retain their stored fingerprint.
        repository.dao.allVideos().filter { it.availability == Availability.AVAILABLE && !it.needsRelink && it.fingerprintSha256 == null }.forEach {
            currentCoroutineContext().ensureActive()
            try { repository.ensureFingerprint(it.id) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { /* Missing sources remain recoverable by explicit confirmed relink. */ }
        }
        val snapshot = repository.database.withTransaction {
            Snapshot(repository.dao.allVideos(), repository.dao.allPins(), repository.dao.allChannels(),
                repository.dao.allChannelVideos(), repository.dao.allSettings())
        }
        val bytes = encode(snapshot)
        repository.resolver.openOutputStream(uri, "wt")?.use { stream -> stream.write(bytes); stream.flush() }
            ?: error("Backup destination could not be opened")
    }

    internal fun encode(snapshot: Snapshot): ByteArray {
        require(snapshot.videos.size + snapshot.pins.size + snapshot.channels.size + snapshot.relations.size + snapshot.settings.size <= MAX_RECORDS) {
            "Backup exceeds the 20,000-record safety limit"
        }
        val root = JSONObject().put("format", "MotionPin").put("version", VERSION).put("exportedAt", System.currentTimeMillis())
            .put("videos", JSONArray(snapshot.videos.map(::videoJson)))
            .put("pins", JSONArray(snapshot.pins.map(::pinJson)))
            .put("channels", JSONArray(snapshot.channels.map(::channelJson)))
            .put("channelVideos", JSONArray(snapshot.relations.map { JSONObject().put("channelId", it.channelId).put("videoId", it.videoId).put("addedAt", it.addedAt) }))
            .put("settings", JSONArray(snapshot.settings.map { JSONObject().put("key", it.key).put("value", it.value) }))
        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Backup exceeds the 8 MiB safety limit" }
        return bytes
    }

    suspend fun import(repository: MotionRepository, uri: Uri): BackupImportResult {
        val bytes = repository.resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Backup exceeds the 8 MiB safety limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Backup could not be opened")
        val snapshot = decode(bytes)
        val (videos, pins, channels, relations, settings) = snapshot
        return repository.database.withTransaction {
            val existingVideos = repository.dao.allVideos().associateBy { it.id }
            val existingPins = repository.dao.allPins().associateBy { it.id }
            val existingChannels = repository.dao.allChannels().associateBy { it.id }
            val existingSettings = repository.dao.allSettings().associateBy { it.key }
            val conflicts = mutableListOf<String>()
            for (video in videos) existingVideos[video.id]?.let { existing ->
                if (!sameVideoIdentity(existing, video)) conflicts += "video ${video.id}"
            }
            for (pin in pins) existingPins[pin.id]?.let { if (!samePin(it, pin)) conflicts += "pin ${pin.id}" }
            for (channel in channels) existingChannels[channel.id]?.let { if (it != channel) conflicts += "collection ${channel.id}" }
            for (setting in settings) existingSettings[setting.key]?.let { if (it.value != setting.value) conflicts += "setting ${setting.key}" }
            if (conflicts.isNotEmpty()) return@withTransaction BackupImportResult(conflicts = conflicts)
            val newVideos = videos.filter { it.id !in existingVideos }
            val newPins = pins.filter { it.id !in existingPins }
            val newChannels = channels.filter { it.id !in existingChannels }
            newVideos.forEach { repository.dao.putVideo(it.copy(availability = Availability.PERMISSION_REQUIRED, needsRelink = true)) }
            newPins.forEach { repository.dao.putPin(it.copy(aiJobId = null)) }
            newChannels.forEach { repository.dao.putChannel(it) }
            relations.forEach { repository.dao.putChannelVideo(it) }
            settings.filter { it.key !in existingSettings }.forEach { repository.dao.putSetting(it) }
            BackupImportResult(newVideos.size, newPins.size, newChannels.size,
                videos.size + pins.size + channels.size - newVideos.size - newPins.size - newChannels.size)
        }
    }

    internal fun samePin(a: PinEntity, b: PinEntity): Boolean = a.copy(aiJobId = null) == b.copy(aiJobId = null)

    internal fun decode(bytes: ByteArray): Snapshot {
        require(bytes.size <= MAX_BYTES) { "Backup exceeds the 8 MiB safety limit" }
        val text = bytes.toString(Charsets.UTF_8)
        require(!text.contains('\uFFFD')) { "Backup is not valid UTF-8" }
        // JSONObject accepts deep nesting recursively; reject unreasonable nesting before invoking it.
        validateNesting(text)
        val root = JSONObject(text)
        require(root.getString("format") == "MotionPin") { "This is not a MotionPin backup" }
        require(root.getInt("version") == VERSION) { "Unsupported backup version; no data was changed" }
        require(root.getLong("exportedAt") >= 0) { "Invalid backup timestamp" }
        val videos = array(root, "videos").map(::parseVideo)
        val videoMap = videos.associateBy { it.id }
        require(videoMap.size == videos.size) { "Duplicate video UUIDs in backup" }
        val pins = array(root, "pins").map { item -> parsePin(item).also { pin ->
            val video = requireNotNull(videoMap[pin.videoId]) { "Pin references a missing video" }
            PinBounds.validate(pin.startUs, pin.endUs, video.durationUs)
        } }
        require(pins.distinctBy { it.id }.size == pins.size) { "Duplicate pin UUIDs in backup" }
        val channels = array(root, "channels").map { ChannelEntity(uuid(it, "id"), string(it, "name", 200).also { name -> require(name.isNotBlank()) }, time(it, "createdAt")) }
        val channelIds = channels.map { it.id }.toSet()
        require(channelIds.size == channels.size) { "Duplicate channel UUIDs in backup" }
        val relations = array(root, "channelVideos").map {
            ChannelVideoEntity(uuid(it, "channelId"), uuid(it, "videoId"), time(it, "addedAt")).also { relation ->
                require(relation.channelId in channelIds && relation.videoId in videoMap) { "Collection references a missing record" }
            }
        }
        require(relations.distinctBy { it.channelId to it.videoId }.size == relations.size) { "Duplicate collection memberships" }
        val settings = array(root, "settings").map { SettingEntity(string(it, "key", 100).also { key -> require(key.isNotBlank()) }, string(it, "value", 4096)) }
        require(settings.distinctBy { it.key }.size == settings.size) { "Duplicate setting keys" }
        require(videos.size + pins.size + channels.size + relations.size + settings.size <= MAX_RECORDS) { "Too many records" }
        return Snapshot(videos, pins, channels, relations, settings)
    }

    internal fun sameVideoIdentity(a: VideoEntity, b: VideoEntity): Boolean {
        if (a.id != b.id) return false
        if (a.fingerprintSha256 != null && b.fingerprintSha256 != null)
            return SourceIdentity.strongMatch(a.fingerprintSha256, b.fingerprintSha256) && a.durationUs == b.durationUs
        return a.displayName == b.displayName && a.durationUs == b.durationUs && a.sizeBytes == b.sizeBytes &&
            a.width == b.width && a.height == b.height && a.revision == b.revision && a.fingerprintSha256 == b.fingerprintSha256
    }

    private fun videoJson(v: VideoEntity) = JSONObject().put("id", v.id).put("uri", v.uri).put("displayName", v.displayName)
        .put("durationUs", v.durationUs).put("width", v.width).put("height", v.height).put("rotationDegrees", v.rotationDegrees)
        .put("mimeType", v.mimeType).put("folder", v.folder).put("sizeBytes", v.sizeBytes).put("modifiedSeconds", v.modifiedSeconds)
        .put("frameRateNumerator", v.frameRateNumerator).put("frameRateDenominator", v.frameRateDenominator)
        .put("fingerprintSha256", v.fingerprintSha256 ?: JSONObject.NULL).put("revision", v.revision)
        .put("resumePositionUs", v.resumePositionUs).put("lastPlayedAt", v.lastPlayedAt).put("createdAt", v.createdAt)
        .put("bitDepth", v.bitDepth ?: JSONObject.NULL).put("colorTransfer", v.colorTransfer ?: JSONObject.NULL)
        .put("colorStandard", v.colorStandard ?: JSONObject.NULL).put("colorRange", v.colorRange ?: JSONObject.NULL)
        .put("codecProfile", v.codecProfile ?: JSONObject.NULL)

    private fun pinJson(p: PinEntity) = JSONObject().put("id", p.id).put("videoId", p.videoId).put("startUs", p.startUs)
        .put("endUs", p.endUs ?: JSONObject.NULL).put("title", p.title).put("tagsJson", p.tagsJson).put("note", p.note)
        .put("speedNumerator", p.speedNumerator).put("speedDenominator", p.speedDenominator).put("loop", p.loop)
        .put("zoomScale", p.zoomScale.toDouble()).put("zoomCenterX", p.zoomCenterX.toDouble()).put("zoomCenterY", p.zoomCenterY.toDouble())
        .put("audioMode", p.audioMode).put("createdAt", p.createdAt).put("updatedAt", p.updatedAt)

    private fun channelJson(c: ChannelEntity) = JSONObject().put("id", c.id).put("name", c.name).put("createdAt", c.createdAt)

    private fun parseVideo(j: JSONObject): VideoEntity {
        val duration = time(j, "durationUs")
        val width = j.getInt("width"); val height = j.getInt("height")
        require(width in 0..32768 && height in 0..32768)
        val rotation = j.getInt("rotationDegrees"); require(rotation in setOf(0, 90, 180, 270))
        val fingerprint = if (j.isNull("fingerprintSha256")) null else string(j, "fingerprintSha256", 64).also { require(it.matches(Regex("[a-fA-F0-9]{64}"))) }
        val rateN = time(j, "frameRateNumerator"); val rateD = time(j, "frameRateDenominator"); require(rateD > 0)
        val revision = time(j, "revision"); require(revision > 0)
        val resume = time(j, "resumePositionUs"); require(resume <= duration)
        val depth = optionalInt(j, "bitDepth"); require(depth == null || depth in 8..32)
        val uri = string(j, "uri", 8192); require(java.net.URI(uri).scheme == "content")
        return VideoEntity(uuid(j, "id"), uri, string(j, "displayName", 1024), duration, width, height, rotation,
            string(j, "mimeType", 200), string(j, "folder", 2048), time(j, "sizeBytes"), time(j, "modifiedSeconds"), rateN, rateD,
            fingerprintSha256 = fingerprint, revision = revision, resumePositionUs = resume, lastPlayedAt = time(j, "lastPlayedAt"),
            createdAt = time(j, "createdAt"), bitDepth = depth, colorTransfer = optionalInt(j, "colorTransfer"),
            colorStandard = optionalInt(j, "colorStandard"), colorRange = optionalInt(j, "colorRange"), codecProfile = optionalInt(j, "codecProfile"), needsRelink = true)
    }

    private fun parsePin(j: JSONObject): PinEntity {
        val speedN = time(j, "speedNumerator"); val speedD = time(j, "speedDenominator"); require(speedN > 0 && speedD > 0 && speedN.toDouble() / speedD in .25..2.0)
        val tags = string(j, "tagsJson", 4096); val tagsArray = JSONArray(tags); require(tagsArray.length() <= 100)
        for (index in 0 until tagsArray.length()) require(tagsArray.get(index) is String && tagsArray.getString(index).length <= 100)
        return PinEntity(id = uuid(j, "id"), videoId = uuid(j, "videoId"), startUs = time(j, "startUs"),
            endUs = if (j.isNull("endUs")) null else time(j, "endUs"), title = string(j, "title", 500),
            tagsJson = tags, note = string(j, "note", 16384), speedNumerator = speedN, speedDenominator = speedD,
            loop = j.getBoolean("loop"), zoomScale = j.getDouble("zoomScale").toFloat(), zoomCenterX = j.getDouble("zoomCenterX").toFloat(),
            zoomCenterY = j.getDouble("zoomCenterY").toFloat(), audioMode = string(j, "audioMode", 32).also { require(it in setOf("original", "muted", "pitch-preserved")) },
            createdAt = time(j, "createdAt"), updatedAt = time(j, "updatedAt")).also { it.zoom() }
    }

    private fun optionalInt(j: JSONObject, key: String): Int? = if (j.isNull(key)) null else j.getInt(key)
    private fun uuid(j: JSONObject, key: String): String = string(j, key, 36).also { require(UUID.fromString(it).toString() == it.lowercase()) { "Invalid UUID" } }
    private fun time(j: JSONObject, key: String): Long {
        val value = j.get(key)
        require(value is Long || value is Int) { "$key must be an integer" }
        return j.getLong(key).also { require(it >= 0) { "$key cannot be negative" } }
    }
    private fun string(j: JSONObject, key: String, limit: Int): String {
        require(j.get(key) is String) { "$key must be a string" }
        return j.getString(key).also { require(it.length <= limit) { "$key is too long" } }
    }
    private fun array(j: JSONObject, key: String): List<JSONObject> {
        val values = j.getJSONArray(key); require(values.length() <= MAX_RECORDS) { "Too many $key records" }
        return (0 until values.length()).map(values::getJSONObject)
    }
    private fun validateNesting(text: String) {
        var quoted = false; var escaped = false; var depth = 0
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) { "Backup nesting limit exceeded" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid JSON nesting" } }
            }
        }
        require(depth == 0 && !quoted) { "Incomplete JSON" }
    }
}
