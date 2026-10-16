package com.caneretabito.motionpin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.caneretabito.motionpin.tracking.DancerTrackingState
import com.caneretabito.motionpin.tracking.TrackerQuality

/** Preview first, then confirm: a small or overlapping box must not immediately choose a stranger. */
@Composable
fun DancerSelectionDialog(
    state: DancerTrackingState, onSelect: (Int) -> Unit, onCancel: () -> Unit,
    onQuality: (TrackerQuality) -> Unit, onRotate: () -> Unit, onStep: (Int) -> Unit
) {
    if (state.phase != "choosing") return
    var selected by remember(state.preview, state.candidates) { mutableStateOf<Int?>(null) }
    val minimumTouchPx = with(LocalDensity.current) { 48.dp.toPx() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("追尾するダンサーを選択") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("枠か番号を選び、金色の枠を確認して追尾を開始します。重なる枠は繰り返しタップで切り替えられます。",
                    style = MaterialTheme.typography.bodySmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(TrackerQuality.entries) { _, quality ->
                        FilterChip(selected = state.quality == quality,
                            onClick = { if (state.quality != quality) onQuality(quality) },
                            label = { Text(quality.title) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Text(if (state.quality == TrackerQuality.HIGH)
                    "高精度：細かい動きや遠くの人物向け。処理負荷は高めです。"
                    else "軽快：処理負荷を抑えたいときに。元動画の画質は同じです。",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        OutlinedButton(onClick = { onStep(-1) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Default.ChevronLeft, null); Text("1秒前")
                        }
                    }
                    item {
                        OutlinedButton(onClick = { onStep(1) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("1秒後"); Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                    item {
                        IconButton(onClick = onRotate, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.RotateRight, "追尾用プレビューを90度回転")
                        }
                    }
                }
                state.preview?.let { preview ->
                    val aspect = preview.width.toFloat() / preview.height
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val previewWidth = minOf(maxWidth, 300.dp * aspect)
                        BoxWithConstraints(Modifier.width(previewWidth).aspectRatio(aspect)
                            .pointerInput(state.preview, state.candidates, minimumTouchPx) {
                                detectTapGestures { position ->
                                    selected = nextDancerSelection(dancerHitCandidates(state.candidates,
                                        position.x, position.y, size.width.toFloat(), size.height.toFloat(), minimumTouchPx), selected)
                                }
                            }) {
                            Image(preview.asImageBitmap(), contentDescription = "検出された人物のプレビュー。下の人物番号で選択できます。",
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                            Canvas(Modifier.fillMaxSize()) {
                                state.candidates.forEachIndexed { index, person ->
                                    val active = selected == index
                                    if (active) drawRect(Champagne.copy(alpha = .15f),
                                        topLeft = Offset(person.left * size.width, person.top * size.height),
                                        size = Size(person.width * size.width, person.height * size.height))
                                    drawRect(if (active) Champagne else Ivory.copy(alpha = .8f),
                                        topLeft = Offset(person.left * size.width, person.top * size.height),
                                        size = Size(person.width * size.width, person.height * size.height),
                                        style = Stroke(width = if (active) 3.dp.toPx() else 1.dp.toPx()))
                                }
                            }
                            // Numbers share the chip order. Chips remain usable when labels overlap.
                            state.candidates.forEachIndexed { index, person ->
                                Surface(modifier = Modifier.offset(
                                    x = (maxWidth * person.left).coerceIn(0.dp, (maxWidth - 28.dp).coerceAtLeast(0.dp)),
                                    y = (maxHeight * person.top - 22.dp).coerceAtLeast(0.dp)),
                                    color = if (selected == index) Champagne else Graphite.copy(alpha = .92f),
                                    contentColor = if (selected == index) Obsidian else Ivory,
                                    shape = RoundedCornerShape(4.dp)) {
                                    Text("${index + 1}", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                Text(state.message, style = MaterialTheme.typography.bodySmall)
                if (state.candidates.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(state.candidates) { index, person ->
                            val position = when {
                                person.cx < .33f -> "左側"
                                person.cx > .67f -> "右側"
                                else -> "中央"
                            }
                            FilterChip(selected = selected == index, onClick = { selected = index },
                                label = { Text("人物 ${index + 1} · $position") },
                                leadingIcon = if (selected == index) ({ Icon(Icons.Default.Check, null) }) else null,
                                modifier = Modifier.heightIn(min = 48.dp).semantics {
                                    contentDescription = "人物 ${index + 1}、映像の$position"
                                    stateDescription = if (selected == index) "選択中" else "未選択"
                                })
                        }
                    }
                }
                Text("交差や遮蔽で人物を特定できない間は画面移動を止めます。AI解析は端末内だけで行い、元動画の画質は変更しません。",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        },
        confirmButton = {
            Button(onClick = { selected?.let(onSelect) }, enabled = selected != null,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(selected?.let { "人物 ${it + 1}を追尾" } ?: "人物を選択")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } }
    )
}
