package com.caneretabito.motionpin.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

data class PlaybackState(
    val sourcePositionUs: Long = 0,
    val durationUs: Long = 1,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val cached: Boolean = false,
    val requestedSpeed: Float = 1f,
    val muted: Boolean = false,
    val loop: Boolean = false,
    val ended: Boolean = false,
    val replay: Boolean = false,
    val viewport: VideoViewport = VideoViewport(),
    val fill: Boolean = false,
    val aspect: Float = 16f / 9f,
    val decoderName: String = "未選択",
    val decoderHardware: String = "未確認",
    val renderedFrames: Int = 0,
    val droppedFrames: Int = 0,
    val error: String? = null,
)

/** Owned by AndroidViewModel, never by a Composable or Activity. SurfaceView can be reattached. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RetainedPlayer(context: Context, scope: CoroutineScope) {
    val exo: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()
    private val sourceFactory = DefaultMediaSourceFactory(context.applicationContext)
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    private var timeline: PlaybackTimeline? = null
    private var uri: Uri? = null
    private var sourceFullDurationUs = 1L
    private var temporarySpeed = false
    private data class ReplayRestore(val uri: Uri, val timeline: PlaybackTimeline,
        val positionUs: Long, val durationUs: Long, val speed: Float, val loop: Boolean,
        val viewport: VideoViewport, val muted: Boolean, val aspect: Float)
    private var replayRestore: ReplayRestore? = null
    private val decoderActive = MutableStateFlow(false)

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { update { copy(isPlaying = isPlaying) } }
            override fun onPlaybackStateChanged(playbackState: Int) {
                update { copy(buffering = playbackState == Player.STATE_BUFFERING, ended = playbackState == Player.STATE_ENDED) }
            }
            override fun onPlayerError(error: PlaybackException) { update { copy(error = "${error.errorCodeName}: ${error.message ?: "再生できません"}", buffering = false) } }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) update { copy(aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height) }
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                decoderActive.value = true
                val codec = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == decoderName }
                update { copy(decoderName = decoderName, decoderHardware = codec?.let { if (it.isHardwareAccelerated) "HW" else "SW" } ?: "未確認") }
            }
            override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) { decoderActive.value = false }
        })
        scope.launch {
            while (true) {
                val map = timeline
                if (map != null) {
                    val counters = exo.videoDecoderCounters
                    counters?.ensureUpdated()
                    update { copy(sourcePositionUs = map.toSourceUs(exo.currentPosition.coerceAtLeast(0) * 1_000),
                        renderedFrames = counters?.renderedOutputBufferCount ?: 0, droppedFrames = counters?.droppedBufferCount ?: 0) }
                }
                delay(150)
            }
        }
    }

    private fun update(block: PlaybackState.() -> PlaybackState) { mutable.value = block(mutable.value) }

    fun open(uri: Uri, fullDurationUs: Long, timeline: PlaybackTimeline, positionUs: Long, speed: Float,
             loop: Boolean, viewport: VideoViewport, muted: Boolean, aspect: Float, play: Boolean = true,
             clipMediaStartUs: Long? = null, clipMediaEndUs: Long? = null) {
        replayRestore = null
        this.uri = uri
        this.timeline = timeline
        sourceFullDurationUs = fullDurationUs
        temporarySpeed = false
        val item = MediaItem.fromUri(uri)
        val source = sourceFactory.createMediaSource(item)
        // Loop boundaries belong to the media source; the UI ticker never implements looping.
        val playable = when {
            clipMediaStartUs != null && clipMediaEndUs != null ->
                ClippingMediaSource(source, clipMediaStartUs, clipMediaEndUs)
            !timeline.cached && (timeline.sourceStartUs > 0 || timeline.sourceEndUs < fullDurationUs) ->
                ClippingMediaSource(source, timeline.sourceStartUs, timeline.sourceEndUs)
            else -> source
        }
        exo.setMediaSource(playable, timeline.toPlayerUs(positionUs) / 1_000)
        exo.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exo.playbackParameters = PlaybackParameters(timeline.effectivePlayerSpeed(speed), 1f)
        exo.volume = if (muted || timeline.cached) 0f else 1f
        mutable.value = PlaybackState(sourcePositionUs = positionUs.coerceIn(0, fullDurationUs - 1), durationUs = fullDurationUs,
            cached = timeline.cached, requestedSpeed = speed, muted = muted || timeline.cached, loop = loop,
            viewport = viewport.bounded(), fill = mutable.value.fill, aspect = aspect)
        exo.prepare()
        exo.playWhenReady = play
    }

    /** The immediately preceding source-time window, looped without AI generation or source export. */
    fun toggleInstantReplay(durationUs: Long = 3_000_000L): Boolean {
        replayRestore?.let { old ->
            replayRestore = null
            open(old.uri, old.durationUs, old.timeline, old.positionUs, old.speed, old.loop,
                old.viewport, old.muted, old.aspect, play = true)
            return false
        }
        val source = uri ?: return false
        val activeTimeline = timeline ?: return false
        val old = mutable.value
        val end = old.sourcePositionUs.coerceIn(activeTimeline.sourceStartUs + 1L, activeTimeline.sourceEndUs)
        val start = maxOf(activeTimeline.sourceStartUs, end - durationUs.coerceAtLeast(500_000L))
        if (end - start < 250_000L) return false
        val restore = ReplayRestore(source, activeTimeline, old.sourcePositionUs, sourceFullDurationUs,
            old.requestedSpeed, old.loop, old.viewport, old.muted, old.aspect)
        val loopTimeline = PlaybackTimeline(start, end, activeTimeline.bakedSpeed, activeTimeline.cached)
        val mediaStart = if (activeTimeline.cached) activeTimeline.toPlayerUs(start) else null
        val mediaEnd = if (activeTimeline.cached) activeTimeline.toPlayerUs(end) + 1L else null
        open(source, sourceFullDurationUs, loopTimeline, start, old.requestedSpeed, true, old.viewport,
            old.muted, old.aspect, play = true, clipMediaStartUs = mediaStart, clipMediaEndUs = mediaEnd)
        replayRestore = restore
        update { copy(replay = true) }
        return true
    }

    fun playPause() { if (exo.isPlaying) exo.pause() else exo.play() }
    fun pause() { exo.pause() }
    suspend fun stopForProcessing() {
        exo.pause()
        exo.stop()
        // stop() is asynchronous. This message is queued on the same playback looper after it,
        // so even a pending prepare/decoder initialization has finished releasing before handoff.
        val barrier = exo.createMessage { _, _ -> }.setLooper(exo.playbackLooper).send()
        val delivered = withContext(Dispatchers.IO) { barrier.blockUntilDelivered(15_000) }
        check(delivered) { "再生スレッドの停止を確認できません。AI処理は開始しません。" }
        // Wait for the renderer's actual release event before granting the AI job GPU admission.
        try { withTimeout(15_000) { decoderActive.first { !it } } }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) { error("再生decoderの解放を確認できません。AI処理は開始しません。ライブラリに戻って再試行してください。") }
    }
    fun seekSource(positionUs: Long) { timeline?.let { exo.seekTo(it.toPlayerUs(positionUs) / 1_000); update { copy(sourcePositionUs = it.toSourceUs(it.toPlayerUs(positionUs))) } } }
    fun seekBy(deltaUs: Long) = seekSource(mutable.value.sourcePositionUs + deltaUs)
    fun setSpeed(speed: Float) {
        update { copy(requestedSpeed = speed) }
        if (!temporarySpeed) exo.playbackParameters = PlaybackParameters(timeline?.effectivePlayerSpeed(speed) ?: speed, 1f)
    }
    fun setTemporarySlow(active: Boolean) {
        // A cache already contains slow motion: applying 0.5 again would violate the timeline contract.
        if (mutable.value.cached) return
        temporarySpeed = active
        exo.playbackParameters = PlaybackParameters(if (active) .5f else mutable.value.requestedSpeed, 1f)
    }
    fun setMuted(muted: Boolean) { update { copy(muted = muted || cached) }; exo.volume = if (mutable.value.muted) 0f else 1f }
    fun setLoop(loop: Boolean) { exo.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF; update { copy(loop = loop) } }
    fun setViewport(viewport: VideoViewport) { update { copy(viewport = viewport.bounded()) } }
    fun setFill(fill: Boolean) { update { copy(fill = fill) } }
    fun clearError() { update { copy(error = null) } }
    fun release() { exo.release() }
}
