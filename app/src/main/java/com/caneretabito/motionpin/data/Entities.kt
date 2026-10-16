package com.caneretabito.motionpin.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.caneretabito.motionpin.domain.Rational
import com.caneretabito.motionpin.domain.ZoomState
import java.util.UUID

object Availability {
    const val AVAILABLE = "available"
    const val PERMISSION_REQUIRED = "permission-required"
    const val TEMPORARILY_UNAVAILABLE = "temporarily-unavailable"
    const val DELETED_CONFIRMED = "deleted-confirmed"
}

@Entity(tableName = "videos", indices = [Index("uri"), Index("fingerprintSha256")])
data class VideoEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val displayName: String,
    val durationUs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val mimeType: String = "video/unknown",
    val folder: String = "",
    val sizeBytes: Long = 0,
    val modifiedSeconds: Long = 0,
    val frameRateNumerator: Long = 0,
    val frameRateDenominator: Long = 1,
    val availability: String = Availability.AVAILABLE,
    val fingerprintSha256: String? = null,
    val revision: Long = 1,
    val resumePositionUs: Long = 0,
    val lastPlayedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val bitDepth: Int? = null,
    val colorTransfer: Int? = null,
    val colorStandard: Int? = null,
    val colorRange: Int? = null,
    val codecProfile: Int? = null,
    val mediaStoreId: Long? = null,
    val needsRelink: Boolean = false
)

@Entity(tableName = "pins", foreignKeys = [ForeignKey(entity = VideoEntity::class, parentColumns = ["id"], childColumns = ["videoId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("videoId"), Index("aiJobId")])
data class PinEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val videoId: String,
    val startUs: Long,
    val endUs: Long? = null,
    val title: String = "",
    val tagsJson: String = "[]",
    val note: String = "",
    val speedNumerator: Long = 1,
    val speedDenominator: Long = 1,
    val loop: Boolean = false,
    val zoomScale: Float = 1f,
    val zoomCenterX: Float = .5f,
    val zoomCenterY: Float = .5f,
    val audioMode: String = "original",
    val aiJobId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    @get:Ignore
    val speed: Float get() = speedNumerator.toFloat() / speedDenominator
    fun rationalSpeed() = Rational(speedNumerator, speedDenominator)
    fun zoom() = ZoomState(zoomScale, zoomCenterX, zoomCenterY)
}

@Entity(tableName = "channels")
data class ChannelEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val name: String, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "channel_videos", primaryKeys = ["channelId", "videoId"], foreignKeys = [
    ForeignKey(entity = ChannelEntity::class, parentColumns = ["id"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = VideoEntity::class, parentColumns = ["id"], childColumns = ["videoId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("videoId")])
data class ChannelVideoEntity(val channelId: String, val videoId: String, val addedAt: Long = System.currentTimeMillis())

object JobStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val WAITING = "waiting"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val INTERRUPTED = "interrupted"
}

@Entity(tableName = "ai_jobs", foreignKeys = [ForeignKey(entity = VideoEntity::class, parentColumns = ["id"], childColumns = ["videoId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("videoId"), Index("pinId"), Index("cacheKey")])
data class AiJobEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val videoId: String,
    val pinId: String? = null,
    val startUs: Long,
    val endUs: Long,
    val speedNumerator: Long = 1,
    val speedDenominator: Long = 2,
    val fpsNumerator: Long = 60,
    val fpsDenominator: Long = 1,
    val status: String = JobStatus.QUEUED,
    val progress: Float = 0f,
    val outputPath: String? = null,
    val cacheKey: String? = null,
    val sourceFingerprint: String? = null,
    val sourceRevision: Long = 1,
    val modelHash: String = "",
    val engineHash: String = "",
    val precision: String = "fp32",
    val colorContract: String = "",
    val encoderConfig: String = "",
    val audioMode: String = "muted",
    val inferenceCount: Long = 0,
    val outputFrameCount: Long = 0,
    val error: String? = null,
    val capability: String = "UNVERIFIED",
    val requestedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)
