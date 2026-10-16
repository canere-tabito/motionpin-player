package com.caneretabito.motionpin.ui

import android.app.Application
import android.net.Uri
import android.view.Display
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caneretabito.motionpin.MotionRuntime
import com.caneretabito.motionpin.data.*
import com.caneretabito.motionpin.player.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    val runtime = MotionRuntime.get(application)
    val repository = runtime.repository
    val ai = runtime.ai
    val player = RetainedPlayer(application, viewModelScope)
    val videos = repository.videos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pins = repository.pins.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val channels = repository.channels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val channelVideos = repository.channelVideos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val jobs = repository.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val libraryState = repository.libraryState
    val currentVideo = MutableStateFlow<VideoEntity?>(null)
    val currentPin = MutableStateFlow<PinEntity?>(null)
    val inspection = MutableStateFlow<PlaybackInspection?>(null)
    val busy = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    val editor = MutableStateFlow<PinEntity?>(null)
    val weakRelink = MutableStateFlow<Pair<String, Uri>?>(null)
    var display: Display? = null
    private var openJob: Job? = null
    private var playJob: Job? = null
    private var defaultSpeed = 1f
    private var defaultMuted = false

    init {
        viewModelScope.launch {
            player.setFill(repository.getSetting("fill") == "true")
            defaultSpeed = repository.getSetting("playbackSpeed")?.toFloatOrNull()?.takeIf { it in listOf(.25f, .5f, .75f, 1f, 1.5f, 2f) } ?: 1f
            defaultMuted = repository.getSetting("muted") == "true"
        }
        viewModelScope.launch {
            while (true) {
                delay(3_000)
                runCatching { savePlaybackPosition() }.onFailure { message.value = "続きの位置を保存できませんでした。${it.message ?: ""}" }
            }
        }
        refresh()
    }

    fun refresh() = operation { repository.scanLibrary(); repository.refreshAvailability() }
    fun importVideo(uri: Uri) = operation("動画を確認しています…") { repository.importVideo(uri); message.value = "動画をライブラリに追加しました。" }
    fun notify(text: String) { message.value = text }
    fun dismissMessage() { message.value = null }

    fun openVideo(video: VideoEntity) = open(video, null, preferAi = false)
    fun openPin(pin: PinEntity, preferAi: Boolean = true) {
        cancelPlaybackTransition()
        openJob = viewModelScope.launch {
            val video = repository.getVideo(pin.videoId)
            if (video == null) notify("元動画の登録が見つかりません。バックアップから復元してください。")
            else open(video, pin, preferAi)
        }
    }

    private fun open(video: VideoEntity, pin: PinEntity?, preferAi: Boolean, sourcePosition: Long? = null, play: Boolean = true,
                     viewportOverride: VideoViewport? = null, loopOverride: Boolean? = null) {
        cancelPlaybackTransition()
        openJob = viewModelScope.launch {
            busy.value = "動画と端末の再生能力を確認しています…"
            try {
                savePlaybackPosition()
                player.pause()
                if (video.needsRelink || video.uri.isBlank()) error("この動画には再関連付けが必要です。ライブラリの「再関連付け」から元動画を選択してください。")
                val job = pin?.aiJobId?.let { repository.getJob(it) }
                val cacheCandidate = job?.takeIf { preferAi && pin != null && it.status == JobStatus.COMPLETED && it.startUs == pin.startUs && it.endUs == pin.endUs && it.speedNumerator == pin.speedNumerator && it.speedDenominator == pin.speedDenominator }
                val checkedCache = cacheCandidate?.let {
                    busy.value = "元動画とAIキャッシュの完全ハッシュを照合しています…"
                    ai.validateCache(it.id)
                }
                val checkedVideo = if (pin != null && checkedCache == null) {
                    busy.value = "ピンの元動画と現在のファイルの完全ハッシュを照合しています…"
                    repository.ensureFingerprint(video.id)
                } else repository.getVideo(video.id) ?: video
                if (checkedVideo.needsRelink) error("動画の内容が変更されています。元動画を再関連付けしてください。")
                val sourceUri = Uri.parse(checkedVideo.uri)
                val details = withContext(Dispatchers.IO) { PlaybackDiagnostics.inspect(getApplication(), sourceUri, display) }
                inspection.value = details
                details.blockedReason?.let { error(it) }
                if (checkedVideo.durationUs <= 0) error("動画の長さを確認できません。元動画を再関連付けしてください。")
                val freshVideo = repository.getVideo(video.id) ?: video
                val cache = checkedCache?.takeIf { isUsableCache(it, freshVideo, pin) }
                val start = pin?.takeIf { it.endUs != null }?.startUs ?: 0L
                val end = pin?.endUs ?: checkedVideo.durationUs
                val timeline = if (cache != null) PlaybackTimeline(cache.startUs, cache.endUs,
                    cache.speedNumerator.toDouble() / cache.speedDenominator, cached = true)
                    else PlaybackTimeline(start, end)
                val position = (sourcePosition ?: pin?.startUs ?: video.resumePositionUs).coerceIn(timeline.sourceStartUs, timeline.sourceEndUs - 1)
                currentCoroutineContext().ensureActive()
                busy.value = "AI処理を安全な境界で待機させています…"
                ai.awaitPlaybackReady()
                player.open(cache?.outputPath?.let { Uri.fromFile(File(it)) } ?: sourceUri, checkedVideo.durationUs, timeline, position,
                    pin?.speed ?: defaultSpeed, loopOverride ?: pin?.loop ?: false,
                    viewportOverride ?: pin?.let { VideoViewport(it.zoomScale, it.zoomCenterX, it.zoomCenterY) } ?: VideoViewport(),
                    pin?.let { it.audioMode == "muted" } ?: defaultMuted, details.aspect, play)
                currentVideo.value = freshVideo
                currentPin.value = pin
                repository.updateVideo(freshVideo.copy(lastPlayedAt = System.currentTimeMillis(), resumePositionUs = position))
                if (preferAi && pin?.aiJobId != null && cache == null) notify("検査済みのAIキャッシュがないため元動画を再生しています。AIジョブの状態を確認してください。")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                message.value = e.message ?: "動画を開けません。権限と元ファイルを確認してください。"
                runCatching { player.stopForProcessing() }.onSuccess { ai.setPlaybackActive(false) }
            }
            finally { busy.value = null }
        }
    }

    private fun isUsableCache(job: AiJobEntity, video: VideoEntity, pin: PinEntity?): Boolean {
        if (pin == null || job.status != JobStatus.COMPLETED || job.outputPath == null || job.sourceRevision != video.revision ||
            job.startUs != pin.startUs || job.endUs != pin.endUs || job.speedNumerator != pin.speedNumerator || job.speedDenominator != pin.speedDenominator ||
            job.sourceFingerprint.isNullOrBlank() || job.sourceFingerprint != video.fingerprintSha256) return false
        val file = File(job.outputPath)
        val root = getApplication<Application>().filesDir.canonicalPath + File.separator
        val cacheRoot = getApplication<Application>().cacheDir.canonicalPath + File.separator
        return file.isFile && file.length() > 0 && (file.canonicalPath.startsWith(root) || file.canonicalPath.startsWith(cacheRoot))
    }

    fun compareSourceCache() {
        val video = currentVideo.value ?: return
        val pin = currentPin.value ?: return
        val state = player.state.value
        open(video, pin, !state.cached, state.sourcePositionUs, state.isPlaying, state.viewport, state.loop)
    }

    fun closePlayer() {
        cancelPlaybackTransition()
        player.pause()
        playJob = operation { savePlaybackPosition(); player.stopForProcessing(); currentVideo.value = null; currentPin.value = null; ai.setPlaybackActive(false) }
    }

    private fun cancelPlaybackTransition() { openJob?.cancel(); playJob?.cancel() }

    fun playPause() {
        cancelPlaybackTransition()
        if (player.exo.isPlaying) { player.pause(); return }
        playJob = operation("再生の準備をしています…") {
            ai.awaitPlaybackReady()
            currentCoroutineContext().ensureActive()
            if (player.exo.playbackState == androidx.media3.common.Player.STATE_IDLE) player.exo.prepare()
            player.exo.play()
        }
    }
    fun setFill(fill: Boolean) { player.setFill(fill); operation { repository.setSetting("fill", fill.toString()) } }
    fun setSpeed(speed: Float) { defaultSpeed = speed; player.setSpeed(speed); operation { repository.setSetting("playbackSpeed", speed.toString()) } }
    fun setMuted(muted: Boolean) { defaultMuted = muted; player.setMuted(muted); operation { repository.setSetting("muted", muted.toString()) } }

    fun foreground(visible: Boolean) {
        runtime.setVisible(visible)
        if (!visible) {
            cancelPlaybackTransition(); player.pause()
            playJob = operation { savePlaybackPosition(); player.stopForProcessing(); ai.setPlaybackActive(false) }
        }
        else if (currentVideo.value != null && player.exo.playWhenReady) ai.setPlaybackActive(true)
    }

    private suspend fun savePlaybackPosition() {
        val video = currentVideo.value ?: return
        val latest = repository.getVideo(video.id) ?: return
        repository.updateVideo(latest.copy(resumePositionUs = player.state.value.sourcePositionUs, lastPlayedAt = System.currentTimeMillis()))
    }

    fun savePoint() {
        val video = currentVideo.value ?: return
        val state = player.state.value
        operation {
            repository.savePin(PinEntity(videoId = video.id, startUs = state.sourcePositionUs.coerceIn(0, video.durationUs - 1),
                title = "${formatTimestamp(state.sourcePositionUs)} の瞬間", speedNumerator = speedFraction(state.requestedSpeed).first,
                speedDenominator = speedFraction(state.requestedSpeed).second, loop = state.loop, zoomScale = state.viewport.zoom,
                zoomCenterX = state.viewport.centerX, zoomCenterY = state.viewport.centerY, audioMode = if (state.muted) "muted" else "original"))
            message.value = "再生を続けながらピンを保存しました。"
        }
    }

    fun editRange() {
        val video = currentVideo.value ?: return
        val state = player.state.value
        editor.value = PinEntity(videoId = video.id, startUs = (state.sourcePositionUs - 5_000_000).coerceAtLeast(0),
            endUs = (state.sourcePositionUs + 5_000_000).coerceAtMost(video.durationUs), title = "お気に入りのシーン",
            speedNumerator = speedFraction(state.requestedSpeed).first, speedDenominator = speedFraction(state.requestedSpeed).second,
            loop = true, zoomScale = state.viewport.zoom, zoomCenterX = state.viewport.centerX, zoomCenterY = state.viewport.centerY,
            audioMode = if (state.muted) "muted" else "original")
    }

    fun savePin(pin: PinEntity) = operation {
        val old = repository.getPin(pin.id)
        val changedAiInputs = old != null && (old.startUs != pin.startUs || old.endUs != pin.endUs || old.speedNumerator != pin.speedNumerator || old.speedDenominator != pin.speedDenominator)
        repository.savePin(pin.copy(updatedAt = System.currentTimeMillis(), aiJobId = if (changedAiInputs) null else pin.aiJobId))
        editor.value = null
        message.value = "ピンを保存しました。"
    }
    fun deletePin(pin: PinEntity) = operation { repository.deletePin(pin.id); message.value = "ピンを削除しました。元動画は変更していません。" }
    fun addChannel(name: String) = operation { repository.addChannel(name.trim()); message.value = "コレクションを作成しました。" }
    fun addToChannel(channel: ChannelEntity, video: VideoEntity) = operation { repository.addToChannel(channel.id, video.id); message.value = "${channel.name} に追加しました。" }
    fun removeFromChannel(channel: ChannelEntity, video: VideoEntity) = operation { repository.removeFromChannel(channel.id, video.id); message.value = "コレクションから外しました。元動画は保持されます。" }
    fun deleteChannel(channel: ChannelEntity) = operation { repository.deleteChannel(channel.id); message.value = "コレクションを削除しました。元動画とピンは保持されます。" }
    fun exportBackup(uri: Uri) = operation("バックアップを書き出しています…") { repository.exportBackup(uri); message.value = "バックアップを書き出しました。動画本体・AIキャッシュ・権限は含まれません。" }
    fun importBackup(uri: Uri) = operation("バックアップを検査しています…") { val result = repository.importBackup(uri); message.value = "${result.message}\n再インストール後は動画の再許可・再関連付けが必要です。" }
    fun clearCaches() = operation("AIキャッシュを整理しています…") {
        val count = ai.clearCompletedCaches()
        message.value = "$count 件のAIキャッシュを削除しました。元動画・ピンは保持され、必要な区間を再生成できます。"
    }
    fun relink(videoId: String, uri: Uri, confirmWeak: Boolean = false) = operation("元動画との一致を確認しています…") {
        val result = repository.relink(videoId, uri, confirmWeak)
        when (result) {
            is RelinkResult.Linked -> { message.value = if (result.strongMatch) "内容の完全一致を確認して再関連付けしました。" else "確認した動画へ再関連付けしました。AI区間は再生成してください。"; weakRelink.value = null }
            is RelinkResult.ConfirmationRequired -> weakRelink.value = videoId to uri
            is RelinkResult.Rejected -> { message.value = result.message; weakRelink.value = null }
        }
    }
    fun enqueue(pin: PinEntity): Job {
        cancelPlaybackTransition()
        return operation("AI区間を準備しています…") {
        val video = repository.getVideo(pin.videoId) ?: error("元動画が見つかりません。")
        if (pin.endUs == null) error("AI補間には区間ピンを指定してください。")
        if (pin.speed !in listOf(.5f, .25f)) error("AI補間の出力速度は0.5×または0.25×です。ピンの速度を編集してください。")
        savePlaybackPosition()
        player.stopForProcessing()
        ai.setPlaybackActive(false)
        ai.enqueue(video, pin)
        runtime.prepareProcessing()
        message.value = "AI区間生成を予約しました。進捗と停止理由は「ピン」で確認できます。音声はミュートです。"
        }.also { playJob = it }
    }
    fun cancelJob(job: AiJobEntity) { ai.cancel(job.id) }
    fun resumeJob(job: AiJobEntity): Job {
        cancelPlaybackTransition()
        return operation("AI区間を再開しています…") {
        savePlaybackPosition(); player.stopForProcessing(); ai.setPlaybackActive(false)
        ai.resume(job.id); runtime.prepareProcessing()
        }.also { playJob = it }
    }

    private fun operation(label: String? = null, block: suspend () -> Unit): Job = viewModelScope.launch {
        if (label != null) busy.value = label
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { message.value = e.message ?: "処理に失敗しました。もう一度お試しください。" }
        finally { if (label != null) busy.value = null }
    }
    override fun onCleared() { player.release(); ai.setPlaybackActive(false) }
}

fun speedFraction(speed: Float): Pair<Long, Long> = when (speed) {
    .25f -> 1L to 4L; .5f -> 1L to 2L; .75f -> 3L to 4L; 1.5f -> 3L to 2L; 2f -> 2L to 1L; else -> 1L to 1L
}
