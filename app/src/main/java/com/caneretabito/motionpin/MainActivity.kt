package com.caneretabito.motionpin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.caneretabito.motionpin.ui.MainViewModel
import com.caneretabito.motionpin.ui.MotionPinApp
import com.caneretabito.motionpin.data.JobStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private val permission = MutableStateFlow("未許可")
    private var pendingRelinkId: String? = null
    private var documentPurpose = "video"
    private var notificationRequested = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) model.notify("通知が許可されていないため、AI進捗はアプリ内で確認してください。バックグラウンド処理はAndroidのタスク管理にも表示されます。")
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        updatePermission(); model.refresh()
    }
    private val document = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult
        val readFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlags != 0 && data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, readFlags) }
                .onFailure { model.notify("この提供元では永続アクセスを取得できませんでした。再起動後に再選択が必要な場合があります。") }
        }
        when (documentPurpose) {
            "backup_import" -> model.importBackup(uri)
            "backup_export" -> model.exportBackup(uri)
            "relink" -> pendingRelinkId?.let { model.relink(it, uri) }
            else -> model.importVideo(uri)
        }
        pendingRelinkId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        documentPurpose = savedInstanceState?.getString("documentPurpose") ?: "video"
        pendingRelinkId = savedInstanceState?.getString("pendingRelinkId")
        notificationRequested = savedInstanceState?.getBoolean("notificationRequested") ?: false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        }
        updatePermission()
        setContent {
            MotionPinApp(model, permission,
                onGrant = ::requestVideoPermission,
                onImportVideo = { pick("video") },
                onExportBackup = { pick("backup_export") },
                onImportBackup = { pick("backup_import") },
                onRelink = { pendingRelinkId = it; pick("relink") })
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(model.ai.isProcessing, model.repository.jobs) { processing, jobs -> processing && jobs.any { it.status == JobStatus.RUNNING } }
                    .collect { processing ->
                        if (processing && Build.VERSION.SDK_INT >= 33 && !notificationRequested && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationRequested = true
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        @Suppress("DEPRECATION")
        val currentDisplay = window.decorView.display ?: windowManager.defaultDisplay
        model.display = currentDisplay
        model.foreground(true)
        updatePermission()
        model.refresh()
    }
    override fun onStop() {
        if (!isChangingConfigurations) model.foreground(false)
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("documentPurpose", documentPurpose)
        outState.putString("pendingRelinkId", pendingRelinkId)
        outState.putBoolean("notificationRequested", notificationRequested)
        super.onSaveInstanceState(outState)
    }

    private fun updatePermission() {
        fun granted(name: String) = checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        permission.value = when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_VIDEO) -> "動画へのアクセス: すべて"
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "動画へのアクセス: 選択した動画のみ"
            Build.VERSION.SDK_INT <= 32 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> "動画へのアクセス: すべて"
            else -> "動画を選択するか、アクセスを許可してください"
        }
    }

    private fun requestVideoPermission() {
        permissions.launch(when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        })
    }

    private fun pick(purpose: String) {
        documentPurpose = purpose
        val exporting = purpose == "backup_export"
        document.launch(Intent(if (exporting) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (purpose.startsWith("backup")) "application/json" else "video/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (exporting) { addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION); putExtra(Intent.EXTRA_TITLE, "MotionPin-backup.json") }
        })
    }
}
