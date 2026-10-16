package com.caneretabito.motionpin.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.caneretabito.motionpin.data.VideoEntity
import com.caneretabito.motionpin.player.VideoViewport
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Person locations are normalized in displayed-video space, not SurfaceView/pixel coordinates. */
data class PersonBox(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val confidence: Float, val appearance: List<Float>
) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

data class TrackingKeyframe(val sourceTimeUs: Long, val box: PersonBox)

/**
 * Do not automatically jump between people when the association is not sufficiently unique.
 * Geometry and a weak clothing/color signature are useful, but cannot establish identity
 * through long occlusion or identical costumes.
 */
object PersonAssociation {
    fun choose(previous: PersonBox, candidates: List<PersonBox>): PersonBox? {
        data class Scored(val box: PersonBox, val score: Float)
        val possibilities = candidates.mapNotNull { candidate ->
            val distance = hypot(candidate.cx - previous.cx, candidate.cy - previous.cy)
            val reach = max(.18f, max(previous.width, previous.height) * 1.25f)
            if (distance > reach || candidate.width <= 0f || candidate.height <= 0f) return@mapNotNull null
            val commonLeft = max(previous.left, candidate.left)
            val commonTop = max(previous.top, candidate.top)
            val overlap = max(0f, min(previous.right, candidate.right) - commonLeft) *
                max(0f, min(previous.bottom, candidate.bottom) - commonTop)
            val union = previous.width * previous.height + candidate.width * candidate.height - overlap
            val iou = if (union > 0f) overlap / union else 0f
            val appearance = if (candidate.appearance.size == previous.appearance.size && candidate.appearance.isNotEmpty())
                candidate.appearance.indices.map { abs(previous.appearance[it] - candidate.appearance[it]) }.average().toFloat()
            else 1f
            Scored(candidate, 2.2f * iou - 1.8f * distance - 1.5f * appearance)
        }.sortedByDescending { it.score }
        if (possibilities.isEmpty() || possibilities[0].score < -.36f) return null
        // A near tie is a loss of identity, never a reason to pick an arbitrary dancer.
        if (possibilities.size > 1 && possibilities[0].score - possibilities[1].score < .17f) return null
        return possibilities.first().box
    }
}

data class DancerTrackingState(
    val phase: String = "idle", // idle, choosing, scanning, active, lost
    val videoId: String? = null,
    val preview: Bitmap? = null,
    val candidates: List<PersonBox> = emptyList(),
    val keyframes: List<TrackingKeyframe> = emptyList(),
    val analyzedUntilUs: Long = 0,
    val message: String = ""
)

/**
 * A bounded ahead-of-playback analysis; never modifies/copies original video or AI cache.
 * Decode uses low-resolution samples on Dispatchers.IO; playback still uses native SurfaceView.
 */
class DancerTracker(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(DancerTrackingState())
    val state = _state.asStateFlow()
    private var work: Job? = null
    private var generation = 0L

    private val stepUs = 500_000L // 2 detection samples/s: interpolate camera motion between anchors
    private val chunkUs = 25_000_000L
    private val aheadThresholdUs = 7_000_000L

    fun stop() {
        generation++
        work?.cancel()
        work = null
        _state.value = DancerTrackingState()
    }

    fun requestSelection(video: VideoEntity, sourceUs: Long) {
        stop()
        val token = generation
        val position = sourceUs.coerceIn(0L, (video.durationUs - 1).coerceAtLeast(0L))
        _state.value = DancerTrackingState("scanning", video.id, message = "人物を検出しています…")
        work = scope.launch(Dispatchers.IO) {
            try {
                val frame = frameAt(video, position)
                val people = withDetector { detect(it, frame) }
                ensureActiveToken(token)
                _state.value = DancerTrackingState("choosing", video.id, preview = frame,
                    candidates = people, analyzedUntilUs = position,
                    message = if (people.isEmpty()) "人物を検出できませんでした。別の時刻で試してください。" else "追尾したい人物をタップしてください")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (token == generation) _state.value = DancerTrackingState("lost", video.id,
                    message = "人物検出を開始できません: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (token == generation) work = null
            }
        }
    }

    fun select(video: VideoEntity, index: Int) {
        val snapshot = _state.value
        if (snapshot.phase != "choosing" || snapshot.videoId != video.id) return
        val chosen = snapshot.candidates.getOrNull(index) ?: return
        val anchorTime = snapshot.analyzedUntilUs
        generation++
        val token = generation
        work?.cancel()
        _state.value = DancerTrackingState("scanning", video.id,
            keyframes = listOf(TrackingKeyframe(anchorTime, chosen)), analyzedUntilUs = anchorTime,
            message = "選んだ人物の動きを解析しています…")
        analyzeAhead(video, token)
    }

    fun maybeExtend(video: VideoEntity, sourceUs: Long) {
        val current = _state.value
        if (current.videoId != video.id || current.phase != "active" || work?.isActive == true) return
        if (sourceUs < current.analyzedUntilUs - aheadThresholdUs) return
        if (current.analyzedUntilUs >= video.durationUs - 1) return
        analyzeAhead(video, generation)
    }

    private fun analyzeAhead(video: VideoEntity, token: Long) {
        work = scope.launch(Dispatchers.IO) {
            try {
                val old = _state.value
                if (old.videoId != video.id || old.keyframes.isEmpty() || old.phase == "lost") return@launch
                val last = old.keyframes.last()
                val stopAt = min(video.durationUs - 1, last.sourceTimeUs + chunkUs)
                _state.value = old.copy(phase = "scanning", message = "追尾範囲を解析中…")
                val appended = ArrayList<TrackingKeyframe>()
                var time = last.sourceTimeUs + stepUs
                var previous = last.box
                var missed = 0
                withDetector { detector ->
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(context, Uri.parse(video.uri))
                        while (time <= stopAt) {
                            ensureActiveToken(token)
                            val bitmap = loadFrame(retriever, video, time)
                            val next = bitmap?.let { bmp ->
                                val boxes = detect(detector, bmp)
                                bmp.recycle()
                                PersonAssociation.choose(previous, boxes)
                            }
                            if (next == null) {
                                if (++missed >= 2) {
                                    ensureActiveToken(token)
                                    _state.value = _state.value.copy(phase = "lost",
                                        keyframes = _state.value.keyframes + appended,
                                        analyzedUntilUs = time,
                                        message = "人物を見失いました。別の人物へ自動で切り替えず、再選択が必要です。")
                                    return@withDetector
                                }
                            } else {
                                missed = 0
                                previous = next
                                appended += TrackingKeyframe(time, next)
                            }
                            time += stepUs
                        }
                    }
                }
                ensureActiveToken(token)
                if (_state.value.phase != "lost") {
                    val anchors = _state.value.keyframes + appended
                    _state.value = _state.value.copy(phase = "active", keyframes = anchors,
                        analyzedUntilUs = stopAt,
                        message = if (stopAt >= video.durationUs - 1) "動画の最後まで追尾を解析しました" else "ダンサーを追尾中")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (token == generation) _state.value = _state.value.copy(phase = "lost",
                    message = "追尾解析を停止しました: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (token == generation) work = null
            }
        }
    }

    /** Smoothly move only viewport center; keep user-selected zoom, playback codec and color unchanged. */
    fun viewportAt(videoId: String, timeUs: Long, current: VideoViewport): VideoViewport? {
        val s = _state.value
        if (s.videoId != videoId || s.phase !in setOf("scanning", "active") || s.keyframes.isEmpty()) return null
        val frames = s.keyframes
        if (timeUs < frames.first().sourceTimeUs || timeUs > frames.last().sourceTimeUs + stepUs) return null
        val nextIndex = frames.indexOfFirst { it.sourceTimeUs >= timeUs }
        val next = when { nextIndex < 0 -> frames.last(); else -> frames[nextIndex] }
        val prev = if (nextIndex <= 0) next else frames[nextIndex - 1]
        val factor = if (next.sourceTimeUs == prev.sourceTimeUs) 0f else
            ((timeUs - prev.sourceTimeUs).toDouble() / (next.sourceTimeUs - prev.sourceTimeUs)).toFloat().coerceIn(0f, 1f)
        val x = prev.box.cx + (next.box.cx - prev.box.cx) * factor
        val y = prev.box.cy + (next.box.cy - prev.box.cy) * factor
        return current.copy(zoom = max(1.8f, current.zoom), centerX = x.coerceIn(0f, 1f), centerY = y.coerceIn(0f, 1f))
    }

    private suspend fun ensureActiveToken(token: Long) {
        currentCoroutineContext().ensureActive()
        if (token != generation) throw CancellationException("Tracking selection replaced")
    }

    private fun frameAt(video: VideoEntity, timeUs: Long): Bitmap =
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, Uri.parse(video.uri))
            loadFrame(retriever, video, timeUs) ?: error("端末でこの時刻の動画フレームを取得できません")
        }

    private fun loadFrame(retriever: MediaMetadataRetriever, video: VideoEntity, timeUs: Long): Bitmap? {
        // 640px max side for ML inspection only. Never resize the actual playback surface.
        val rotated = video.rotationDegrees % 180 != 0
        val width = if (rotated) video.height else video.width
        val height = if (rotated) video.width else video.height
        if (width <= 0 || height <= 0) return null
        val scale = min(1.0, 640.0 / max(width, height))
        val result = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST,
            max(1, (width * scale).toInt()), max(1, (height * scale).toInt())) ?: return null
        val expectedPortrait = height > width
        val actualPortrait = result.height > result.width
        if (expectedPortrait == actualPortrait || width == height) return result
        val fixed = Bitmap.createBitmap(result, 0, 0, result.width, result.height,
            Matrix().apply { postRotate(if (rotated) video.rotationDegrees.toFloat() else 90f) }, true)
        if (fixed !== result) result.recycle()
        return fixed
    }

    private fun <T> withDetector(block: (ObjectDetector) -> T): T {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder()
                .setModelAssetPath("models/tracker/efficientdet_lite0.tflite").build())
            .setRunningMode(RunningMode.IMAGE)
            .setCategoryAllowlist(listOf("person"))
            .setMaxResults(12)
            .setScoreThreshold(.30f)
            .build()
        return ObjectDetector.createFromOptions(context, options).use(block)
    }

    private fun detect(detector: ObjectDetector, bitmap: Bitmap): List<PersonBox> {
        return detector.detect(BitmapImageBuilder(bitmap).build()).detections().mapNotNull { detected ->
            val classification = detected.categories().firstOrNull() ?: return@mapNotNull null
            if (classification.categoryName() != "person") return@mapNotNull null
            val r = detected.boundingBox()
            val l = (r.left / bitmap.width).coerceIn(0f, 1f)
            val t = (r.top / bitmap.height).coerceIn(0f, 1f)
            val right = (r.right / bitmap.width).coerceIn(0f, 1f)
            val bottom = (r.bottom / bitmap.height).coerceIn(0f, 1f)
            if (right - l < .015f || bottom - t < .03f) return@mapNotNull null
            val appearance = ArrayList<Float>(36)
            // Coarse torso/clothing signature; no face embeddings or identity data stored.
            for (yy in listOf(.26f, .40f, .54f, .68f)) for (xx in listOf(.30f, .50f, .70f)) {
                val px = ((l + (right - l) * xx) * (bitmap.width - 1)).toInt().coerceIn(0, bitmap.width - 1)
                val py = ((t + (bottom - t) * yy) * (bitmap.height - 1)).toInt().coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(px, py)
                appearance += android.graphics.Color.red(pixel) / 255f
                appearance += android.graphics.Color.green(pixel) / 255f
                appearance += android.graphics.Color.blue(pixel) / 255f
            }
            PersonBox(l, t, right, bottom, classification.score(), appearance)
        }
    }
}
