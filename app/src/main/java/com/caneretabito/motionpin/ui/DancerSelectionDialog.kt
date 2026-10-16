package com.caneretabito.motionpin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.caneretabito.motionpin.tracking.DancerTrackingState

/** Explicit target selection avoids the ambiguous "track the most prominent person" shortcut. */
@Composable
fun DancerSelectionDialog(state: DancerTrackingState, onSelect: (Int) -> Unit, onCancel: () -> Unit) {
    if (state.phase != "choosing") return
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("追尾するダンサーを選択") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("黄色の枠が検出された人物です。追いかけたい人を映像上でタップ。")
                val bitmap = state.preview
                if (bitmap != null) {
                    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)
                        .pointerInput(state.candidates) {
                            detectTapGestures { position ->
                                val x = (position.x / size.width).coerceIn(0f, 1f)
                                val y = (position.y / size.height).coerceIn(0f, 1f)
                                val choice = state.candidates.withIndex()
                                    .filter { it.value.contains(x, y) }
                                    .minByOrNull { it.value.width * it.value.height }
                                if (choice != null) onSelect(choice.index)
                            }
                        }) {
                        Image(bitmap.asImageBitmap(), contentDescription = "人物を選択するための動画フレーム",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Canvas(Modifier.fillMaxSize()) {
                            state.candidates.forEach { person ->
                                drawRect(color = Color(0xFFC5A771),
                                    topLeft = Offset(person.left * size.width, person.top * size.height),
                                    size = Size(person.width * size.width, person.height * size.height),
                                    style = Stroke(width = 3.dp.toPx()))
                            }
                        }
                    }
                }
                if (state.candidates.isEmpty()) Text(state.message)
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(state.candidates) { index, _ ->
                        OutlinedButton(onClick = { onSelect(index) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("人物 ${index + 1} を追尾")
                        }
                    }
                }
                Text("端末内で解析します。人物が交差・隠れると追尾を停止する場合があります。元動画の画質・HDRは変えません。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("キャンセル") } }
    )
}
