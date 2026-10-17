package com.caneretabito.motionpin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.caneretabito.motionpin.player.ColorGrade
import kotlin.math.roundToInt

/**
 * Compact live controls: video stays visible above a transparent-scrim bottom sheet.
 * Original comparison is a toggle to support both touch and TalkBack reliably.
 */
@Composable
internal fun ColorGradingPanel(
    grade: ColorGrade,
    blockedReason: String?,
    comparing: Boolean,
    isPin: Boolean,
    onChange: (ColorGrade) -> Unit,
    onCompare: (Boolean) -> Unit,
    onSaveVideoDefault: () -> Unit,
) {
    val enabled = blockedReason == null
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("映像の色調整", color = Ivory, style = MaterialTheme.typography.titleLarge)
        Text(
            if (isPin) "このピン専用の設定として自動保存されます。" else "この動画の設定として自動保存されます。",
            color = Muted, style = MaterialTheme.typography.bodySmall,
        )
        if (blockedReason != null) {
            Text(blockedReason, color = Champagne, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("SDR 8bit限定の試験機能。元ファイルとAIキャッシュは変更しません。GPU処理中の4K60fps性能は実機検証が必要です。",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "元の色" to ColorGrade.ORIGINAL,
                "鮮やか" to ColorGrade.VIVID,
                "暗いステージ" to ColorGrade.DARK_STAGE,
                "暖色" to ColorGrade.WARM,
            ).forEach { (label, preset) ->
                FilterChip(
                    selected = grade == preset, onClick = { onChange(preset) },
                    enabled = enabled,
                    label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        FilterChip(
            selected = comparing,
            onClick = { onCompare(!comparing) },
            enabled = enabled && !grade.isOriginal,
            label = { Text(if (comparing) "原画を表示中 · タップで調整に戻る" else "原画と比較") },
            modifier = Modifier.heightIn(min = 48.dp),
        )

        GradeSlider("明るさ", grade.brightness, enabled, { onChange(grade.copy(brightness = it)) })
        GradeSlider("コントラスト", grade.contrast, enabled, { onChange(grade.copy(contrast = it)) })
        GradeSlider("彩度", grade.saturation, enabled, { onChange(grade.copy(saturation = it)) })
        GradeSlider("色温度（暖色 ↔ 寒色）", grade.temperature, enabled,
            { onChange(grade.copy(temperature = it)) })
        GradeSlider("色合い（Hue）", grade.hue, enabled, { onChange(grade.copy(hue = it)) })

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onChange(ColorGrade.ORIGINAL); onCompare(false) }, enabled = enabled) {
                Text("全項目リセット")
            }
            if (isPin) OutlinedButton(onClick = onSaveVideoDefault, enabled = enabled) {
                Text("動画の基本設定にも保存")
            }
        }
        Text("暗部とハイライトの個別補正は未実装。SDRの明度調整とは別の処理が必要です。",
            color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GradeSlider(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Ivory, style = MaterialTheme.typography.bodyMedium)
            Text(if (value > 0) "+$value" else value.toString(), color = Champagne)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(-100, 100)) },
            valueRange = -100f..100f,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "$label の強さ" },
        )
    }
}
