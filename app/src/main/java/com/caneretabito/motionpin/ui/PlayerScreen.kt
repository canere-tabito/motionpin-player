package com.caneretabito.motionpin.ui

import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.caneretabito.motionpin.data.*
import com.caneretabito.motionpin.player.*
import com.caneretabito.motionpin.tracking.DancerTrackingState
import com.caneretabito.motionpin.tracking.DancerFraming
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(model: MainViewModel, video: VideoEntity, pins: List<PinEntity>) {
    val state by model.player.state.collectAsStateWithLifecycle()
    val fullscreen by model.fullscreen.collectAsStateWithLifecycle()
    val tracking by model.tracking.state.collectAsStateWithLifecycle()
    val selectedPin by model.currentPin.collectAsStateWithLifecycle()
    val inspection by model.inspection.collectAsStateWithLifecycle()
    val jobs by model.jobs.collectAsStateWithLifecycle()
    // FULL framing needs the complete source even in a portrait screen. Preserve the user's
    // FILL preference and retain this display mode during hold/loss to avoid an abrupt crop.
    val fullBodyFit = tracking.videoId == video.id && tracking.keyframes.isNotEmpty() &&
        tracking.framing == DancerFraming.FULL && tracking.phase in setOf("scanning", "active", "lost")
    val effectiveFill = state.fill && !fullBodyFit
    var hud by rememberSaveable(video.id) { mutableStateOf(true) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var pinSheet by rememberSaveable { mutableStateOf(false) }
    var reelPin by remember { mutableStateOf<PinEntity?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    val interact: () -> Unit = { hud = true; interaction++ }
    LaunchedEffect(tracking.phase) { if (tracking.phase == "lost") interact() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        val verticalVideo = state.aspect < 1f
        val immersive = fullscreen || landscape || verticalVideo
        LaunchedEffect(hud, state.isPlaying, interaction, immersive, tools, diagnostics, pinSheet, tracking.phase) {
            if (immersive && hud && state.isPlaying && tracking.phase != "lost" &&
                !tools && !diagnostics && !pinSheet) { delay(4_000); hud = false }
        }
        if (!immersive) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                PlayerHeader(video, model::closePlayer, { diagnostics = true }, fullscreen, model::toggleFullscreen,
                    tracking.phase in setOf("scanning", "active") && tracking.videoId == video.id,
                    { if (tracking.phase in setOf("scanning", "active") && tracking.videoId == video.id) model.stopDancerTracking() else model.requestDancerTracking() })
                VideoSurface(model, state, effectiveFill, Modifier.fillMaxWidth().aspectRatio(state.aspect.coerceAtLeast(.3f)), { hud = !hud }, interact)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    PlaybackControls(model, state, selectedPin, compact = false, effectiveFill = effectiveFill, interact = interact,
                        onTools = { tools = true }, onPins = { pinSheet = true })
                    if (tracking.videoId == video.id && tracking.phase != "idle")
                        TrackingControls(model, tracking, fullBodyFit && state.fill, showStatusMessage = !immersive) { interact() }
                    Text("この動画のピン", style = MaterialTheme.typography.titleMedium, color = Champagne, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
                    if (pins.isEmpty()) Text("ピンをタップして時刻を保存。「区間ピン」では前後5秒から編集できます。", color = Muted, modifier = Modifier.padding(bottom = 24.dp))
                    pins.take(20).forEach { pin ->
                        PinCard(pin, video.displayName, jobs.firstOrNull { it.id == pin.aiJobId }, { model.openPin(pin) }, { model.editor.value = pin }, { model.enqueue(pin) }, { model.deletePin(pin) }, { reelPin = pin })
                        Spacer(Modifier.height(12.dp))
                    }
                    if (pins.size > 20) TextButton(onClick = { pinSheet = true }) { Text("すべてのピンを表示 (${pins.size})") }
                }
            }
        } else {
            VideoSurface(model, state, effectiveFill, Modifier.fillMaxSize(), { hud = !hud }, interact)
            if (hud) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .8f), Color.Transparent))).statusBarsPadding()) {
                    PlayerHeader(video, model::closePlayer, { diagnostics = true }, fullscreen, model::toggleFullscreen,
                    tracking.phase in setOf("scanning", "active") && tracking.videoId == video.id,
                    { if (tracking.phase in setOf("scanning", "active") && tracking.videoId == video.id) model.stopDancerTracking() else model.requestDancerTracking() })
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f))))
                    .navigationBarsPadding().padding(horizontal = if (landscape) 28.dp else 14.dp, vertical = 10.dp)) {
                    PlaybackControls(model, state, selectedPin, compact = true, effectiveFill = effectiveFill, interact = interact, onTools = { tools = true }, onPins = { pinSheet = true })
                    if (tracking.videoId == video.id && tracking.phase != "idle")
                        TrackingControls(model, tracking, fullBodyFit && state.fill, showStatusMessage = !immersive) { interact() }
                }
                if (landscape && verticalVideo) {
                    Column(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalIconButton(onClick = { model.savePoint(); interact() }, Modifier.size(56.dp)) { Icon(Icons.Default.PushPin, "時刻ピンを保存") }
                        Text("ピン", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(14.dp))
                        FilledTonalIconButton(onClick = { tools = true }, Modifier.size(56.dp)) { Icon(Icons.Default.Tune, "表示・速度・音声") }
                    }
                }
            } else {
                // An always discoverable 48dp button is a TalkBack/gesture alternative.
                IconButton(onClick = { hud = true; interaction++ }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).size(48.dp)
                    .background(Color.Black.copy(alpha = .45f), RoundedCornerShape(24.dp))) { Icon(Icons.Default.MoreHoriz, "再生コントロールを表示", tint = Ivory) }
            }
        }
        if (tracking.videoId == video.id && tracking.phase in setOf("scanning", "active", "lost") && hud && immersive) {
            Surface(Modifier.align(Alignment.TopCenter).padding(top = 78.dp)
                .widthIn(max = 320.dp), color = Graphite.copy(alpha = .9f), shape = RoundedCornerShape(16.dp)) {
                Text(tracking.message, Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall, color = Champagne)
            }
        }
        if (state.buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp))
        state.error?.let { error -> Surface(Modifier.align(Alignment.Center).padding(24.dp), color = Graphite, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(20.dp)) { Text("再生を停止しました", style = MaterialTheme.typography.titleLarge); Text(error, Modifier.padding(vertical = 12.dp)); TextButton(onClick = { model.player.clearError(); model.closePlayer() }) { Text("ライブラリに戻る") } }
        } }
    }
    if (tracking.videoId == video.id && tracking.phase == "choosing")
        DancerSelectionDialog(tracking, model::selectDancerTarget, { model.stopDancerTracking() },
            model::changeDancerQuality, model::rotateDancerPreview, model::stepDancerPreview)
    if (tools) PlayerTools(model, state, { tools = false; interact() }, { diagnostics = true })
    if (diagnostics) DiagnosticsDialog(inspection, state) { diagnostics = false }
    reelPin?.let { pin -> ReelPickerDialog(model, pin) { reelPin = null } }
    if (pinSheet) ModalBottomSheet(onDismissRequest = { pinSheet = false }, containerColor = Obsidian) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("この動画のピン · ${pins.size}", style = MaterialTheme.typography.headlineSmall) }
            item { Button(onClick = { model.editRange(); pinSheet = false }, Modifier.heightIn(min = 48.dp)) { Text("区間ピンを作成") } }
            items(pins, key = { it.id }) { pin -> PinCard(pin, video.displayName, jobs.firstOrNull { it.id == pin.aiJobId },
                { pinSheet = false; model.openPin(pin) }, { pinSheet = false; model.editor.value = pin }, { model.enqueue(pin) }, { model.deletePin(pin) }, { pinSheet = false; reelPin = pin }) }
        }
    }
}

@Composable
private fun TrackingControls(model: MainViewModel, tracking: DancerTrackingState,
                             fullBodyFit: Boolean, showStatusMessage: Boolean, interact: () -> Unit) {
    val hasTarget = tracking.keyframes.isNotEmpty()
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CenterFocusStrong, null, tint = Champagne, modifier = Modifier.size(18.dp))
            Text(" ダンサー追尾 · ${tracking.quality.title}" +
                if (tracking.manualHold) " · 画面固定中" else if (tracking.phase == "lost") " · 再選択できます" else "",
                color = Champagne, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f))
        }
        if (showStatusMessage) Text(tracking.message, color = Muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        // Horizontal scrolling preserves the 48dp targets at large font sizes and narrow widths.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hasTarget && tracking.phase in setOf("active", "scanning", "lost")) item {
                OutlinedButton(onClick = { model.holdDancerTracking(); interact() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(if (tracking.manualHold) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                    Text(if (tracking.manualHold) "追尾再開" else "画面を固定")
                }
            }
            item { TextButton(onClick = { model.requestDancerTracking(); interact() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("選び直す") } }
            if (hasTarget && tracking.phase in setOf("active", "scanning", "lost")) {
                items(DancerFraming.entries) { framing ->
                    FilterChip(selected = tracking.framing == framing,
                        onClick = { model.chooseDancerFraming(framing); interact() },
                        label = { Text(framing.label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            item { TextButton(onClick = { model.stopDancerTracking(); interact() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("追尾終了") } }
        }
        if (fullBodyFit) Text("全身追尾中はFITで表示", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlayerHeader(video: VideoEntity, onBack: () -> Unit, onDiagnostics: () -> Unit,
                         fullscreen: Boolean, onFullscreen: () -> Unit, trackingActive: Boolean,
                         onTracking: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, Modifier.size(48.dp)) { Icon(Icons.Default.ArrowBack, "ライブラリに戻る") }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text("NOW PLAYING", color = Champagne, style = MaterialTheme.typography.labelSmall)
            Text(video.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = onTracking, Modifier.size(48.dp)) {
            Icon(if (trackingActive) Icons.Default.CenterFocusWeak else Icons.Default.CenterFocusStrong,
                if (trackingActive) "ダンサー追尾を解除" else "ダンサーを選んで自動追尾",
                tint = if (trackingActive) Champagne else Ivory)
        }
        IconButton(onClick = onFullscreen, Modifier.size(48.dp)) {
            Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                if (fullscreen) "全画面固定を解除" else "全画面固定", tint = if (fullscreen) Champagne else Ivory)
        }
        IconButton(onClick = onDiagnostics, Modifier.size(48.dp)) { Icon(Icons.Default.Info, "再生能力とdecoderの診断") }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun VideoSurface(model: MainViewModel, state: PlaybackState, effectiveFill: Boolean, modifier: Modifier, onTap: () -> Unit, onInteract: () -> Unit) {
    val latestState by rememberUpdatedState(state)
    val latestFill by rememberUpdatedState(effectiveFill)
    val tap by rememberUpdatedState(onTap)
    val interaction by rememberUpdatedState(onInteract)
    BoxWithConstraints(modifier.clipToBounds().background(Color.Black)
        .semantics {
            contentDescription = "動画。タップで操作表示、左右ダブルタップで5秒移動。"
            customActions = listOf(CustomAccessibilityAction("5秒戻る") { model.player.seekBy(-5_000_000); true },
                CustomAccessibilityAction("5秒進む") { model.player.seekBy(5_000_000); true },
                CustomAccessibilityAction("操作を表示") { tap(); true },
                CustomAccessibilityAction("時刻ピンを保存") { model.savePoint(); true })
        }
        .pointerInput(model) {
            detectTapGestures(onTap = { tap() }, onDoubleTap = { offset ->
                model.player.seekBy(if (offset.x < size.width / 2f) -5_000_000 else 5_000_000); interaction()
            }, onLongPress = { model.player.setTemporarySlow(true); interaction() }, onPress = {
                try { tryAwaitRelease() } finally { model.player.setTemporarySlow(false) }
            })
        }
        .pointerInput(model) {
            detectTransformGestures { _, pan, zoom, _ ->
                val current = latestState
                val tracker = model.tracking.state.value
                if (tracker.videoId == model.currentVideo.value?.id &&
                    tracker.phase in setOf("active", "scanning", "lost") && tracker.keyframes.isNotEmpty() && !tracker.manualHold)
                    model.tracking.setManualHold(true)
                val transform = viewportTransform(size.width.toFloat(), size.height.toFloat(), current.aspect, latestFill, current.viewport)
                val next = current.viewport.copy(zoom = (current.viewport.zoom * zoom).coerceIn(1f, 8f))
                if (zoom != 1f) model.tracking.setUserZoom(next.zoom)
                model.player.setViewport(if (next.zoom > 1f) next.pan(pan.x, pan.y, transform.baseWidth, transform.baseHeight) else VideoViewport())
                interaction()
            }
        }) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        SideEffect { model.updateTrackingGeometry(widthPx, heightPx) }
        val transform = viewportTransform(widthPx, heightPx, state.aspect, effectiveFill, state.viewport)
        AndroidView(factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = model.player.exo
                // PlayerView defaults to SurfaceView. Never silently switch to TextureView for zoom.
                check(videoSurfaceView is SurfaceView) { "MotionPin requires SurfaceView playback" }
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }, modifier = Modifier.align(Alignment.Center).requiredSize(
            with(density) { transform.baseWidth.toDp() }, with(density) { transform.baseHeight.toDp() }),
            update = { view ->
                if (view.player !== model.player.exo) view.player = model.player.exo
                view.scaleX = state.viewport.zoom; view.scaleY = state.viewport.zoom
                view.translationX = transform.translateX; view.translationY = transform.translateY
            }, onRelease = { view -> view.player = null })
    }
}

@Composable
private fun PlaybackControls(model: MainViewModel, state: PlaybackState, pin: PinEntity?, compact: Boolean, effectiveFill: Boolean,
                             interact: () -> Unit, onTools: () -> Unit, onPins: () -> Unit) {
    val lower = pin?.takeIf { it.endUs != null }?.startUs ?: 0L
    val upper = (pin?.endUs ?: state.durationUs).coerceAtLeast(lower + 1)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val reel by model.activeReel.collectAsStateWithLifecycle()
    Column {
        reel?.let { active -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${active.reelName} · ${active.index + 1}/${active.order.size}", color = Champagne,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { model.nextReelScene(); interact() }) { Text("次のシーン") }
            TextButton(onClick = { model.stopReel(); interact() }) { Text("リール終了") }
        } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(formatTimestamp(dragging?.toLong() ?: state.sourcePositionUs, true), style = MaterialTheme.typography.bodySmall, color = Ivory)
            Text(if (state.cached) "AI ${state.requestedSpeed}× · 再生速度 1× · ミュート" else "元動画 · ${state.requestedSpeed}×", color = Champagne, style = MaterialTheme.typography.bodySmall)
            Text(formatTimestamp(upper), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Slider(value = dragging ?: state.sourcePositionUs.toFloat().coerceIn(lower.toFloat(), upper.toFloat()),
            onValueChange = { dragging = it; interact() }, onValueChangeFinished = { dragging?.let { model.player.seekSource(it.toLong()) }; dragging = null },
            valueRange = lower.toFloat()..upper.toFloat(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "元動画の再生位置" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.player.seekBy(-5_000_000); interact() }, Modifier.size(48.dp)) { Icon(Icons.Default.Replay5, "5秒戻る") }
            FilledIconButton(onClick = { model.playPause(); interact() }, Modifier.size(if (compact) 52.dp else 60.dp)) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "一時停止" else "再生", modifier = Modifier.size(30.dp)) }
            IconButton(onClick = { model.player.seekBy(5_000_000); interact() }, Modifier.size(48.dp)) { Icon(Icons.Default.Forward5, "5秒進む") }
            IconButton(onClick = { model.savePoint(); interact() }, Modifier.size(48.dp)) { Icon(Icons.Default.PushPin, "再生を続けながら時刻ピンを保存", tint = Champagne) }
            IconButton(onClick = { model.editRange(); interact() }, Modifier.size(48.dp)) { Icon(Icons.Default.ContentCut, "前後5秒から区間ピンを作成") }
            IconButton(onClick = onTools, Modifier.size(48.dp)) { Icon(Icons.Default.Tune, "速度・ズーム・音声・表示") }
        }
        if (!compact) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(listOf(.25f, .5f, .75f, 1f, 1.5f, 2f)) { speed -> FilterChip(selected = state.requestedSpeed == speed, enabled = !state.cached,
                    onClick = { model.setSpeed(speed); interact() }, label = { Text("${speed}×") }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { model.player.setLoop(!state.loop); interact() }) { Icon(Icons.Default.Repeat, null, tint = if (state.loop) Champagne else Muted); Text(if (state.loop) "ループ中" else "ループ") }
                TextButton(onClick = { model.toggleInstantReplay(); interact() }) { Text(if (state.replay) "リプレイ終了" else "直前3秒リプレイ") }
                TextButton(onClick = onPins) { Text("ピン一覧") }
                if (pin?.aiJobId != null) TextButton(onClick = { model.compareSourceCache(); interact() }) { Text(if (state.cached) "同じ時刻の元動画" else "同じ時刻のAI") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { model.toggleInstantReplay(); interact() }) { Text(if (state.replay) "リプレイ終了" else "直前3秒リプレイ") }
                TextButton(onClick = onPins) { Text("ピン一覧") }
                if (pin?.aiJobId != null) TextButton(onClick = { model.compareSourceCache(); interact() }) { Text(if (state.cached) "元動画へ" else "AIへ") }
                Text("${if (effectiveFill) "FILL" else "FIT"} · ズーム ${"%.1f".format(state.viewport.zoom)}×", modifier = Modifier.padding(14.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PlayerTools(model: MainViewModel, state: PlaybackState, onDismiss: () -> Unit, onDiagnostics: () -> Unit) {
    var fillConfirm by remember { mutableStateOf(false) }
    fun manualViewport(viewport: VideoViewport) {
        val tracking = model.tracking.state.value
        if (tracking.videoId == model.currentVideo.value?.id && tracking.keyframes.isNotEmpty() &&
            tracking.phase in setOf("active", "scanning", "lost") && !tracking.manualHold)
            model.tracking.setManualHold(true)
        model.tracking.setUserZoom(viewport.zoom)
        model.player.setViewport(viewport)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("映像と再生の設定") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("速度", color = Champagne)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(listOf(.25f, .5f, .75f, 1f, 1.5f, 2f)) { speed ->
                FilterChip(state.requestedSpeed == speed, { model.setSpeed(speed) }, enabled = !state.cached,
                    label = { Text("${speed}×") }, modifier = Modifier.heightIn(min = 48.dp))
            } }
            Text(if (state.cached) "このAI映像にはスローが焼き込まれています。二重スローを防ぐためPlayerは1×固定です。" else "長押し中は通常0.5×。同じ操作は上の0.5×ボタンでも行えます。AIは区間生成で0.5× / 0.25×に対応します。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("ループ", Modifier.weight(1f)); Switch(state.loop, { model.player.setLoop(it) }) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("ミュート", Modifier.weight(1f)); Switch(state.muted, { model.setMuted(it) }, enabled = !state.cached) }
            Text(if (state.cached) "AI生成音声はミュートです。" else "通常スローはピッチを保持する音声処理を使用します。", color = Muted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!state.fill, { model.setFill(false) }, label = { Text("FIT · 全体") }, modifier = Modifier.heightIn(min = 48.dp))
                FilterChip(state.fill, { fillConfirm = true }, label = { Text("FILL · 切り抜き") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            Text("ズーム ${"%.1f".format(state.viewport.zoom)}×", color = Champagne, modifier = Modifier.padding(top = 8.dp))
            Slider(state.viewport.zoom, { manualViewport(state.viewport.copy(zoom = it)) }, valueRange = 1f..8f, modifier = Modifier.semantics { contentDescription = "ズーム倍率" })
            Text("ピンチで拡大、拡大中に1本指で移動できます。手動操作中は追尾の画面を固定し、「追尾再開」で戻れます。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { manualViewport(state.viewport.copy(centerX = state.viewport.centerX - .05f)) }, Modifier.size(48.dp)) { Icon(Icons.Default.KeyboardArrowLeft, "表示位置を左へ") }
                IconButton(onClick = { manualViewport(state.viewport.copy(centerY = state.viewport.centerY - .05f)) }, Modifier.size(48.dp)) { Icon(Icons.Default.KeyboardArrowUp, "表示位置を上へ") }
                IconButton(onClick = { manualViewport(state.viewport.copy(centerY = state.viewport.centerY + .05f)) }, Modifier.size(48.dp)) { Icon(Icons.Default.KeyboardArrowDown, "表示位置を下へ") }
                IconButton(onClick = { manualViewport(state.viewport.copy(centerX = state.viewport.centerX + .05f)) }, Modifier.size(48.dp)) { Icon(Icons.Default.KeyboardArrowRight, "表示位置を右へ") }
            }
            TextButton(onClick = { manualViewport(VideoViewport()) }) { Text("ズームと位置をリセット") }
            TextButton(onClick = onDiagnostics) { Text("decoder・HDR・フレーム診断") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } })
    if (fillConfirm) AlertDialog(onDismissRequest = { fillConfirm = false }, title = { Text("画面いっぱいに表示") },
        text = { Text("FILLでは画面と映像の比率に応じて上下または左右が切り抜かれます。元動画やAI映像自体は変更しません。") },
        confirmButton = { TextButton(onClick = { model.setFill(true); fillConfirm = false }) { Text("FILLにする") } },
        dismissButton = { TextButton(onClick = { fillConfirm = false }) { Text("キャンセル") } })
}

@Composable
private fun DiagnosticsDialog(inspection: PlaybackInspection?, state: PlaybackState, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("再生診断") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("実際のdecoder", color = Champagne)
        Text("${state.decoderName}\n${state.decoderHardware} · SurfaceView")
        inspection?.let { details ->
            Text("入力", color = Champagne)
            Text("${details.width} × ${details.height} · ${details.fps?.let { "%.3f fps".format(it) } ?: "fps未確認"}\n${details.mime}\nprofile ${details.profile ?: "不明"} / level ${details.level ?: "不明"}\nbit depth ${details.bitDepth ?: "不明"} · ${details.color}\nbitrate ${details.bitrate?.let { "${it / 1_000} kbps" } ?: "未確認"}")
            Text("画面", color = Champagne)
            Text("${details.displayRefresh} Hz · ${details.displayHdr}")
            Text("候補decoder（能力照会）", color = Champagne)
            Text(details.candidates.joinToString("\n").ifBlank { "profile・サイズ・レートの対応を未確認" })
            Text(details.notes, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Text("この再生セッションのカウンタ", color = Champagne)
        Text(if (state.decoderName == "未選択") "未計測" else "描画 ${state.renderedFrames} · drop ${state.droppedFrames}")
        Text("warmup・seekを含む参考値です。4K60の継続性能試験やAV同期の測定結果ではありません。通常4K再生、4K AI、HDR AIの対応は別々に検証します。", color = Muted, style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } })
}
