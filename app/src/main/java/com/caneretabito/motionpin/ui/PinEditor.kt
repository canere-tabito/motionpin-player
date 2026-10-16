package com.caneretabito.motionpin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.caneretabito.motionpin.data.PinEntity
import com.caneretabito.motionpin.player.parseSecondsUs
import com.caneretabito.motionpin.player.secondsText
import org.json.JSONArray

@Composable
fun PinEditor(pin: PinEntity, durationUs: Long, onDismiss: () -> Unit, onSave: (PinEntity) -> Any) {
    var title by rememberSaveable(pin.id) { mutableStateOf(pin.title) }
    var isRange by rememberSaveable(pin.id) { mutableStateOf(pin.endUs != null) }
    var startText by rememberSaveable(pin.id) { mutableStateOf(secondsText(pin.startUs)) }
    var endText by rememberSaveable(pin.id) { mutableStateOf(secondsText(pin.endUs ?: (pin.startUs + 5_000_000).coerceAtMost(durationUs))) }
    var speed by rememberSaveable(pin.id) { mutableFloatStateOf(pin.speed) }
    var loop by rememberSaveable(pin.id) { mutableStateOf(pin.loop) }
    var muted by rememberSaveable(pin.id) { mutableStateOf(pin.audioMode == "muted") }
    var zoom by rememberSaveable(pin.id) { mutableFloatStateOf(pin.zoomScale) }
    var x by rememberSaveable(pin.id) { mutableFloatStateOf(pin.zoomCenterX) }
    var y by rememberSaveable(pin.id) { mutableFloatStateOf(pin.zoomCenterY) }
    var note by rememberSaveable(pin.id) { mutableStateOf(pin.note) }
    var tags by rememberSaveable(pin.id) { mutableStateOf(runCatching { val array = JSONArray(pin.tagsJson); (0 until array.length()).joinToString(", ") { array.getString(it) } }.getOrDefault("")) }
    val start = parseSecondsUs(startText)
    val end = parseSecondsUs(endText)
    val validation = when {
        start == null -> "開始時刻を秒または小数で入力してください（例: 12.345）。"
        start < 0 || start >= durationUs -> "開始時刻は0秒以上、動画の末尾より前にしてください。"
        isRange && (end == null || end <= start || end > durationUs) -> "終了時刻は開始より後、動画の末尾以下にしてください。"
        else -> null
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth().padding(12.dp).widthIn(max = 640.dp).fillMaxHeight(.94f), shape = MaterialTheme.shapes.extraLarge, color = Obsidian) {
            Column {
                Text("ピンを編集", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(title, { title = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("タイトル") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!isRange, { isRange = false }, label = { Text("時刻ピン") }, modifier = Modifier.heightIn(min = 48.dp))
                        FilterChip(isRange, { isRange = true }, label = { Text("区間ピン") }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                    TimeInput(if (isRange) "開始（秒）" else "時刻（秒）", startText, { startText = it }, durationUs)
                    if (isRange) TimeInput("終了（秒・末尾は含まない）", endText, { endText = it }, durationUs)
                    Text("動画の長さ ${secondsText(durationUs)} 秒。時刻は元動画に保存します。±0.1秒・ミリ秒入力に対応。decoderのseekをフレーム完全一致とは保証しません。", style = MaterialTheme.typography.bodySmall, color = Muted)
                    validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text("再生速度", color = Champagne)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf(.25f, .5f, .75f, 1f, 1.5f, 2f)) { choice ->
                        FilterChip(speed == choice, { speed = choice }, label = { Text("${choice}×") }, modifier = Modifier.heightIn(min = 48.dp))
                    } }
                    Text("AI区間生成は0.25× / 0.5×、音声ミュートです。画質を維持できない入力は停止します。", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("ループ", Modifier.padding(top = 12.dp)); Switch(loop, { loop = it }) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("音声をミュート", Modifier.padding(top = 12.dp)); Switch(muted, { muted = it }) }
                    Text("ズーム ${"%.1f".format(zoom)}×", color = Champagne)
                    Slider(zoom, { zoom = it }, valueRange = 1f..8f, modifier = Modifier.semantics { contentDescription = "保存するズーム倍率" })
                    Text("中心位置 · 横 ${"%.2f".format(x)} / 縦 ${"%.2f".format(y)}", style = MaterialTheme.typography.bodySmall)
                    Slider(x, { x = it }, valueRange = 0f..1f, modifier = Modifier.semantics { contentDescription = "映像の横方向の中心位置" })
                    Slider(y, { y = it }, valueRange = 0f..1f, modifier = Modifier.semantics { contentDescription = "映像の縦方向の中心位置" })
                    Text("位置は回転メタデータ適用後の映像内で保存。端末の向きや余白には依存せず、AI出力には焼き込みません。", style = MaterialTheme.typography.bodySmall, color = Muted)
                    OutlinedTextField(tags, { tags = it.take(1_000) }, Modifier.fillMaxWidth(), label = { Text("タグ（カンマ区切り）") })
                    OutlinedTextField(note, { note = it.take(16_384) }, Modifier.fillMaxWidth(), label = { Text("メモ") }, minLines = 2, maxLines = 5)
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, Modifier.heightIn(min = 48.dp)) { Text("キャンセル") }
                    Spacer(Modifier.width(12.dp))
                    Button(enabled = validation == null, onClick = {
                        val fraction = speedFraction(speed)
                        val tagsArray = JSONArray(tags.split(',', '、').map { it.trim() }.filter { it.isNotBlank() }.distinct())
                        onSave(pin.copy(title = title.trim(), startUs = start!!, endUs = if (isRange) end else null,
                            speedNumerator = fraction.first, speedDenominator = fraction.second, loop = loop,
                            audioMode = if (muted) "muted" else "pitch-preserved", zoomScale = zoom, zoomCenterX = x, zoomCenterY = y,
                            note = note, tagsJson = tagsArray.toString()))
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("ピンを保存") }
                }
            }
        }
    }
}

@Composable
private fun TimeInput(label: String, value: String, onValue: (String) -> Unit, durationUs: Long) {
    Column {
        OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onValue(secondsText(((parseSecondsUs(value) ?: 0) - 100_000).coerceAtLeast(0))) }, Modifier.heightIn(min = 48.dp)) { Text("−0.1秒") }
            OutlinedButton(onClick = { onValue(secondsText(((parseSecondsUs(value) ?: 0) + 100_000).coerceAtMost(durationUs))) }, Modifier.heightIn(min = 48.dp)) { Text("＋0.1秒") }
        }
    }
}
