package com.caneretabito.motionpin.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import com.caneretabito.motionpin.data.VideoEntity
import com.caneretabito.motionpin.player.VideoViewport
import com.caneretabito.motionpin.player.ViewportGeometry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val batteryManager = context.applicationContext.getSystemService(BatteryManager::class.java)
    private val resourceGate = TrackingResourceGate()
    val state = _state.asStateFlow()
    private var work: Job? = null
    @Volatile private var generation = 0L
    private var matcher: StablePersonMatcher? = null
    // A cancelled native decode/inference may finish asynchronously. Never start a second
    // retriever/model until its predecessor has closed both resources.
    private val nativeSession = Mutex()
    private var sceneGuard = TrackingSceneGuard()
    private val camera = SmoothFollowCamera()
    private var preferredQuality = TrackerQuality.HIGH
    private var preferredFraming = DancerFraming.FULL
    @Volatile private var previewCorrection = 0
    @Volatile private var playbackUnderPressure = false
    @Volatile private var playbackRunning = false
    private val chunkUs = 6_000_000L
    private val aheadThresholdUs = 3_000_000L
    private val maxAnchors = 2400

    private fun resourcePauseReason(): String? {
        val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return when (resourceGate.reason(powerManager.currentThermalStatus,
            capacity in 0..10 && !batteryManager.isCharging,
            powerManager.isPowerSaveMode, playbackUnderPressure)) {
            TrackingResourceGate.Reason.THERMAL -> "高温のため追尾解析を待機中。十分に冷えたら再開します。"
            TrackingResourceGate.Reason.LOW_BATTERY -> "電池残量が少ないため追尾解析を待機中。動画再生を優先します。"
            TrackingResourceGate.Reason.POWER_SAVE -> "省電力モードのため追尾解析を待機中。動画再生を優先します。"
            TrackingResourceGate.Reason.PLAYBACK -> "再生負荷のため追尾解析を待機中。動画再生を優先します。"
            null -> null
        }
    }

    @Synchronized fun stop() {
        generation++
        work?.cancel()
        work = null
        matcher = null
        sceneGuard = TrackingSceneGuard()
        camera.reset()
        previewCorrection = 0
        playbackUnderPressure = false
        playbackRunning = false
        _state.value = DancerTrackingState()
    }

    /** Call after stop() before lending codec/compute resources to another local AI job. */
    suspend fun awaitIdle() { nativeSession.withLock { } }

    @Synchronized fun requestSelection(video: VideoEntity, sourceUs: Long, rotation: Int = 0) {
        stop()
        previewCorrection = ((rotation % 360) + 360) % 360
        val token = generation
        val quality = preferredQuality
        val correction = previewCorrection
        val position = sourceUs.coerceIn(0L, (video.durationUs - 1L).coerceAtLeast(0L))
        _state.value = DancerTrackingState("scanning", video.id,
            quality = preferredQuality, framing = preferredFraming,
            rotationCorrectionDegrees = previewCorrection,
            message = "人物を検出中。端末内だけで解析しています…")
        work = scope.launch(Dispatchers.IO) {
            try {
                nativeSession.withLock {
                    ensureActiveWork(token)
                    resourcePauseReason()?.let { reason ->
                        updateState(token) { it.copy(phase = "lost", message = reason) }
                        return@withLock
                    }
                    val frame = frameAt(video, position, correction)
                    var previewAdopted = false
                    try {
                        ensureActiveWork(token)
                        val people = withDetector(quality) { detector ->
                            ensureActiveWork(token)
                            // MediaPipe 1.1.0 MPImage.close() recycles its Bitmap container. The
                            // displayed selection preview therefore needs independent ownership.
                            val input = frame.copy(Bitmap.Config.ARGB_8888, false)
                                ?: error("人物検出用の画像を確保できません")
                            try { detect(detector, input) }
                            finally { if (!input.isRecycled) input.recycle() }
                        }
                        ensureActiveWork(token)
                        updateState(token) { current ->
                            if (current.videoId != video.id) current else current.copy(
                                phase = "choosing", preview = frame, candidates = people,
                                analyzedUntilUs = position,
                                message = if (people.isEmpty()) "人物を検出できません。別の時刻や軽快モードで試してください。"
                                else "追尾する人物を枠または番号で選択してください")
                        }
                        previewAdopted = true
                    } finally {
                        if (!previewAdopted && !frame.isRecycled) frame.recycle()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                updateStateIfCurrent(token) { it.copy(phase = "lost",
                    message = "人物検出を開始できません: ${e.message ?: e.javaClass.simpleName}") }
            } finally { finishWork(token) }
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
    fun resetCamera() { camera.reset() }

    @Synchronized fun onTimelineDiscontinuity(sourceUs: Long) {
        camera.reset()
        val current = _state.value
        val first = current.keyframes.firstOrNull() ?: return
        if (current.phase !in setOf("scanning", "active")) return
        if (TrackingSampling.seekNeedsSelection(sourceUs, first.sourceTimeUs, current.analyzedUntilUs)) {
            invalidateTracking("解析済みの範囲外へ移動しました。人物を再選択してください。")
        }
    }

    @Synchronized private fun invalidateTracking(message: String) {
        generation++
        work?.cancel()
        work = null
        matcher = null
        _state.update { it.copy(phase = "lost", message = message) }
    }
    fun resumeFromManual(view: VideoViewport, timeUs: Long) {
        camera.resumeFrom(view, timeUs)
        _state.update { it.copy(manualHold = false,
            message = "ゆっくりダンサーの位置へ戻ります。") }
    }

    fun setFraming(framing: DancerFraming) {
        preferredFraming = framing
        _state.update { it.copy(framing = framing) }
    }

    fun setManualHold(hold: Boolean) {
        _state.update { it.copy(manualHold = hold,
            message = if (hold) "追尾の画面移動を一時固定しました。" else "追尾の画面移動を再開しました。") }
        if (!hold) camera.reset()
    }

    @Synchronized fun select(video: VideoEntity, index: Int) {
        val snapshot = _state.value
        if (snapshot.phase != "choosing" || snapshot.videoId != video.id) return
        val chosen = snapshot.candidates.getOrNull(index)?.takeIf { it.valid() } ?: return
        val anchorTime = snapshot.analyzedUntilUs
        generation++
        val token = generation
        work?.cancel()
        matcher = StablePersonMatcher(chosen, anchorTime, snapshot.candidates)
        sceneGuard = TrackingSceneGuard().also { guard ->
            snapshot.preview?.let { guard.reset(sceneSignature(it)) }
        }
        camera.reset()
        _state.value = snapshot.copy(phase = "scanning", preview = null, candidates = emptyList(),
            keyframes = listOf(TrackingKeyframe(anchorTime, chosen)), inspectedFrames = 1,
            matchedFrames = 1,
            message = "追尾する人物を確認して数秒先まで解析中…")
        analyzeAhead(video, token)
    }

    /** Playback always takes priority over initiating the next analysis batch. */
    @Synchronized fun maybeExtend(video: VideoEntity, sourceUs: Long,
                    playbackIsPlaying: Boolean = false, overloaded: Boolean = false) {
        playbackRunning = playbackIsPlaying
        // Buffering reports isPlaying=false while playback still needs decoder priority.
        playbackUnderPressure = overloaded
        val current = _state.value
        if (current.videoId == video.id && current.phase in setOf("scanning", "active") &&
            current.keyframes.isNotEmpty() && sourceUs > current.analyzedUntilUs + chunkUs) {
            invalidateTracking("再生位置に解析が追いつきません。再生を優先して追尾を停止しました。")
            return
        }
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
                nativeSession.withLock {
                    ensureActiveWork(token)
                    val initial = _state.value
                    val identity = matcher ?: return@withLock
                    val cuts = sceneGuard
                    val correction = previewCorrection
                    if (initial.videoId != video.id || initial.keyframes.isEmpty() || initial.phase == "lost")
                        return@withLock
                    val last = initial.keyframes.last()
                    val step = initial.quality.stepUs
                    // Continue after the last inspected sample, including misses. Restarting at the
                    // last MATCHED anchor re-decoded missing frames and fed timestamps backwards.
                    var time = TrackingSampling.nextTimeUs(initial.analyzedUntilUs, last.sourceTimeUs, step)
                    val stopAt = min(video.durationUs - 1L, max(initial.analyzedUntilUs, last.sourceTimeUs) + chunkUs)
                    if (time > stopAt) {
                        updateState(token) { it.copy(phase = "active", analyzedUntilUs = stopAt,
                            message = "動画末尾まで追尾解析しました。") }
                        return@withLock
                    }
                    resourcePauseReason()?.let { reason ->
                        updateState(token) { it.copy(phase = "active", message = reason) }
                        return@withLock
                    }
                    var lastInspected = initial.analyzedUntilUs
                    var samples = 0
                    var successes = 0
                    var pauseReason: String? = null
                    val anchors = ArrayList<TrackingKeyframe>()
                    updateState(token) { it.copy(phase = "scanning", message = "選んだダンサーを先読み解析中…") }
                    fun publish(done: Boolean = false) {
                        val additions = anchors.toList()
                        updateState(token) { latest ->
                            if (latest.videoId != video.id || latest.phase == "lost") latest
                            else {
                                val updated = (latest.keyframes + additions).takeLast(maxAnchors)
                                val sufficient = updated.size >= 4
                                latest.copy(
                                    phase = if (sufficient || done) "active" else "scanning",
                                    keyframes = updated,
                                    analyzedUntilUs = max(latest.analyzedUntilUs, lastInspected),
                                    inspectedFrames = latest.inspectedFrames + samples,
                                    matchedFrames = latest.matchedFrames + additions.size,
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
                            ensureActiveWork(token)
                            retriever.setDataSource(context, Uri.parse(video.uri))
                            while (time <= stopAt) {
                                ensureActiveWork(token)
                                pauseReason = resourcePauseReason()
                                if (pauseReason != null) return@withDetector
                                val frame = loadFrame(retriever, video, time, correction)
                                var cut = false
                                val people = if (frame == null) emptyList() else try {
                                    ensureActiveWork(token)
                                    cut = cuts.isCut(sceneSignature(frame))
                                    if (cut) emptyList() else detect(detector, frame)
                                } finally { if (!frame.isRecycled) frame.recycle() }
                                // Native calls do not necessarily observe Job cancellation. Do not
                                // mutate identity/counters or publish their results after cancellation.
                                ensureActiveWork(token)
                                samples++
                                lastInspected = time
                                if (cut) {
                                    publish()
                                    updateState(token) { it.copy(phase = "lost",
                                        message = "場面の大きな変化を検出しました。別人への切替を防ぐため再選択してください。") }
                                    return@withDetector
                                }
                                val outcome = identity.step(time, people)
                                if (outcome.status == MatchStatus.MATCHED) {
                                    anchors += TrackingKeyframe(time, checkNotNull(outcome.box))
                                    successes++
                                }
                                if (outcome.status == MatchStatus.LOST) {
                                    publish()
                                    updateState(token) { it.copy(phase = "lost",
                                        message = "人物の識別が曖昧になりました。別人へ飛ばさず追尾を停止。再選択できます。") }
                                    return@withDetector
                                }
                                if (samples >= 2) publish()
                                time += step
                                // Give playback/cancellation a scheduling opportunity between samples.
                                // This does not alter source frame rate or promise decoder independence.
                                if (playbackRunning) delay(20L)
                            }
                        }
                    }
                    ensureActiveWork(token)
                    if (_state.value.phase != "lost") {
                        publish(done = true)
                        updateState(token) { it.copy(phase = "active",
                            analyzedUntilUs = if (pauseReason == null && stopAt == video.durationUs - 1L)
                                stopAt else it.analyzedUntilUs,
                            message = when {
                                pauseReason != null -> checkNotNull(pauseReason)
                                successes == 0 -> "人物が見えない区間です。再選択してください。"
                                else -> "先読み完了 · ${successes} 箇所の動きを追尾しました。"
                            }) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                updateStateIfCurrent(token) { it.copy(phase = "lost",
                    message = "追尾解析を停止しました: ${error.message ?: error.javaClass.simpleName}") }
            } finally { finishWork(token) }
        }
    }

    fun viewportAt(videoId: String, timeUs: Long, current: VideoViewport,
                   geometry: ViewportGeometry = ViewportGeometry()): VideoViewport? {
        val currentState = _state.value
        if (currentState.videoId != videoId || currentState.manualHold ||
            currentState.phase !in setOf("scanning", "active", "lost")) return null
        val box = TrackingTrajectory.at(currentState.keyframes, timeUs,
            min(900_000L, currentState.quality.stepUs * 2L)) ?: return null
        return camera.follow(timeUs, box, current, currentState.framing, geometry)
    }

    private suspend fun ensureActiveWork(token: Long) {
        currentCoroutineContext().ensureActive()
        if (token != generation)
            throw CancellationException("Tracking selection changed")
    }

    @Synchronized private fun updateState(token: Long,
        transform: (DancerTrackingState) -> DancerTrackingState) {
        if (token != generation) throw CancellationException("Tracking selection changed")
        _state.update(transform)
    }

    @Synchronized private fun updateStateIfCurrent(token: Long,
        transform: (DancerTrackingState) -> DancerTrackingState) {
        if (token == generation) _state.update(transform)
    }

    @Synchronized private fun finishWork(token: Long) {
        if (token == generation) work = null
    }

    private fun frameAt(video: VideoEntity, timeUs: Long, correction: Int): Bitmap =
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, Uri.parse(video.uri))
            loadFrame(retriever, video, timeUs, correction) ?: error("端末でこの時刻の動画フレームを取得できません")
        }

    private fun loadFrame(retriever: MediaMetadataRetriever, video: VideoEntity, timeUs: Long,
                          correction: Int): Bitmap? {
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
        var owned = result
        try {
            if (expectedPortrait != actualPortrait && width != height) {
                val oriented = Bitmap.createBitmap(owned, 0, 0, owned.width, owned.height,
                    Matrix().apply { postRotate(if (rotated) video.rotationDegrees.toFloat() else 90f) }, true)
                if (oriented !== owned) owned.recycle()
                owned = oriented
            }
            // The *analysis thumbnail only* is converted for the RGB8 model.
            // Original video frames, HDR metadata and SurfaceView playback are untouched.
            if (correction != 0) {
                val displayed = Bitmap.createBitmap(owned, 0, 0, owned.width, owned.height,
                    Matrix().apply { postRotate(correction.toFloat()) }, true)
                if (displayed !== owned) owned.recycle()
                owned = displayed
            }
            if (owned.config != Bitmap.Config.ARGB_8888) {
                val compatible = owned.copy(Bitmap.Config.ARGB_8888, false)
                    ?: error("端末で追尾用のARGB_8888フレームを生成できません")
                owned.recycle()
                owned = compatible
            }
            return owned
        } catch (error: Throwable) {
            owned.recycle()
            throw error
        }
    }

    private suspend fun <T> withDetector(quality: TrackerQuality, block: suspend (ObjectDetector) -> T): T {
        val detector = try {
            ObjectDetector.createFromOptions(context, detectorOptions(quality))
        } catch (error: LinkageError) {
            // Missing/incompatible native libraries or generated classes must use the same
            // visible failure and bitmap-cleanup path as other detector startup errors.
            throw IllegalStateException("追尾AIエンジンを読み込めません。アプリの更新・再起動後にお試しください。", error)
        }
        return detector.use { block(it) }
    }

    private fun detect(detector: ObjectDetector, bitmap: Bitmap): List<PersonBox> {
        // Keep MPImage alive through appearance extraction; close transfers/releases bitmap
        // ownership in MediaPipe 1.1.0. Callers must not retain this inference bitmap.
        return BitmapImageBuilder(bitmap).build().use { image ->
            detector.detect(image).detections().mapNotNull { detected ->
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
                    // A local patch average is less sensitive to one pixel hitting skin, sequins,
                    // motion blur or a compression edge. Keep the same 36-value RGB descriptor.
                    val radius = (min((right - l) * bitmap.width, (bottom - t) * bitmap.height).toInt() / 24)
                        .coerceIn(1, 2)
                    var red = 0f; var green = 0f; var blue = 0f; var count = 0
                    for (y in max(0, py - radius)..min(bitmap.height - 1, py + radius)) {
                        for (x in max(0, px - radius)..min(bitmap.width - 1, px + radius)) {
                            val pixel = bitmap.getPixel(x, y)
                            red += android.graphics.Color.red(pixel)
                            green += android.graphics.Color.green(pixel)
                            blue += android.graphics.Color.blue(pixel)
                            count++
                        }
                    }
                    appearance += red / (255f * count)
                    appearance += green / (255f * count)
                    appearance += blue / (255f * count)
                }
                PersonBox(l, t, right, bottom, classification.score(), appearance)
            }
        }
    }

    private fun sceneSignature(bitmap: Bitmap): FloatArray = FloatArray(12 * 8 * 3).also { signature ->
        for (y in 0 until 8) for (x in 0 until 12) {
            val pixel = bitmap.getPixel(((x + .5f) * bitmap.width / 12).toInt().coerceAtMost(bitmap.width - 1),
                ((y + .5f) * bitmap.height / 8).toInt().coerceAtMost(bitmap.height - 1))
            val offset = (y * 12 + x) * 3
            signature[offset] = android.graphics.Color.red(pixel) / 255f
            signature[offset + 1] = android.graphics.Color.green(pixel) / 255f
            signature[offset + 2] = android.graphics.Color.blue(pixel) / 255f
        }
    }

    companion object {
        /** Shared by the production path and the offline Android model smoke test. */
        internal fun detectorOptions(quality: TrackerQuality): ObjectDetector.ObjectDetectorOptions =
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(quality.modelFile).build())
                .setRunningMode(RunningMode.IMAGE)
                .setCategoryAllowlist(listOf("person"))
                .setMaxResults(24)
                .setScoreThreshold(if (quality == TrackerQuality.HIGH) .22f else .30f)
                .build()
    }
}
