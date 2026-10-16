package com.caneretabito.motionpin.ai

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.StatFs
import com.caneretabito.motionpin.data.AiJobEntity
import com.caneretabito.motionpin.data.VideoEntity
import com.caneretabito.motionpin.domain.Rational
import com.caneretabito.motionpin.domain.TimeMapping
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.abs

internal data class PipelinePrepared(val fingerprint: String, val cacheKey: String, val colorContract: String,
    val encoderConfig: String, val estimatedBytes: Long)
internal data class PipelineProgress(val frames: Long, val total: Long, val inferences: Long)
internal data class PipelineResult(val path: String, val fingerprint: String, val cacheKey: String,
    val frames: Long, val inferences: Long, val report: String)

/** URI → display PTS pairs → real native RIFE → native-size encode → validated atomic cache. */
internal class VideoPipeline(private val context: Context) {
    val directory = File(context.filesDir, "ai-cache").apply { mkdirs() }

    suspend fun generate(
        video: VideoEntity, job: AiJobEntity, boundary: () -> Unit,
        prepared: suspend (PipelinePrepared) -> Unit, progress: suspend (PipelineProgress) -> Unit
    ): PipelineResult {
        val uri = Uri.parse(video.uri)
        VideoQualityProbe.requireLocalSeekable(context, uri)
        boundary()
        val source = MediaExtractor().let { extractor ->
            try { extractor.setDataSource(context, uri, null); VideoQualityProbe.inspect(extractor) }
            finally { extractor.release() }
        }
        val contract = VideoQualityProbe.requireSdr(source)
        require(job.startUs >= 0 && job.endUs > job.startUs && job.endUs <= source.durationUs) { "Interval is outside the current source duration" }
        val speed = Rational(job.speedNumerator, job.speedDenominator).normalized()
        require(speed == Rational.HALF || speed == Rational.QUARTER) { "AI generation supports 0.5× and 0.25×" }
        require(job.fpsNumerator == 60L && job.fpsDenominator == 1L) { "This encoder path requires exactly 60fps output" }
        val mapping = TimeMapping(job.startUs, job.endUs, speed)
        val fps = Rational(job.fpsNumerator, job.fpsDenominator)
        val frameCount = mapping.outputFrameCount(fps)
        val plan = StreamingEncoder.plan(source.descriptor, source.bitrate)
        val estimate = Math.addExact(Math.multiplyExact(mapping.outputDurationUs, plan.bitrate.toLong()) / 8_000_000, 64L * 1024 * 1024)
        requireStorage(estimate)
        val fingerprint = VideoQualityProbe.sha256(context, uri, boundary)
        if (video.fingerprintSha256 != null && video.fingerprintSha256 != fingerprint)
            throw QualityGateException("Source content changed since its recorded revision. Refresh/relink the source before generating; existing caches are not reusable.")
        val identity = listOf("motionpin-cache-v3", fingerprint, video.revision, job.startUs, job.endUs,
            "${speed.numerator}/${speed.denominator}", "60/1", NativeRife.MODEL_HASH, NativeRife.ENGINE_HASH,
            "fp32", source.descriptor, plan.cacheIdentity, "muted", "cut-v1", "rgb709-v1").joinToString("\n")
        val key = sha256(identity.toByteArray())
        prepared(PipelinePrepared(fingerprint, key, source.descriptor.toString(), plan.cacheIdentity, estimate))
        val partial = File(directory, "$key-${job.id}.partial.mp4")
        val destination = File(directory, "$key-${job.id}.mp4")
        val reportFile = File(directory, "$key-${job.id}.json")
        val reportPartial = File(directory, "$key-${job.id}.partial.json")
        partial.delete(); reportPartial.delete()
        var committed = false
        val begin = android.os.SystemClock.elapsedRealtime()
        var inferences = 0L; var cutHolds = 0L; var originals = 0L; var decoded = 0L; var duplicates = 0L
        var gpuDescription = "UNVERIFIED"
        try {
            val bytes = Math.multiplyExact(Math.multiplyExact(source.descriptor.width, source.descriptor.height), 3)
            lateinit var inspected: ProbedVideo
            NativeRife(context).use { native ->
                boundary(); gpuDescription = native.open().toString()
                // Native ownership gives pause/playback preemption deterministic host-buffer release.
                // No buffer alias escapes this use block; native.close() frees all three allocations.
                var before = native.allocateFrameBuffer(bytes).order(ByteOrder.nativeOrder())
                var after = native.allocateFrameBuffer(bytes).order(ByteOrder.nativeOrder())
                val output = native.allocateFrameBuffer(bytes).order(ByteOrder.nativeOrder())
                StreamingDecoder(context, uri, source, job.startUs, boundary).use { decoder ->
                    var beforePts = decoder.next(before) ?: throw QualityGateException("No decoded source frame")
                    var afterPts = decoder.next(after)
                    var cut = afterPts != null && SceneCutDetector.isCut(before, after, source.descriptor.width, source.descriptor.height)
                    StreamingEncoder(partial, source.descriptor, plan, boundary).use { encoder ->
                        for (index in 0 until frameCount) {
                            boundary()
                            val target = mapping.sourceTimeForFrameUs(index, fps)
                            while (afterPts != null && afterPts!! <= target) {
                                val reusable = before; before = after; after = reusable
                                beforePts = afterPts!!; afterPts = decoder.next(after)
                                cut = afterPts != null && SceneCutDetector.isCut(before, after, source.descriptor.width, source.descriptor.height)
                            }
                            val frame = if (target <= beforePts || afterPts == null || cut) {
                                if (cut && target > beforePts) cutHolds++ else originals++
                                before
                            } else {
                                val alpha = (target - beforePts).toDouble() / (afterPts!! - beforePts).toDouble()
                                require(alpha > 0 && alpha < 1)
                                native.interpolate(before, after, output, source.descriptor.width, source.descriptor.height, alpha.toFloat())
                                inferences++; boundary(); output
                            }
                            encoder.put(frame, mapping.outputPtsUs(index, fps))
                            if (index % 10 == 0L || index + 1 == frameCount) {
                                requireStorage(32L * 1024 * 1024)
                                progress(PipelineProgress(index + 1, frameCount, inferences))
                            }
                        }
                        encoder.finish(mapping.outputDurationUs)
                    }
                    decoded = decoder.decodedFrames; duplicates = decoder.duplicatePts
                }
                boundary()
                if (inferences == 0L) throw QualityGateException("No intermediate frame was inferred. This interval cannot be labelled AI-generated; choose an interval with valid frame pairs.")
                // Validate the real muxed stream while the native-owned scratch buffer is alive.
                inspected = validateOutput(partial, source, mapping, fps, frameCount, before, boundary)
            }
            boundary()
            if (VideoQualityProbe.sha256(context, uri, boundary) != fingerprint)
                throw QualityGateException("Source changed during generation; unfinished output was discarded")
            val outputHash = hashFile(partial, boundary)
            val elapsed = android.os.SystemClock.elapsedRealtime() - begin
            val report = JSONObject().apply {
                put("schema", 1); put("cacheKey", key); put("sourceSha256", fingerprint); put("sourceRevision", video.revision)
                put("model", NativeRife.MODEL_ID); put("engine", NativeRife.ENGINE_ID); put("gpu", gpuDescription)
                put("modelHash", NativeRife.MODEL_HASH); put("engineHash", NativeRife.ENGINE_HASH)
                put("outputSha256", outputHash); put("outputBytes", partial.length())
                put("source", source.descriptor.toString()); put("actualOutput", inspected.descriptor.toString())
                put("decodeIntermediate", source.descriptor.copy(pixelFormat = "YUV_420_888", codec = "RAW").toString())
                put("inferenceIntermediate", source.descriptor.copy(pixelFormat = "RGB8", chroma = "444", codec = "RAW").toString())
                put("precision", "FP32"); put("fullFrameScale", 1); put("nativeCompositionWidth", source.descriptor.width); put("nativeCompositionHeight", source.descriptor.height)
                put("encoder", plan.cacheIdentity); put("requestedBitrate", plan.bitrate)
                put("measuredContainerBitrate", partial.length() * 8_000_000L / mapping.outputDurationUs)
                put("audio", "muted"); put("startUs", job.startUs); put("endUs", job.endUs)
                put("speedNumerator", job.speedNumerator); put("speedDenominator", job.speedDenominator)
                put("fpsNumerator", fps.numerator); put("fpsDenominator", fps.denominator)
                put("outputFrames", frameCount); put("inferenceCount", inferences); put("originalFrames", originals); put("sceneCutHolds", cutHolds)
                put("decodedFrames", decoded); put("discardedDuplicatePts", duplicates); put("elapsedMs", elapsed)
                put("qualityInspection", "metadata + every sample PTS + first three frames decoded; visual artifacts and encode-only comparison require device review")
                put("peakNativeMemory", "NOT_MEASURED"); put("sceneCutDetection", "heuristic: sampled luma/histogram; not a guarantee")
                put("compression", "Lossy AVC High; target bitrate is not a fidelity guarantee")
            }.toString(2)
            FileOutputStream(partial, true).use { it.fd.sync() }
            FileOutputStream(reportPartial).use { it.write(report.toByteArray()); it.fd.sync() }
            boundary()
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Files.move(reportPartial.toPath(), reportFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            committed = true
            return PipelineResult(destination.absolutePath, fingerprint, key, frameCount, inferences, report)
        } finally {
            partial.delete(); reportPartial.delete()
            if (!committed) { destination.delete(); reportFile.delete() }
        }
    }

    private fun validateOutput(file: File, source: ProbedVideo, mapping: TimeMapping, fps: Rational,
        expectedFrames: Long, scratch: ByteBuffer, boundary: () -> Unit): ProbedVideo {
        val extractor = MediaExtractor()
        val output: ProbedVideo
        try {
            extractor.setDataSource(file.absolutePath); output = VideoQualityProbe.inspect(extractor)
            VideoQualityProbe.requireSdr(source).validateOutput(output.descriptor).requireSupported()
            if (output.descriptor.codec != MediaFormat.MIMETYPE_VIDEO_AVC || output.descriptor.codecProfile != android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                throw QualityGateException("Muxed cache codec/profile changed")
            if (extractor.trackCount != 1) throw QualityGateException("Muted AI cache unexpectedly contains additional tracks")
            extractor.selectTrack(output.track)
            var count = 0L
            while (extractor.sampleTime >= 0) {
                boundary()
                if (count >= expectedFrames || abs(extractor.sampleTime - mapping.outputPtsUs(count, fps)) > 2)
                    throw QualityGateException("Muxed cache presentation timestamps differ from the requested rational output timeline")
                if (extractor.sampleSize <= 0) throw QualityGateException("Muxed cache has an empty video sample")
                count++; extractor.advance()
            }
            if (count != expectedFrames) throw QualityGateException("Muxed cache frame count $count differs from $expectedFrames")
            if (abs(output.durationUs - mapping.outputDurationUs) > 1_000_000L / 60 + 1_000)
                throw QualityGateException("Muxed cache duration differs from requested slow-motion duration")
        } finally { extractor.release() }
        StreamingDecoder(context, Uri.fromFile(file), output, 0, boundary).use { decoder ->
            repeat(minOf(3L, expectedFrames).toInt()) { if (decoder.next(scratch) == null) throw QualityGateException("Completed cache failed decode/playability inspection") }
        }
        return output
    }

    fun requireStorage(bytes: Long) {
        if (StatFs(directory.absolutePath).availableBytes < bytes)
            throw PipelinePause("Insufficient free storage: need at least ${bytes / 1024 / 1024} MiB; output quality was not reduced")
    }

    fun hashFile(file: File, boundary: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { boundary(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
