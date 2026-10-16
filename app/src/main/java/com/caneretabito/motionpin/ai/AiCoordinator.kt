package com.caneretabito.motionpin.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import com.caneretabito.motionpin.data.AiJobEntity
import com.caneretabito.motionpin.data.JobStatus
import com.caneretabito.motionpin.data.MotionRepository
import com.caneretabito.motionpin.data.PinEntity
import com.caneretabito.motionpin.data.VideoEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class PipelinePause(message: String) : RuntimeException(message)
private class PipelineCancelled : RuntimeException("Cancelled at a safe native boundary")

/** Process-owned scheduler; one worker, one GPU job, persisted state, safe-boundary preemption. */
class AiCoordinator(context: Context, private val repository: MotionRepository) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val commandLock = Mutex()
    private val admissionLock = Any()
    private val power = this.context.getSystemService(PowerManager::class.java)
    private val pipeline = VideoPipeline(this.context)
    private val cancellations = ConcurrentHashMap.newKeySet<String>()
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    private val _schedulerError = MutableStateFlow<String?>(null)
    val schedulerError: StateFlow<String?> = _schedulerError.asStateFlow()
    val jobs = repository.jobs
    private val _generationEta = MutableStateFlow<AiGenerationEta?>(null)
    val generationEta: StateFlow<AiGenerationEta?> = _generationEta.asStateFlow()
    @Volatile private var foreground = false
    @Volatile private var playbackActive = false
    private var playbackAdmissionRevision = 0L
    @Volatile private var closed = false
    @Volatile private var forcedPause: String? = null
    @Volatile private var thermalLatched = false
    @Volatile private var batteryPercent = 0
    @Volatile private var charging = false
    @Volatile private var cacheMaintenance = false
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) thermalLatched = true
        else if (status <= PowerManager.THERMAL_STATUS_LIGHT) thermalLatched = false
        signal.trySend(Unit)
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else 0
                charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            }
            signal.trySend(Unit)
        }
    }

    init {
        thermalLatched = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        power.addThermalStatusListener(this.context.mainExecutor, thermalListener)
        this.context.registerReceiver(batteryReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        })
        scope.launch {
            try {
                reconcileAfterProcessDeath()
                while (!closed) {
                    signal.receive()
                    while (!closed) {
                        val next = repository.allJobs().filter { it.status == JobStatus.QUEUED || it.status == JobStatus.WAITING }
                            .minByOrNull { it.requestedAt } ?: break
                        val reason = blockedReason()
                        if (reason != null) {
                            repository.updateJob(next.copy(status = JobStatus.WAITING, error = "$reason. Resume regenerates the entire interval."))
                            break
                        }
                        val admitted = synchronized(admissionLock) {
                            if (blockedReason() == null) { _isProcessing.value = true; true } else false
                        }
                        if (!admitted) break
                        val shouldContinue = try { process(next) } finally { _isProcessing.value = false }
                        if (!shouldContinue) break
                    }
                }
            } catch (error: Exception) {
                closed = true
                _schedulerError.value = "AI scheduler stopped: ${error.message ?: error.javaClass.simpleName}. Restart the application process after resolving storage/database access."
                forcedPause = _schedulerError.value
            } finally { _isProcessing.value = false; scope.cancel() }
        }
        scope.launch {
            while (!closed) { delay(15_000); if (foreground && forcedPause == null) signal.trySend(Unit) }
        }
    }

    suspend fun enqueue(video: VideoEntity, pin: PinEntity): String {
        check(!closed) { _schedulerError.value ?: "AI coordinator has shut down; restart the application" }
        require(pin.videoId == video.id && pin.endUs != null) { "AI requires an interval pin" }
        require(pin.speed == .5f || pin.speed == .25f) { "Choose 0.5× or 0.25× before AI generation" }
        forcedPause = null
        val jobId = commandLock.withLock {
                val existing = repository.allJobs().firstOrNull { it.pinId == pin.id && it.startUs == pin.startUs && it.endUs == pin.endUs &&
                    it.speedNumerator == pin.speedNumerator && it.speedDenominator == pin.speedDenominator &&
                    it.sourceRevision == video.revision && it.status in setOf(JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.WAITING) }
                if (existing == null) {
                    val job = AiJobEntity(videoId = video.id, pinId = pin.id, startUs = pin.startUs, endUs = checkNotNull(pin.endUs),
                        speedNumerator = pin.speedNumerator, speedDenominator = pin.speedDenominator, sourceRevision = video.revision,
                        modelHash = NativeRife.MODEL_HASH, engineHash = NativeRife.ENGINE_HASH, audioMode = "muted")
                    repository.updateJob(job)
                    repository.getPin(pin.id)?.let { current -> repository.savePin(current.copy(aiJobId = job.id)) }
                    job.id
                } else existing.id
        }
        signal.trySend(Unit)
        return jobId
    }

    fun cancel(jobId: String) {
        cancellations += jobId
        scope.launch {
            repository.getJob(jobId)?.takeIf { it.status != JobStatus.COMPLETED }?.let {
                repository.updateJob(it.copy(status = JobStatus.CANCELLED, error = "Cancelled. Any in-flight inference releases resources at its next boundary."))
            }
            signal.trySend(Unit)
        }
    }

    suspend fun resume(jobId: String) {
        check(!closed) { _schedulerError.value ?: "AI coordinator has shut down; restart the application" }
        forcedPause = null; cancellations -= jobId
        repository.getJob(jobId)?.takeIf { it.status != JobStatus.RUNNING && it.status != JobStatus.COMPLETED }?.let {
                repository.updateJob(it.copy(status = JobStatus.QUEUED, progress = 0f, outputPath = null,
                    inferenceCount = 0, outputFrameCount = 0, error = "Regenerating the complete interval; partial MP4 is never appended"))
        }
        signal.trySend(Unit)
    }

    fun setForeground(value: Boolean) { synchronized(admissionLock) { foreground = value }; signal.trySend(Unit) }
    fun setPlaybackActive(value: Boolean) {
        synchronized(admissionLock) {
            playbackAdmissionRevision++
            playbackActive = value
        }
        signal.trySend(Unit)
    }

    /** A disposed player may still own a non-interruptible tracking decoder. */
    fun releasePlaybackWhenIdle(awaitIdle: suspend () -> Unit) {
        val revision = synchronized(admissionLock) { playbackAdmissionRevision }
        scope.launch {
            try {
                awaitIdle()
                synchronized(admissionLock) {
                    // A new player/selection may have acquired admission while release waited.
                    if (revision == playbackAdmissionRevision) {
                        playbackAdmissionRevision++
                        playbackActive = false
                    }
                }
                signal.trySend(Unit)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Keep admission closed if native resource release could not be established.
                throw cancelled
            } catch (_: Exception) {
                // Fail closed; a later explicit playback/AI action can re-establish admission.
            }
        }
    }

    /** Call before preparing playback: waits until the active inference, decoder and encoder are released. */
    suspend fun awaitPlaybackReady() { setPlaybackActive(true); isProcessing.first { !it } }

    /** Before cache playback, verify current source bytes and cache bytes, not merely a URI/revision. */
    suspend fun validateCache(jobId: String): AiJobEntity = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val job = repository.getJob(jobId) ?: throw QualityGateException("AI job no longer exists")
        if (job.status != JobStatus.COMPLETED || job.modelHash != NativeRife.MODEL_HASH || job.engineHash != NativeRife.ENGINE_HASH)
            throw QualityGateException("Cache is incomplete or belongs to a different model/engine revision")
        val associated = repository.getVideo(job.videoId) ?: throw QualityGateException("Source association missing")
        VideoQualityProbe.requireLocalSeekable(context, android.net.Uri.parse(associated.uri))
        val video = repository.ensureFingerprint(job.videoId)
        if (video.revision != job.sourceRevision || video.fingerprintSha256 != job.sourceFingerprint)
            throw QualityGateException("Source fingerprint/revision differs from this cache")
        val file = job.outputPath?.let(::File) ?: throw QualityGateException("Cache path missing")
        if (file.canonicalFile.parentFile != pipeline.directory.canonicalFile || !file.isFile)
            throw QualityGateException("Cache file missing or outside the private cache")
        val report = JSONObject(File(file.path.removeSuffix(".mp4") + ".json").readText())
        if (!matchesReport(job, report) || report.optLong("outputBytes") != file.length() || report.optString("outputSha256") != pipeline.hashFile(file) {})
            throw QualityGateException("Cache quality identity or file digest changed; regenerate before playback")
        job
    }

    /** Explicit storage maintenance. Pins/source files remain; cache rows become regenerable. */
    suspend fun clearCompletedCaches(): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        synchronized(admissionLock) {
            if (playbackActive || _isProcessing.value || cacheMaintenance)
                throw QualityGateException("Close video playback and pause AI before clearing caches; referenced caches are protected")
            cacheMaintenance = true
            _isProcessing.value = true
        }
        try {
            var removed = 0
            commandLock.withLock {
                repository.allJobs().filter { it.status == JobStatus.COMPLETED }.forEach { job ->
                    val file = job.outputPath?.let(::File)
                    if (file != null && file.canonicalFile.parentFile != pipeline.directory.canonicalFile)
                        throw QualityGateException("Refusing to delete a path outside the private AI cache")
                    repository.updateJob(job.copy(status = JobStatus.INTERRUPTED, outputPath = null, progress = 0f,
                        error = "AI cache cleared by request. Pin preserved; resume regenerates the complete interval."))
                    if (file != null) {
                        if (file.delete()) removed++
                        File(file.path.removeSuffix(".mp4") + ".json").delete()
                    }
                }
            }
            removed
        } finally {
            synchronized(admissionLock) { cacheMaintenance = false; _isProcessing.value = false }
            signal.trySend(Unit)
        }
    }

    private fun matchesReport(job: AiJobEntity, report: JSONObject): Boolean =
        report.optString("cacheKey") == job.cacheKey && report.optString("sourceSha256") == job.sourceFingerprint &&
            report.optLong("sourceRevision", -1) == job.sourceRevision && report.optString("modelHash") == job.modelHash &&
            report.optString("engineHash") == job.engineHash && report.optLong("startUs", -1) == job.startUs && report.optLong("endUs", -1) == job.endUs &&
            report.optLong("speedNumerator", -1) == job.speedNumerator && report.optLong("speedDenominator", -1) == job.speedDenominator &&
            report.optLong("fpsNumerator", -1) == job.fpsNumerator && report.optLong("fpsDenominator", -1) == job.fpsDenominator &&
            report.optString("audio") == job.audioMode && report.optString("source") == job.colorContract &&
            report.optString("encoder") == job.encoderConfig && report.optLong("inferenceCount", 0) == job.inferenceCount && job.inferenceCount > 0 &&
            report.optLong("outputFrames", 0) == job.outputFrameCount

    /** Notification stop/OS timeout latches a pause; only explicit enqueue/resume clears it. */
    fun pauseAll(reason: String) {
        forcedPause = reason
        scope.launch {
            repository.allJobs().filter { it.status == JobStatus.QUEUED || it.status == JobStatus.WAITING }.forEach {
                repository.updateJob(it.copy(status = JobStatus.WAITING, error = "$reason. Explicit resume regenerates the entire interval."))
            }
            signal.trySend(Unit)
        }
    }
    fun pause(reason: String) = pauseAll(reason)

    fun shutdown() {
        closed = true; forcedPause = "Application is shutting down"; signal.trySend(Unit)
        runCatching { power.removeThermalStatusListener(thermalListener) }
        runCatching { context.unregisterReceiver(batteryReceiver) }
    }

    private fun blockedReason(): String? = when {
        closed -> "Application stopped"
        cacheMaintenance -> "Cache storage maintenance is in progress"
        forcedPause != null -> forcedPause
        !foreground -> "AI paused in background; return to the app to regenerate"
        playbackActive -> "AI yielded GPU and codec resources to video playback"
        thermalLatched -> "Thermal SEVERE or above; waiting until LIGHT or below"
        power.isPowerSaveMode -> "Battery saver is active"
        batteryPercent <= 15 && !charging -> "Battery is at or below 15% and not charging"
        else -> null
    }

    private fun boundary(jobId: String) {
        if (jobId in cancellations) throw PipelineCancelled()
        blockedReason()?.let { throw PipelinePause(it) }
    }

    private suspend fun process(initial: AiJobEntity): Boolean {
        var current = initial.copy(status = JobStatus.RUNNING, progress = 0f, inferenceCount = 0, outputFrameCount = 0,
            modelHash = NativeRife.MODEL_HASH, engineHash = NativeRife.ENGINE_HASH,
            outputPath = null, cacheKey = null, colorContract = "", encoderConfig = "", precision = "fp32", audioMode = "muted",
            error = "Preparing original-resolution SDR pipeline; audio is muted", capability = "UNVERIFIED")
        var unpublishedOutput: String? = null
        val etaEstimator = FrameEtaEstimator()
        _generationEta.value = null
        try {
            repository.updateJob(current)
            boundary(current.id)
            val video = repository.getVideo(current.videoId) ?: throw QualityGateException("Source video record is missing")
            if (video.revision != current.sourceRevision) throw QualityGateException("Source revision changed; create a new job for this pin")
            val result = pipeline.generate(video, current, { boundary(current.id) }, prepared = { ready ->
                val freshVideo = repository.getVideo(video.id) ?: throw QualityGateException("Source record disappeared")
                if (freshVideo.revision != video.revision || freshVideo.uri != video.uri) throw QualityGateException("Source association changed during fingerprint verification")
                repository.recordVerifiedFingerprint(video.id, video.revision, video.uri, ready.fingerprint)
                current = current.copy(sourceFingerprint = ready.fingerprint, cacheKey = ready.cacheKey,
                    colorContract = ready.colorContract, encoderConfig = ready.encoderConfig,
                    error = "Generating real RIFE frames at original resolution. Audio is muted; visual/device quality remains unverified.")
                repository.updateJob(current)
            }, progress = { progress ->
                _generationEta.value = AiGenerationEta(current.id, progress.frames, progress.total,
                    etaEstimator.observe(progress.frames, progress.total, SystemClock.elapsedRealtime()))
                current = current.copy(progress = progress.frames.toFloat() / progress.total * .95f,
                    inferenceCount = progress.inferences, outputFrameCount = progress.frames)
                repository.updateJob(current)
            })
            unpublishedOutput = result.path
            boundary(current.id)
            val freshVideo = repository.getVideo(video.id)
            if (freshVideo?.revision != video.revision || freshVideo.uri != video.uri) {
                File(result.path).delete(); File(result.path.removeSuffix(".mp4") + ".json").delete()
                throw QualityGateException("Source association changed; cache was discarded")
            }
            current = current.copy(status = JobStatus.COMPLETED, progress = 1f, outputPath = result.path,
                sourceFingerprint = result.fingerprint, cacheKey = result.cacheKey, inferenceCount = result.inferences,
                outputFrameCount = result.frames, capability = "SUPPORTED",
                error = "Generated muted 60fps cache. Metadata, PTS and sampled decode passed; visual quality/device performance not certified.")
            repository.updateJob(current)
            unpublishedOutput = null
            return true
        } catch (cancelled: PipelineCancelled) {
            return persistFailure(current.copy(status = JobStatus.CANCELLED, outputPath = null, error = "Cancelled; all native/codec resources released and unfinished output removed"))
        } catch (paused: PipelinePause) {
            persistFailure(current.copy(status = JobStatus.WAITING, outputPath = null,
                error = "${paused.message}. Resources released. Resume regenerates the full interval."))
            return false
        } catch (error: Throwable) {
            return persistFailure(current.copy(status = JobStatus.FAILED, outputPath = null,
                capability = if (error is QualityGateException) "UNSUPPORTED" else "UNVERIFIED",
                error = "${error.javaClass.simpleName}: ${error.message ?: "Native/codec execution failed"}. No reduced-quality fallback was used."))
        } finally {
            _generationEta.value = null
            unpublishedOutput?.let { path -> File(path).delete(); File(path.removeSuffix(".mp4") + ".json").delete() }
        }
    }

    private suspend fun persistFailure(job: AiJobEntity): Boolean {
        // A stale/deleted source must not kill the sole worker when recording its failure.
        return try { repository.updateJob(job); true } catch (error: Exception) {
            _schedulerError.value = "Cannot persist AI failure: ${error.message}. Processing paused; reopen after resolving source/database access."
            forcedPause = _schedulerError.value
            false
        }
    }

    private suspend fun reconcileAfterProcessDeath() {
        val jobs = repository.allJobs()
        val completedPaths = mutableSetOf<String>()
        jobs.forEach { job ->
            if (job.status == JobStatus.COMPLETED) {
                val file = job.outputPath?.let(::File)
                val valid = file != null && file.canonicalFile.parentFile == pipeline.directory.canonicalFile && file.isFile && file.length() > 0 &&
                    runCatching {
                        val report = JSONObject(File(file.path.removeSuffix(".mp4") + ".json").readText())
                        job.modelHash == NativeRife.MODEL_HASH && job.engineHash == NativeRife.ENGINE_HASH &&
                            matchesReport(job, report) &&
                            report.getLong("outputBytes") == file.length() && report.getString("outputSha256").matches(Regex("[a-f0-9]{64}"))
                    }.getOrDefault(false)
                if (valid) { completedPaths += file!!.canonicalPath; completedPaths += file.canonicalPath.removeSuffix(".mp4") + ".json" }
                else repository.updateJob(job.copy(status = JobStatus.INTERRUPTED, outputPath = null, progress = 0f,
                    error = "Cache file/report missing or quality identity mismatched. Pin preserved; explicitly resume to regenerate."))
            } else if (job.status == JobStatus.RUNNING) {
                repository.updateJob(job.copy(status = JobStatus.INTERRUPTED, progress = 0f, outputPath = null,
                    error = "Process ended before atomic completion. Resume regenerates the complete interval."))
            }
        }
        // Orphans are never promoted to ready; the DB is the publication boundary. No completed cache is evicted here.
        pipeline.directory.listFiles()?.forEach { file -> if (file.canonicalPath !in completedPaths && file.isFile) file.delete() }
    }
}
