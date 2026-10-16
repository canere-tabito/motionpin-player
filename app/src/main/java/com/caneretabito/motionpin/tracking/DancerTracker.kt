package com.caneretabito.motionpin.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.PowerManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

enum class TrackerQuality(val title: String, val stepUs: Long, val modelFile: String) {
    HIGH("高精度", 250_000L, "models/tracker/efficientdet_lite2.tflite"),
    BALANCED("軽快", 500_000L, "models/tracker/efficientdet_lite0.tflite")
}

data class DancerTrackingState(
    val phase: String = "idle",
    val videoId: String? = null,
    val preview: Bitmap? = null,
    val candidates: List<PersonBox> = emptyList(),
    val keyframes: List<TrackingKeyframe> = emptyList(),
    val analyzedUntilUs: Long = 0L,
    val quality: TrackerQuality = TrackerQuality.HIGH,
    val framing: DancerFraming = DancerFraming.FULL,
    val manualHold: Boolean = false,
    val rotationCorrectionDegrees: Int = 0,
    val inspectedFrames: Int = 0,
    val matchedFrames: Int = 0,
    val message: String = ""
)

/**
 * A bounded ahead-of-playback analysis; never modifies/copies original video or AI cache.
 * Decode uses low-resolution samples on Dispatchers.IO; playback still uses native SurfaceView.
 */
class DancerTracker(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(DancerTrackingState())
    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
    val state = _state.asStateFlow()
    private var work: Job? = null
    @Volatile private var generation = 0L
    private var matcher: StablePersonMatcher? = null
    private val camera = SmoothFollowCamera()
    private var preferredQuality = TrackerQuality.HIGH
    private var preferredFraming = DancerFraming.FULL
    @Volatile private var previewCorrection = 0
    @Volatile private var playbackUnderPressure = false
    private val chunkUs = 6_000_000L
    private val aheadThresholdUs = 3_000_000L
    private val maxAnchors = 2400

    private fun resourcePauseReason(): String? = when {
        powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
            "高温のため追尾解析を停止しました。端末が冷えたら再開します。"
        powerManager.isPowerSaveMode ->
            "省電力モードのため追尾解析を待機中。動画再生を優先します。"
        playbackUnderPressure ->
            "動画のフレーム落ちを検出したため追尾解析を待機中。"
        else -> null
    }

    fun stop() {
        generation++
        work?.cancel()
        work = null
        matcher = null
        camera.reset()
        previewCorrection = 0
        playbackUnderPressure = false
        _state.value = DancerTrackingState()
    }

    fun requestSelection(video: VideoEntity, sourceUs: Long, rotation: Int = 0) {
        stop()
        previewCorrection = ((rotation % 360) + 360) % 360
        val token = generation
        val position = sourceUs.coerceIn(0L, (video.durationUs - 1L).coerceAtLeast(0L))
        _state.value = DancerTrackingState("scanning", video.id,
            quality = preferredQuality, framing = preferredFraming,
            rotationCorrectionDegrees = previewCorrection,
            message = "人物を検出中。端末内だけで解析しています…")
        work = scope.launch(Dispatchers.IO) {
            try {
                val frame = frameAt(video, position)
                val people = try { withDetector(preferredQuality) { detect(it, frame) } }
                    catch (e: Exception) { frame.recycle(); throw e }
                ensureActiveToken(token)
                _state.update { current ->
                    if (current.videoId != video.id) current else current.copy(
                        phase = "choosing", preview = frame, candidates = people,
                        analyzedUntilUs = position,
                        message = if (people.isEmpty()) "人物を検出できません。別の時刻や軽快モードで試してください。"
                        else "追尾する人物を枠または番号で選択してください")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (token == generation) _state.update { it.copy(phase = "lost",
                    message = "人物検出を開始できません: ${e.message ?: e.javaClass.simpleName}") }
            } finally { if (token == generation) work = null }
        }
    }

    fun changeQuality(video: VideoEntity, timeUs: Long, quality: TrackerQuality) {
        preferredQuality = quality
        requestSelection(video, timeUs, _state.value.rotationCorrectionDegrees)
    }

    fun rotatePreview(video: VideoEntity, sourceUs: Long) {
        requestSelection(video, sourceUs, (_state.value.rotationCorrectionDegrees + 90) % 360)
    }

    fun setUserZoom(zoom: Float) { camera.setUserZoom(zoom) }

    fun setFraming(framing: DancerFraming) {
        preferredFraming = framing
        _state.update { it.copy(framing = framing) }
    }

    fun setManualHold(hold: Boolean) {
        _state.update { it.copy(manualHold = hold,
            message = if (hold) "追尾の画面移動を一時固定しました。" else "追尾の画面移動を再開しました。") }
        if (!hold) camera.reset()
    }

    fun select(video: VideoEntity, index: Int) {
        val snapshot = _state.value
        if (snapshot.phase != "choosing" || snapshot.videoId != video.id) return
        val chosen = snapshot.candidates.getOrNull(index)?.takeIf { it.valid() } ?: return
        val anchorTime = snapshot.analyzedUntilUs
        generation++
        val token = generation
        work?.cancel()
        matcher = StablePersonMatcher(chosen, anchorTime)
        camera.reset()
        _state.value = snapshot.copy(phase = "scanning", preview = null, candidates = emptyList(),
            keyframes = listOf(TrackingKeyframe(anchorTime, chosen)), inspectedFrames = 1,
            message = "追尾する人物を確認して数秒先まで解析中…")
        analyzeAhead(video, token)
    }

    /** Playback always takes priority over initiating the next analysis batch. */
    fun maybeExtend(video: VideoEntity, sourceUs: Long,
                    playbackIsPlaying: Boolean = false, overloaded: Boolean = false) {
        playbackUnderPressure = playbackIsPlaying && overloaded
        val current = _state.value
        if (current.videoId != video.id || current.phase != "active" || work?.isActive == true) return
        if (current.keyframes.isNotEmpty() && sourceUs < current.keyframes.first().sourceTimeUs - 250_000L) {
            _state.update { it.copy(message = "追尾できる時間より前へ移動しました。人物を再選択してください。") }
            return
        }
        if (sourceUs < current.analyzedUntilUs - aheadThresholdUs ||
            current.analyzedUntilUs >= video.durationUs - 1L) return
        val pauseReason = resourcePauseReason()
        if (pauseReason != null) {
            if (current.message != pauseReason) _state.update { it.copy(message = pauseReason) }
            return
        }
        analyzeAhead(video, generation)
    }

    private fun analyzeAhead(video: VideoEntity, token: Long) {
        work = scope.launch(Dispatchers.IO) {
            try {
                val initial = _state.value
                val identity = matcher ?: return@launch
                if (initial.videoId != video.id || initial.keyframes.isEmpty() || initial.phase == "lost") return@launch
                val last = initial.keyframes.last()
                val step = initial.quality.stepUs
                val stopAt = min(video.durationUs - 1L, last.sourceTimeUs + chunkUs)
                if (stopAt <= last.sourceTimeUs) {
                    _state.update { it.copy(phase = "active", message = "動画末尾まで追尾解析しました。") }
                    return@launch
                }
                var time = last.sourceTimeUs + step
                var samples = 0
                var successes = 0
                var throttled = false
                val anchors = ArrayList<TrackingKeyframe>()
                _state.update { it.copy(phase = "scanning", message = "選んだダンサーを先読み解析中…") }
                fun publish(atUs: Long, done: Boolean = false) {
                    ensureActiveToken(token)
                    val additions = anchors.toList()
                    val matched = additions.size
                    _state.update { latest ->
                        if (latest.videoId != video.id || latest.phase == "lost") latest
                        else {
                            val updated = (latest.keyframes + additions).takeLast(maxAnchors)
                            val sufficient = updated.size >= 4
                            latest.copy(
                                phase = if (sufficient || done) "active" else "scanning",
                                keyframes = updated,
                                analyzedUntilUs = max(latest.analyzedUntilUs, atUs),
                                inspectedFrames = latest.inspectedFrames + samples,
                                matchedFrames = latest.matchedFrames + matched,
                                message = when {
                                    !sufficient -> "追尾対象を確認中…"
                                    done -> "数秒先まで追尾準備完了"
                                    else -> "追尾可能 · ${updated.size} 点の位置を解析済み"
                                })
                        }
                    }
                    anchors.clear()
                    samples = 0
                }
                withDetector(initial.quality) { detector ->
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(context, Uri.parse(video.uri))
                        while (time <= stopAt) {
                            ensureActiveToken(token)
                            val frame = loadFrame(retriever, video, time)
                            val people = if (frame == null) emptyList() else try {
                                detect(detector, frame)
                            } finally { frame.recycle() }
                            val outcome = identity.step(time, people)
                            samples++
                            if (outcome.status == MatchStatus.MATCHED) {
                                anchors += TrackingKeyframe(time, checkNotNull(outcome.box))
                                successes++
                            }
                            if (outcome.status == MatchStatus.LOST) {
                                publish(time)
                                _state.update { it.copy(phase = "lost",
                                    message = "人物の識別が曖昧になりました。別人へ飛ばさず追尾を停止。再選択できます。") }
                                return@withDetector
                            }
                            if (samples >= 2) publish(time)
                            val pauseReason = resourcePauseReason()
                            if (pauseReason != null) {
                                throttled = true
                                publish(time)
                                return@withDetector
                            }
                            time += step
                        }
                    }
                }
                ensureActiveToken(token)
                if (_state.value.phase != "lost") {
                    publish(if (throttled) time else min(time - step, stopAt), !throttled)
                    _state.update { it.copy(phase = "active",
                        message = when {
                            throttled -> resourcePauseReason() ?: "追尾の先読みを中断しました。"
                            successes == 0 -> "人物が見えない区間です。再選択してください。"
                            else -> "先読み完了 · ${successes} 箇所の動きを追尾しました。"
                        }) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (token == generation) _state.update { it.copy(phase = "lost",
                    message = "追尾解析を停止しました: ${error.message ?: error.javaClass.simpleName}") }
            } finally { if (token == generation) work = null }
        }
    }

    fun viewportAt(videoId: String, timeUs: Long, current: VideoViewport): VideoViewport? {
        val currentState = _state.value
        if (currentState.videoId != videoId || currentState.manualHold ||
            currentState.phase !in setOf("scanning", "active", "lost")) return null
        val box = TrackingTrajectory.at(currentState.keyframes, timeUs) ?: return null
        return camera.follow(timeUs, box, current, currentState.framing)
    }

    private fun ensureActiveToken(token: Long) {
        if (token != generation || Thread.currentThread().isInterrupted)
            throw CancellationException("Tracking selection changed")
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
        val oriented = if (expectedPortrait == actualPortrait || width == height) result else {
            Bitmap.createBitmap(result, 0, 0, result.width, result.height,
                Matrix().apply { postRotate(if (rotated) video.rotationDegrees.toFloat() else 90f) }, true)
                .also { if (it !== result) result.recycle() }
        }
        // The *analysis thumbnail only* is converted for the RGB8 model.
        // Original video frames, HDR metadata and SurfaceView playback are untouched.
        val angle = previewCorrection
        val displayed = if (angle == 0) oriented else Bitmap.createBitmap(oriented, 0, 0,
            oriented.width, oriented.height, Matrix().apply { postRotate(angle.toFloat()) }, true)
            .also { if (it !== oriented) oriented.recycle() }
        if (displayed.config == Bitmap.Config.ARGB_8888) return displayed
        val compatible = displayed.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("端末で追尾用のARGB_8888フレームを生成できません")
        displayed.recycle()
        return compatible
    }

    private fun <T> withDetector(quality: TrackerQuality, block: (ObjectDetector) -> T): T {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder()
                .setModelAssetPath(quality.modelFile).build())
            .setRunningMode(RunningMode.IMAGE)
            .setCategoryAllowlist(listOf("person"))
            .setMaxResults(24)
            .setScoreThreshold(if (quality == TrackerQuality.HIGH) .22f else .30f)
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
