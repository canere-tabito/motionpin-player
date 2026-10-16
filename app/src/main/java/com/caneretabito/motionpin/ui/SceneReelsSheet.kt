package com.caneretabito.motionpin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caneretabito.motionpin.data.PinEntity
import com.caneretabito.motionpin.data.SceneReel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneReelsSheet(model: MainViewModel, onDismiss: () -> Unit) {
    val reels by model.reels.collectAsStateWithLifecycle()
    val pins by model.pins.collectAsStateWithLifecycle()
    val byId = remember(pins) { pins.associateBy { it.id } }
    var name by remember { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<SceneReel?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Obsidian) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("お気に入りシーンリール", style = MaterialTheme.typography.headlineSmall, color = Champagne)
            Text("区間ピンを複数の動画から並べて、順番・シャッフル・繰り返しで再生。元動画をコピーしません。",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("新しいリール名") },
                    singleLine = true, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { model.createReel(name); name = "" },
                    enabled = name.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("作成") }
            }
            if (reels.isEmpty()) Text("まだリールがありません。作成後、ピンの「リールに追加」からシーンを集めましょう。", color = Muted)
            reels.forEach { reel ->
                Surface(shape = MaterialTheme.shapes.large, color = Graphite) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(reel.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text("${reel.pinIds.size} シーン", color = Muted)
                            IconButton(onClick = { deleteCandidate = reel }) {
                                Icon(Icons.Default.DeleteOutline, "リールを削除")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("シャッフル", Modifier.weight(1f))
                            Switch(reel.shuffle, { model.changeReelPlayback(reel.id, shuffle = it) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("無限リピート", Modifier.weight(1f))
                            Switch(reel.repeat, { model.changeReelPlayback(reel.id, repeat = it) })
                        }
                        Button(onClick = { onDismiss(); model.playSceneReel(reel.id) },
                            enabled = reel.pinIds.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Default.PlayArrow, null)
                            Text(" このリールを再生")
                        }
                        reel.pinIds.forEachIndexed { index, id ->
                            val pin = byId[id]
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}. ${pin?.title?.ifBlank { "無題のシーン" } ?: "再関連付けが必要なピン"}",
                                    Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                IconButton(onClick = { model.moveReelScene(reel.id, index, -1) }, enabled = index > 0) {
                                    Icon(Icons.Default.ArrowUpward, "前へ")
                                }
                                IconButton(onClick = { model.moveReelScene(reel.id, index, 1) },
                                    enabled = index < reel.pinIds.lastIndex) {
                                    Icon(Icons.Default.ArrowDownward, "後へ")
                                }
                                IconButton(onClick = { model.removeSceneFromReel(reel.id, id) }) {
                                    Icon(Icons.Default.Close, "シーンをリールから外す")
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    deleteCandidate?.let { reel ->
        AlertDialog(onDismissRequest = { deleteCandidate = null },
            title = { Text("リールを削除しますか？") },
            text = { Text("「${reel.name}」を削除します。元動画とピンはそのままです。") },
            confirmButton = {
                TextButton(onClick = { model.deleteReel(reel.id); deleteCandidate = null }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("キャンセル") } })
    }
}

@Composable
fun ReelPickerDialog(model: MainViewModel, pin: PinEntity, onDismiss: () -> Unit) {
    val reels by model.reels.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("リールに追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pin.title.ifBlank { "無題の区間ピン" }, color = Champagne)
                if (reels.isEmpty()) Text("先に「ピン」タブのリール一覧でリールを作成してください。")
                reels.forEach { reel ->
                    OutlinedButton(onClick = { model.addSceneToReel(reel.id, pin.id); onDismiss() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(reel.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}
