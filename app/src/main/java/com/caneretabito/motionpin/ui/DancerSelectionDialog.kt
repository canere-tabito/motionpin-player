package com.caneretabito.motionpin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.caneretabito.motionpin.tracking.DancerTrackingState
import com.caneretabito.motionpin.tracking.TrackerQuality

/** Select by touch or accessible, numbered buttons. Always show what the AI actually sees. */
@Composable
fun DancerSelectionDialog(
    state: DancerTrackingState, onSelect: (Int) -> Unit, onCancel: () -> Unit,
    onQuality: (TrackerQuality) -> Unit, onRotate: () -> Unit, onStep: (Int) -> Unit
) {
    if (state.phase != "choosing") return
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("追尾するダンサーを選択") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ダンサーを選ぶと、その人の動きに画面がついていきます。",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackerQuality.entries.forEach { quality ->
                        FilterChip(selected = state.quality == quality,
                            onClick = { if (state.quality != quality) onQuality(quality) },
                            label = { Text(quality.title) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Text(if (state.quality == TrackerQuality.HIGH)
                    "高精度：Lite2・約4回/秒の解析。細かい動きや遠くの人物向け（処理負荷は高め）。"
                    else "軽快：Lite0・約2回/秒の解析。処理負荷を抑えたいとき向け。",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    OutlinedButton(onClick = { onStep(-1) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.ChevronLeft, null); Text("1秒前")
                    }
                    OutlinedButton(onClick = { onStep(1) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("1秒後"); Icon(Icons.Default.ChevronRight, null)
                    }
                    IconButton(onClick = onRotate, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.RotateRight, "追尾用プレビューを90度回転")
                    }
                }
                val preview = state.preview
                if (preview != null) {
                    Box(Modifier.fillMaxWidth().aspectRatio(preview.width.toFloat() / preview.height)
                        .pointerInput(state.candidates) {
                            detectTapGestures { position ->
                                val x = (position.x / size.width).coerceIn(0f, 1f)
                                val y = (position.y / size.height).coerceIn(0f, 1f)
                                val target = state.candidates.withIndex()
                                    .filter { it.value.contains(x, y) }
                                    .minByOrNull { it.value.area }
                                if (target != null) onSelect(target.index)
                            }
                        }) {
                        Image(preview.asImageBitmap(), contentDescription = "AIが検出したダンサーの映像",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Canvas(Modifier.fillMaxSize()) {
                            state.candidates.forEachIndexed { index, person ->
                                drawRect(Color(0xFFC5A771),
                                    topLeft = Offset(person.left * size.width, person.top * size.height),
                                    size = Size(person.width * size.width, person.height * size.height),
                                    style = Stroke(width = 3.dp.toPx()))
                            }
                        }
                    }
                }
                Text(state.message, style = MaterialTheme.typography.bodySmall)
                if (state.candidates.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(state.candidates) { index, person ->
                            OutlinedButton(onClick = { onSelect(index) },
                                modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("人物 ${index + 1} · ${(person.confidence * 100).toInt()}%")
                            }
                        }
                    }
                }
                Text("交差・遮蔽で人物を特定できなくなったら、安全のため追尾を止めます。AIの分析は端末内のみ。動画の画質は変更しません。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("キャンセル") } }
    )
}
