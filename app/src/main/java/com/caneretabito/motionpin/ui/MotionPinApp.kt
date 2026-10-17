package com.caneretabito.motionpin.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caneretabito.motionpin.data.*
import com.caneretabito.motionpin.player.formatTimestamp
import com.caneretabito.motionpin.thumbnail.LocalVideoThumbnails
import com.caneretabito.motionpin.thumbnail.ThumbnailRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionPinApp(model: MainViewModel, permissionFlow: StateFlow<String>, onGrant: () -> Unit,
                 onImportVideo: () -> Unit, onExportBackup: () -> Unit, onImportBackup: () -> Unit,
                 onRelink: (String) -> Unit) {
    val videos by model.videos.collectAsStateWithLifecycle()
    val pins by model.pins.collectAsStateWithLifecycle()
    val channels by model.channels.collectAsStateWithLifecycle()
    val relations by model.channelVideos.collectAsStateWithLifecycle()
    val jobs by model.jobs.collectAsStateWithLifecycle()
    val library by model.libraryState.collectAsStateWithLifecycle()
    val video by model.currentVideo.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val editor by model.editor.collectAsStateWithLifecycle()
    val weakRelink by model.weakRelink.collectAsStateWithLifecycle()
    val permission by permissionFlow.collectAsStateWithLifecycle()
    val processingNotice by model.runtime.processingNotice.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    var createCollection by rememberSaveable { mutableStateOf(false) }
    var confirmCacheClear by rememberSaveable { mutableStateOf(false) }
    var collectionName by rememberSaveable { mutableStateOf("") }
    var addVideoToCollection by remember { mutableStateOf<VideoEntity?>(null) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it, withDismissAction = true); model.dismissMessage() } }
    LaunchedEffect(processingNotice) { processingNotice?.let { snack.showSnackbar(it, withDismissAction = true) } }

    MotionPinTheme {
        Box(Modifier.fillMaxSize().background(Obsidian)) {
            if (video != null) {
                PlayerScreen(model, video!!, pins.filter { it.videoId == video!!.id })
                BackHandler { model.closePlayer() }
            } else {
                Scaffold(containerColor = Obsidian, topBar = {
                    Column(Modifier.statusBarsPadding().padding(start = 24.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("MOTIONPIN", style = MaterialTheme.typography.labelSmall, color = Champagne)
                                Text(listOf("あなたの映像", "心に残る瞬間", "コレクション")[tab], style = MaterialTheme.typography.headlineLarge,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                            IconButton(onClick = { showSettings = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert, "バックアップと設定") }
                        }
                        Text("端末の中だけで、何度でも。", color = Muted, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                    }
                }, bottomBar = {
                    NavigationBar(containerColor = Obsidian, tonalElevation = 0.dp) {
                        listOf("動画" to Icons.Default.VideoLibrary, "ピン" to Icons.Default.PushPin, "コレクション" to Icons.Default.CollectionsBookmark).forEachIndexed { index, (label, icon) ->
                            NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, null) }, label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = Champagne, selectedTextColor = Champagne, indicatorColor = Graphite))
                        }
                    }
                }) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (tab) {
                            0 -> VideosScreen(videos, library, permission, onGrant, onImportVideo, model::refresh, model::openVideo,
                                onRelink, { addVideoToCollection = it })
                            1 -> PinsScreen(pins, videos, jobs, model)
                            2 -> CollectionsScreen(channels, videos, relations, model::openVideo, model::deleteChannel,
                                { createCollection = true }, onRelink, model::removeFromChannel)
                        }
                    }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (video == null) 80.dp else 12.dp))
            if (busy != null) Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp), color = Graphite, shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(busy!!, Modifier.padding(start = 14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        editor?.let { pin ->
            PinEditor(pin, videos.firstOrNull { it.id == pin.videoId }?.durationUs ?: video?.durationUs ?: 1L,
                onDismiss = { model.editor.value = null }, onSave = model::savePin)
        }
        if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("ライブラリの管理") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("バックアップはピン・コレクション・設定を保存します。元動画・AI映像・アクセス権は含みません。保存先は端末内を選んでください。", color = Muted)
                OutlinedButton(onClick = { showSettings = false; onExportBackup() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("バックアップを書き出す") }
                OutlinedButton(onClick = { showSettings = false; onImportBackup() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("バックアップから復元") }
                Text("再インストール後は再許可・再関連付けが必要です。同名の別動画へ自動でピンを移しません。", style = MaterialTheme.typography.bodySmall, color = Muted)
                TextButton(onClick = { showSettings = false; confirmCacheClear = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("AIキャッシュを削除") }
                TextButton(onClick = { showSettings = false; showLicenses = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("ライセンスとモデルの来歴") }
            } }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("閉じる") } })
        if (showLicenses) LicenseDialog { showLicenses = false }
        if (confirmCacheClear) AlertDialog(onDismissRequest = { confirmCacheClear = false }, title = { Text("AIキャッシュを削除しますか？") },
            text = { Text("生成済みのAI映像だけを削除します。元動画とピンは保持されます。再生・AI処理中は削除できません。必要な区間は後から再生成できます。") },
            confirmButton = { TextButton(onClick = { confirmCacheClear = false; model.clearCaches() }) { Text("キャッシュを削除") } },
            dismissButton = { TextButton(onClick = { confirmCacheClear = false }) { Text("キャンセル") } })
        if (createCollection) AlertDialog(onDismissRequest = { createCollection = false }, title = { Text("コレクションを作成") },
            text = { Column { Text("好きな動画をまとめる手動チャンネル。元ファイルは移動しません。", color = Muted)
                OutlinedTextField(collectionName, { collectionName = it.take(200) }, label = { Text("名前") }, singleLine = true, modifier = Modifier.padding(top = 16.dp)) } },
            confirmButton = { TextButton(enabled = collectionName.isNotBlank(), onClick = { model.addChannel(collectionName); collectionName = ""; createCollection = false }) { Text("作成") } },
            dismissButton = { TextButton(onClick = { createCollection = false }) { Text("キャンセル") } })
        addVideoToCollection?.let { selected ->
            AlertDialog(onDismissRequest = { addVideoToCollection = null }, title = { Text("コレクションに追加") },
                text = { LazyColumn { if (channels.isEmpty()) item { Text("先に「コレクション」タブでコレクションを作成してください。") }
                    items(channels, key = { it.id }) { channel -> TextButton(onClick = { model.addToChannel(channel, selected); addVideoToCollection = null }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(channel.name) } } } },
                confirmButton = { TextButton(onClick = { addVideoToCollection = null }) { Text("閉じる") } })
        }
        weakRelink?.let { (id, uri) ->
            AlertDialog(onDismissRequest = { model.weakRelink.value = null }, title = { Text("元動画と一致を確認できません") },
                text = { Text("名前・サイズ・長さだけでは同じ動画と証明できません。この動画へ既存ピンを移しますか？異なる動画ならキャンセルしてください。AIキャッシュは無効化されます。") },
                confirmButton = { TextButton(onClick = { model.weakRelink.value = null; model.relink(id, uri, true) }) { Text("確認して再関連付け") } },
                dismissButton = { TextButton(onClick = { model.weakRelink.value = null }) { Text("キャンセル") } })
        }
    }
}

@Composable
private fun VideosScreen(videos: List<VideoEntity>, library: LibraryState, permission: String, onGrant: () -> Unit,
                         onImport: () -> Unit, onRefresh: () -> Any, onOpen: (VideoEntity) -> Unit,
                         onRelink: (String) -> Unit, onCollection: (VideoEntity) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var showSort by remember { mutableStateOf(false) }
    val folders = remember(videos) { videos.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted() }
    val filtered = remember(videos, query, folder, sort) {
        val result = videos.filter { (folder.isBlank() || it.folder == folder) && (it.displayName.contains(query, true) || it.folder.contains(query, true)) }
        when (sort) { 1 -> result.sortedBy { it.displayName.lowercase() }; 2 -> result.sortedByDescending { it.durationUs }; 3 -> result.sortedByDescending { it.lastPlayedAt }; else -> result.sortedByDescending { it.createdAt } }
    }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("動画やフォルダを検索") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(16.dp))
                Box { IconButton(onClick = { showSort = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Sort, "動画の並び順") }
                    DropdownMenu(showSort, { showSort = false }) { listOf("最近追加", "名前順", "長い順", "最近再生").forEachIndexed { index, name -> DropdownMenuItem(text = { Text(name) }, onClick = { sort = index; showSort = false }) } } }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImport, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("動画を開く") }
            OutlinedButton(onClick = onGrant, Modifier.heightIn(min = 48.dp)) { Text("アクセスを選択") }
        } }
        item { Text(permission, style = MaterialTheme.typography.bodySmall, color = Muted) }
        if (folders.isNotEmpty()) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = folder.isBlank(), onClick = { folder = "" }, label = { Text("すべてのフォルダ") }, modifier = Modifier.heightIn(min = 48.dp)) }
            items(folders, key = { it }) { name -> FilterChip(selected = folder == name, onClick = { folder = name }, label = { Text(name.trimEnd('/').substringAfterLast('/')) }, modifier = Modifier.heightIn(min = 48.dp)) }
        } }
        if (library.status == "loading") item { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("${library.scanned} 本を確認しました…", color = Muted, modifier = Modifier.padding(top = 6.dp)) } }
        if (library.status in listOf("partial", "error")) item { Surface(color = Graphite, shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(14.dp)) {
            Text(if (library.status == "partial") "一部の動画を表示しています" else "ライブラリを確認してください", color = Champagne)
            Text("選択範囲にない動画や権限を失った動画のピンも保持されます。アクセスを選び直すか、端末内の動画を開いてください。", color = Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onRefresh() }) { Text("再読み込み") }
        } } }
        val recent = videos.filter { it.lastPlayedAt > 0 && it.resumePositionUs > 0 && it.availability == Availability.AVAILABLE }.sortedByDescending { it.lastPlayedAt }.take(5)
        if (recent.isNotEmpty() && query.isBlank() && folder.isBlank()) {
            item { SectionLabel("続きから", "${recent.size} 本") }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(recent, key = { it.id }) { item ->
                Card(onClick = { onOpen(item) }, modifier = Modifier.width(220.dp), colors = CardDefaults.cardColors(containerColor = Graphite)) {
                    VideoThumbnail(item, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Text(item.displayName, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${formatTimestamp(item.resumePositionUs)} から再生", Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), color = Champagne, style = MaterialTheme.typography.bodySmall)
                }
            } } }
        }
        item { SectionLabel(listOf("最近追加", "名前順", "長い順", "最近再生")[sort], "${filtered.size} 本") }
        if (filtered.isEmpty() && library.status != "loading") item { EmptyState("まだ動画がありません", "端末内の動画を選んで、好きな瞬間をピンに残しましょう。動画は移動・変更されません。") }
        items(filtered, key = { it.id }, contentType = { "video" }) { video -> VideoRow(video, { onOpen(video) }, { onRelink(video.id) }, { onCollection(video) }) }
    }
}

@Composable
private fun VideoRow(video: VideoEntity, onOpen: () -> Unit, onRelink: () -> Unit, onCollection: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = Graphite) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoThumbnail(video, Modifier.width(112.dp).height(76.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(video.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text("${formatTimestamp(video.durationUs)} · ${video.width} × ${video.height}", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (video.needsRelink || video.availability != Availability.AVAILABLE) Text(if (video.needsRelink) "再関連付けが必要" else when (video.availability) {
                    Availability.PERMISSION_REQUIRED -> "アクセス権を確認"; Availability.DELETED_CONFIRMED -> "元動画の削除を確認済み"; else -> "一時的に利用できません"
                }, color = Champagne, style = MaterialTheme.typography.bodySmall)
            }
            Box { IconButton(onClick = { menu = true }, Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert, "${video.displayName} のメニュー") }
                DropdownMenu(menu, { menu = false }) {
                    if (onCollection != null) DropdownMenuItem(text = { Text("コレクションに追加") }, onClick = { menu = false; onCollection() })
                    if (onRemove != null) DropdownMenuItem(text = { Text("このコレクションから外す") }, onClick = { menu = false; onRemove() })
                    DropdownMenuItem(text = { Text("元動画を再関連付け") }, onClick = { menu = false; onRelink() })
                }
            }
        }
    }
}

@Composable
private fun PinsScreen(pins: List<PinEntity>, videos: List<VideoEntity>, jobs: List<AiJobEntity>, model: MainViewModel) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var delete by remember { mutableStateOf<PinEntity?>(null) }
    var reelPin by remember { mutableStateOf<PinEntity?>(null) }
    var showReels by remember { mutableStateOf(false) }
    val byVideo = remember(videos) { videos.associateBy { it.id } }
    val byJob = remember(jobs) { jobs.associateBy { it.id } }
    val visible = remember(pins, filter, query, jobs) { pins.filter { pin ->
        (when (filter) { 1 -> pin.endUs == null; 2 -> pin.endUs != null; 3 -> byJob[pin.aiJobId]?.status == JobStatus.COMPLETED; else -> true }) &&
            (pin.title.contains(query, true) || pin.note.contains(query, true) || byVideo[pin.videoId]?.displayName?.contains(query, true) == true)
    }.sortedByDescending { it.updatedAt } }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("シーンを好きな順番で鑑賞", Modifier.weight(1f), color = Muted)
            FilledTonalButton(onClick = { showReels = true }, Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.PlaylistPlay, null)
                Text(" シーンリール")
            }
        } }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("ピン・動画・メモを検索") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(16.dp)) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(4) { index -> FilterChip(filter == index, { filter = index }, label = { Text(listOf("すべて", "時刻", "区間", "AI済み")[index]) }, modifier = Modifier.heightIn(min = 48.dp)) } } }
        val activeJobs = jobs.filter { it.status != JobStatus.COMPLETED }
        if (activeJobs.isNotEmpty()) item { Text("AI区間生成", style = MaterialTheme.typography.titleMedium, color = Champagne) }
        items(activeJobs, key = { "job-${it.id}" }, contentType = { "job" }) { job -> JobCard(job, model) }
        if (visible.isEmpty()) item { EmptyState("大切な瞬間を、ここに", "再生画面のピンをタップすると、そのまま時刻を保存。区間を選べば、繰り返しやAIスローも楽しめます。") }
        items(visible, key = { it.id }, contentType = { "pin" }) { pin ->
            PinCard(pin, byVideo[pin.videoId]?.displayName ?: "再関連付けが必要な動画", byJob[pin.aiJobId],
                onPlay = { model.openPin(pin) }, onEdit = { model.editor.value = pin }, onAi = { model.enqueue(pin) },
                onDelete = { delete = pin }, onAddToReel = { reelPin = pin })
        }
    }
    if (showReels) SceneReelsSheet(model) { showReels = false }
    reelPin?.let { pin -> ReelPickerDialog(model, pin) { reelPin = null } }
    delete?.let { pin -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("このピンを削除しますか？") },
        text = { Text("${pin.title}\n元動画は変更されません。") }, confirmButton = { TextButton(onClick = { model.deletePin(pin); delete = null }) { Text("ピンを削除") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("キャンセル") } }) }
}

@Composable
fun PinCard(pin: PinEntity, videoName: String, job: AiJobEntity?, onPlay: () -> Unit, onEdit: () -> Unit,
            onAi: () -> Unit, onDelete: () -> Unit, onAddToReel: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = Graphite) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (pin.endUs == null) "MOMENT" else "SCENE", color = Champagne, style = MaterialTheme.typography.labelSmall)
                    Text(pin.title.ifBlank { "無題のピン" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                    Text(videoName, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { menu = true }, Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert, "${pin.title} の操作") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("編集") }, onClick = { menu = false; onEdit() })
                    if (pin.endUs != null && onAddToReel != null)
                        DropdownMenuItem(text = { Text("リールに追加") }, onClick = { menu = false; onAddToReel() })
                    DropdownMenuItem(text = { Text("削除") }, onClick = { menu = false; onDelete() })
                }
            }
            Text("${formatTimestamp(pin.startUs, true)}${pin.endUs?.let { " → ${formatTimestamp(it, true)}" } ?: ""} · ${pin.speed}×${if (pin.loop) " · ループ" else ""}", color = Champagne, modifier = Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            if (pin.note.isNotBlank()) Text(pin.note, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Default.PlayArrow, null); Text(if (job?.status == JobStatus.COMPLETED) "AIを再生" else "再生") }
                if (pin.endUs != null) OutlinedButton(onClick = onAi, Modifier.heightIn(min = 48.dp)) { Text("AI区間生成") }
            }
        }
    }
}

@Composable
private fun JobCard(job: AiJobEntity, model: MainViewModel) {
    val eta by model.ai.generationEta.collectAsStateWithLifecycle()
    val jobEta = eta?.takeIf { it.jobId == job.id && job.status == JobStatus.RUNNING }
    val remaining = jobEta?.remainingMs
    val etaLabel = when {
        jobEta == null -> null
        jobEta.outputFrames >= jobEta.totalFrames -> "映像の検証・保存中"
        remaining == null -> "残り時間を計測中…"
        remaining < 60_000L -> "推定残り 約${maxOf(1L, (remaining + 999L) / 1000L)}秒"
        else -> "推定残り 約${(remaining + 59_999L) / 60_000L}分"
    }
    Surface(color = Graphite, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("${formatTimestamp(job.startUs)}–${formatTimestamp(job.endUs)} · AI ${job.speedNumerator.toFloat() / job.speedDenominator}×", style = MaterialTheme.typography.titleMedium)
            Text(when (job.status) { JobStatus.RUNNING -> "生成中"; JobStatus.WAITING -> "待機中"; JobStatus.QUEUED -> "予約済み"; JobStatus.FAILED -> "停止・要確認"; JobStatus.CANCELLED -> "取消済み"; JobStatus.INTERRUPTED -> "中断"; else -> job.status }, color = Champagne)
            LinearProgressIndicator(progress = { job.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Text("${(job.progress * 100).toInt()}% · 中間フレーム ${job.inferenceCount} 枚", color = Muted, style = MaterialTheme.typography.bodySmall)
            etaLabel?.let { Text(it, color = Champagne, style = MaterialTheme.typography.bodySmall) }
            if (jobEta != null) Text("生成フレーム ${jobEta.outputFrames} / ${jobEta.totalFrames} · 処理速度から推定（検証・保存時間は含まず）", color = Muted, style = MaterialTheme.typography.bodySmall)
            job.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            Row { if (job.status in listOf(JobStatus.RUNNING, JobStatus.WAITING, JobStatus.QUEUED)) TextButton(onClick = { model.cancelJob(job) }) { Text("停止 / 取消") }
                else TextButton(onClick = { model.resumeJob(job) }) { Text("区間を再生成して再開") } }
        }
    }
}

@Composable
private fun CollectionsScreen(channels: List<ChannelEntity>, videos: List<VideoEntity>, relations: List<ChannelVideoEntity>, onOpen: (VideoEntity) -> Unit,
                              onDelete: (ChannelEntity) -> Any, onAdd: () -> Unit, onRelink: (String) -> Unit, onRemove: (ChannelEntity, VideoEntity) -> Any) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<ChannelEntity?>(null) }
    val active = channels.firstOrNull { it.id == selected }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (active == null) "動画を、好きなまとまりに。" else active.name, Modifier.weight(1f), color = Muted)
            if (active == null) FilledTonalButton(onClick = onAdd, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Default.Add, null); Text("作成") }
            else TextButton(onClick = { selected = null }) { Text("一覧に戻る") }
        } }
        if (active == null) {
            if (channels.isEmpty()) item { EmptyState("自分だけのコレクション", "ダンス、ステージ、お気に入りの演者。動画一覧のメニューから追加できます。元ファイルは移動しません。") }
            items(channels, key = { it.id }) { channel -> Surface(color = Graphite, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().clickable { selected = channel.id }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CollectionsBookmark, null, tint = Champagne, modifier = Modifier.size(32.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 18.dp)) { Text(channel.name, style = MaterialTheme.typography.titleLarge); Text("${relations.count { it.channelId == channel.id }} 本", color = Muted) }
                    IconButton(onClick = { delete = channel }, Modifier.size(48.dp)) { Icon(Icons.Default.DeleteOutline, "${channel.name} を削除") }
                }
            } }
        } else {
            val ids = relations.filter { it.channelId == active.id }.map { it.videoId }.toSet()
            if (ids.isEmpty()) item { EmptyState("動画を追加しましょう", "「動画」タブの各動画のメニューから、このコレクションに追加できます。") }
            items(videos.filter { it.id in ids }, key = { it.id }) { video -> VideoRow(video, { onOpen(video) }, { onRelink(video.id) }, onRemove = { onRemove(active, video) }) }
        }
    }
    delete?.let { channel -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("コレクションを削除しますか？") }, text = { Text("元動画とピンは保持されます。") },
        confirmButton = { TextButton(onClick = { onDelete(channel); delete = null }) { Text("削除") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("キャンセル") } }) }
}

@Composable
private fun SectionLabel(title: String, detail: String) { Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
} }

@Composable
private fun EmptyState(title: String, text: String) { Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Default.PushPin, null, tint = Champagne, modifier = Modifier.size(38.dp)); Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp)); Text(text, color = Muted)
} }

@Composable
fun VideoThumbnail(video: VideoEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = ThumbnailRequest(video.id, video.uri, video.revision, video.modifiedSeconds,
        video.sizeBytes, video.durationUs, video.width, video.height, video.rotationDegrees,
        fileName = video.displayName, mimeType = video.mimeType)
    // Reset the remembered image immediately on relink/permission/source changes. produceState's
    // keys alone restart its coroutine but retain the previous image until that coroutine runs.
    val bitmap by key(request, video.availability, video.needsRelink) {
        produceState<Bitmap?>(initialValue = null) {
            if (video.availability == Availability.AVAILABLE && !video.needsRelink)
                value = LocalVideoThumbnails.load(context, request)
        }
    }
    Box(modifier.background(Obsidian, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.Movie, null, tint = Muted)
    }
}
