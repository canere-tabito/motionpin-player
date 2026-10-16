package com.caneretabito.motionpin

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.caneretabito.motionpin.ai.AiCoordinator
import com.caneretabito.motionpin.data.MotionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One process graph prevents rotation or a service from duplicating a GPU job. */
class MotionRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    val repository = MotionRepository(app)
    val ai = AiCoordinator(app, repository)
    private var visible = false
    private var serviceRunning = false
    private var timedOut = false
    private val mutableProcessingNotice = MutableStateFlow<String?>(null)
    val processingNotice = mutableProcessingNotice.asStateFlow()

    @Synchronized
    fun setVisible(value: Boolean) {
        visible = value
        ai.setForeground(!timedOut && (visible || serviceRunning))
    }

    /** Call from a visible Activity immediately after an explicit enqueue/resume. */
    @Synchronized
    fun prepareProcessing() {
        if (!visible) {
            mutableProcessingNotice.value = "AI処理を再開するにはアプリを開いてください。"
            return
        }
        timedOut = false
        ai.setForeground(true)
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                ContextCompat.startForegroundService(app, Intent(app, AiProcessingService::class.java))
                mutableProcessingNotice.value = null
            } catch (failure: RuntimeException) {
                // A denied background/FGS quota must never cause restart loops.
                serviceRunning = false
                mutableProcessingNotice.value = "バックグラウンド処理は開始できません。表示中のみ処理します: ${failure.javaClass.simpleName}"
            }
        }
    }

    @Synchronized
    internal fun onProcessingServiceChanged(running: Boolean) {
        serviceRunning = running
        ai.setForeground(!timedOut && (visible || serviceRunning))
    }

    @Synchronized
    internal fun stopProcessing(reason: String) {
        timedOut = true
        serviceRunning = false
        mutableProcessingNotice.value = reason
        ai.setForeground(false)
        ai.pauseAll(reason)
    }

    companion object {
        @Volatile private var instance: MotionRuntime? = null
        fun get(context: Context): MotionRuntime = instance ?: synchronized(this) {
            instance ?: MotionRuntime(context).also { instance = it }
        }
    }
}
