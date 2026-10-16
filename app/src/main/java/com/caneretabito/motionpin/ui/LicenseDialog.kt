package com.caneretabito.motionpin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notices by produceState("ライセンスを読み込んでいます…") {
        value = withContext(Dispatchers.IO) {
            val assets = context.assets
            listOf("THIRD_PARTY_NOTICES.txt", "THIRD_PARTY_NOTICES.md", "MODEL_MANIFEST.json", "MODEL_MANIFEST.md").mapNotNull { name ->
                runCatching { assets.open(name).bufferedReader().use { "$name\n\n${it.readText()}" } }.getOrNull()
            }.joinToString("\n\n────────────\n\n").ifBlank {
                "ライセンス資産を読み込めませんでした。配布元のTHIRD_PARTY_NOTICESとMODEL_MANIFESTを参照してください。"
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(16.dp), color = Obsidian, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Text("ライセンスとモデルの来歴", style = MaterialTheme.typography.headlineSmall)
                SelectionContainer(Modifier.weight(1f).padding(top = 16.dp).verticalScroll(rememberScrollState())) { Text(notices, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("閉じる") }
            }
        }
    }
}
