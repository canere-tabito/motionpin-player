package com.caneretabito.motionpin.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MotionDao {
    @Query("SELECT * FROM videos ORDER BY lastPlayedAt DESC, createdAt DESC") fun observeVideos(): Flow<List<VideoEntity>>
    @Query("SELECT * FROM pins ORDER BY updatedAt DESC") fun observePins(): Flow<List<PinEntity>>
    @Query("SELECT * FROM channels ORDER BY name COLLATE NOCASE") fun observeChannels(): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channel_videos ORDER BY addedAt") fun observeChannelVideos(): Flow<List<ChannelVideoEntity>>
    @Query("SELECT * FROM ai_jobs ORDER BY requestedAt DESC") fun observeJobs(): Flow<List<AiJobEntity>>
    @Query("SELECT * FROM settings") fun observeSettings(): Flow<List<SettingEntity>>
    @Query("SELECT * FROM videos") suspend fun allVideos(): List<VideoEntity>
    @Query("SELECT * FROM pins") suspend fun allPins(): List<PinEntity>
    @Query("SELECT * FROM channels") suspend fun allChannels(): List<ChannelEntity>
    @Query("SELECT * FROM channel_videos") suspend fun allChannelVideos(): List<ChannelVideoEntity>
    @Query("SELECT * FROM settings") suspend fun allSettings(): List<SettingEntity>
    @Query("SELECT * FROM ai_jobs") suspend fun allJobs(): List<AiJobEntity>
    @Query("SELECT * FROM videos WHERE id = :id") suspend fun video(id: String): VideoEntity?
    @Query("SELECT * FROM videos WHERE uri = :uri LIMIT 1") suspend fun videoForUri(uri: String): VideoEntity?
    @Query("SELECT * FROM pins WHERE id = :id") suspend fun pin(id: String): PinEntity?
    @Query("SELECT * FROM channels WHERE id = :id") suspend fun channel(id: String): ChannelEntity?
    @Query("SELECT * FROM ai_jobs WHERE id = :id") suspend fun job(id: String): AiJobEntity?
    @Query("SELECT * FROM settings WHERE `key` = :key") suspend fun setting(key: String): SettingEntity?
    @Query("UPDATE videos SET availability = :availability WHERE id = :id AND needsRelink = 0") suspend fun setAvailability(id: String, availability: String)
    @Query("UPDATE videos SET availability = 'temporarily-unavailable', needsRelink = 1 WHERE id = :id") suspend fun requireRelink(id: String)
    @Query("UPDATE videos SET fingerprintSha256 = :hash WHERE id = :id AND revision = :revision AND uri = :uri AND needsRelink = 0") suspend fun setVerifiedHash(id: String, revision: Long, uri: String, hash: String): Int
    @Upsert suspend fun putVideo(value: VideoEntity)
    @Upsert suspend fun putPin(value: PinEntity)
    @Upsert suspend fun putChannel(value: ChannelEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putChannelVideo(value: ChannelVideoEntity)
    @Upsert suspend fun putJob(value: AiJobEntity)
    @Upsert suspend fun putSetting(value: SettingEntity)
    @Query("DELETE FROM pins WHERE id = :id") suspend fun deletePin(id: String)
    @Query("DELETE FROM settings WHERE `key` = :key") suspend fun deleteSetting(key: String)
    @Query("DELETE FROM channels WHERE id = :id") suspend fun deleteChannel(id: String)
    @Query("DELETE FROM channel_videos WHERE channelId = :channelId AND videoId = :videoId") suspend fun removeFromChannel(channelId: String, videoId: String)
}

@Database(entities = [VideoEntity::class, PinEntity::class, ChannelEntity::class, ChannelVideoEntity::class, AiJobEntity::class, SettingEntity::class], version = 1, exportSchema = true)
abstract class MotionPinDatabase : RoomDatabase() {
    abstract fun dao(): MotionDao
    companion object {
        @Volatile private var instance: MotionPinDatabase? = null
        fun get(context: Context): MotionPinDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MotionPinDatabase::class.java, "motionpin.db")
                // No destructive fallback: an unknown schema must never discard the user's pins.
                .build().also { instance = it }
        }
    }
}
