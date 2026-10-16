package com.caneretabito.motionpin.validation

import com.caneretabito.motionpin.player.VideoViewport
import com.caneretabito.motionpin.player.viewportTransform
import com.caneretabito.motionpin.tracking.*
import java.io.File
import java.util.Locale
import kotlin.math.*

/** Executes production classes. Identity labels belong to the evaluator, never the matcher. */
private data class Detection(val id: String, val box: PersonBox)
private data class Frame(val timeUs: Long, val truth: PersonBox?, val detections: List<Detection>)
private data class Scenario(val name: String, val frames: List<Frame>)
private fun box(x: Float, y: Float = .2f, w: Float = .12f, h: Float = .55f,
                color: Float = .45f) = PersonBox(x, y, x + w, y + h, .91f, List(36) { color })
private fun frame(time: Long, target: PersonBox?, vararg rivals: Detection): Frame =
    Frame(time, target, listOfNotNull(target?.let { Detection("target", it) }) + rivals)

private fun scenarios(): List<Scenario> = buildList {
    add(Scenario("solo_smooth", (0..40).map { frame(it * 250_000L, box(.10f + .012f * it)) }))
    for (count in listOf(2, 4, 8, 12)) {
        add(Scenario("group_$count", (0..40).map { step ->
            val selected = box(.08f + .003f * step, w = .055f, h = .38f)
            val rivals = (1 until count).map { id -> Detection("rival_$id",
                box(.40f + ((id - 1) % 6) * .085f, .12f + (id / 6) * .40f,
                    w = .045f, h = .34f, color = if (id % 2 == 0) .45f else .85f)) }
            frame(step * 250_000L, selected, *rivals.toTypedArray())
        }))
    }
    add(Scenario("same_uniform_crossing", (0..20).map { step ->
        frame(step * 250_000L, box(.12f + .030f * step, w = .08f),
            Detection("rival", box(.72f - .030f * step, w = .08f)))
    }))
    add(Scenario("short_occlusion", (0..16).map { step ->
        val truth = box(.10f + .012f * step)
        Frame(step * 250_000L, truth.takeUnless { step in 5..7 },
            if (step in 5..7) emptyList() else listOf(Detection("target", truth)))
    }))
    add(Scenario("long_occlusion", (0..16).map { step ->
        val truth = box(.10f + .012f * step)
        Frame(step * 250_000L, truth.takeUnless { step in 5..11 },
            if (step in 5..11) emptyList() else listOf(Detection("target", truth)))
    }))
    add(Scenario("dense_sampling_short_occlusion", (0..12).map { step ->
        val truth = box(.20f + .002f * step)
        Frame(step * 50_000L, truth,
            if (step in 1..5) emptyList() else listOf(Detection("target", truth)))
    }))
    add(Scenario("turn_toward_same_uniform_rival", listOf(
        frame(0L, box(.20f, w = .18f), Detection("rival", box(.60f, w = .18f))),
        frame(250_000L, box(.30f, w = .18f), Detection("rival", box(.50f, w = .18f))),
        frame(500_000L, box(.20f, w = .18f), Detection("rival", box(.37f, w = .18f))),
        frame(750_000L, box(.16f, w = .18f), Detection("rival", box(.49f, w = .18f))),
        frame(1_000_000L, box(.14f, w = .18f), Detection("rival", box(.61f, w = .18f)))
    )))
    add(Scenario("merged_detection", listOf(frame(0L, box(.20f, w = .18f)),
        Frame(250_000L, box(.20f, w = .18f), listOf(Detection("merged", box(.11f, w = .36f)))),
        frame(500_000L, box(.21f, w = .18f)))))
    add(Scenario("missing_appearance", listOf(frame(0L, box(.20f)),
        Frame(250_000L, box(.20f), listOf(Detection("rival", box(.20f).copy(appearance = emptyList())))),
        frame(500_000L, box(.21f)))))
    add(Scenario("jump_turn_camera_pan", (0..40).map { step ->
        val x = if (step <= 20) .12f + .018f * step else .48f - .014f * (step - 20)
        frame(step * 200_000L, box(x, .20f - .09f * abs(sin(step * .3f)),
            w = .12f + .015f * sin(step * .7f)))
    }))
    var vfrTime = 0L
    add(Scenario("irregular_pts", (0..30).map { step ->
        if (step > 0) vfrTime += listOf(83_000L, 167_000L, 333_000L, 417_000L)[step % 4]
        frame(vfrTime, box(.12f + .04f * vfrTime / 1_000_000f))
    }))
    add(Scenario("camera_detector_jitter", (0..80).map { step ->
        frame(step * 100_000L, box(.40f + .004f * sin(step * 1.7f),
            .20f + .003f * cos(step * 1.3f), h = .55f + .012f * sin(step * 1.9f)))
    }))
    add(Scenario("rapid_subject_approach", (0..20).map { step ->
        val size = if (step < 8) .38f else min(.88f, .38f + (step - 7) * .055f)
        frame(step * 150_000L, box(.36f, (1f - size) / 2f, .22f, size))
    }))
    add(Scenario("seek_without_reselection", listOf(frame(0L, box(.2f)),
        frame(250_000L, box(.22f)), frame(3_000_000L, box(.25f)))))
}

private fun number(value: Double) = String.format(Locale.ROOT, "%.6f", value)
private fun writeTrace(scenario: Scenario, output: File) {
    output.printWriter().use { writer ->
        writer.println("record,time_us,id,left,top,right,bottom,confidence,appearance")
        for (frame in scenario.frames) {
            writer.println("frame,${frame.timeUs},,,,,,,")
            fun write(type: String, id: String, box: PersonBox) = writer.println(
                "$type,${frame.timeUs},$id,${box.left},${box.top},${box.right},${box.bottom},${box.confidence}," +
                    box.appearance.joinToString(";"))
            frame.truth?.let { write("truth", "target", it) }
            frame.detections.forEach { write("detection", it.id, it.box) }
        }
    }
}

/** Simple unquoted CSV; IDs must not contain commas. Source PTS are integer microseconds. */
private fun readTrace(file: File): Scenario {
    data class Builder(val time: Long, var truth: PersonBox? = null, val detections: MutableList<Detection> = mutableListOf())
    val frames = linkedMapOf<Long, Builder>()
    val lines = file.readLines()
    require(lines.firstOrNull() == "record,time_us,id,left,top,right,bottom,confidence,appearance") { "Unexpected CSV header" }
    lines.drop(1).filter { it.isNotBlank() }.forEachIndexed { line, raw ->
        val p = raw.split(',')
        require(p.size == 9) { "${file.name}:${line + 2}: expected 9 columns" }
        val time = p[1].toLong()
        require(time >= 0L)
        if (p[0] == "frame") {
            require(time !in frames && (frames.isEmpty() || time > frames.keys.last())) { "Frame PTS must increase" }
            frames[time] = Builder(time)
        } else {
            val frame = requireNotNull(frames[time]) { "Declare frame row before observations" }
            require(p[2].matches(Regex("[A-Za-z0-9_-]+"))) { "Identity must be a nonempty simple label" }
            val box = PersonBox(p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6].toFloat(),
                p[7].toFloat(), if (p[8].isEmpty()) emptyList() else p[8].split(';').map(String::toFloat))
            when (p[0]) {
                "truth" -> { require(p[2] == "target" && frame.truth == null && box.valid()); frame.truth = box }
                "detection" -> frame.detections.add(Detection(p[2], box))
                else -> error("Unknown record ${p[0]}")
            }
        }
    }
    require(frames.isNotEmpty()) { "Empty trace" }
    require(file.nameWithoutExtension.matches(Regex("[A-Za-z0-9_-]+"))) { "Trace filename must use letters, digits, '_' or '-'" }
    return Scenario(file.nameWithoutExtension, frames.values.map { Frame(it.time, it.truth, it.detections) })
}

private fun evaluate(scenario: Scenario, directory: File): Map<String, String> {
    val seedFrame = scenario.frames.first()
    val seed = requireNotNull(seedFrame.detections.firstOrNull { it.id == "target" }) { "First frame requires target detection" }.box
    val matcher = StablePersonMatcher(seed, seedFrame.timeUs)
    val keys = mutableListOf(TrackingKeyframe(seedFrame.timeUs, seed))
    var correct = 0; var falseMatches = 0; var ambiguous = 0; var missing = 0; var lost = 0
    var visible = 0; var visibleCorrect = 0; var unavailableUs = 0L; var maxUnavailableUs = 0L; var runUs = 0L
    var previousTime = seedFrame.timeUs; var previousId = "target"; var switches = 0
    val detectorAbsentUs = scenario.frames.zipWithNext().sumOf { (a, b) ->
        if (b.truth != null && b.detections.none { it.id == "target" }) b.timeUs - a.timeUs else 0L
    }
    File(directory, "${scenario.name}-association.csv").printWriter().use { writer ->
        writer.println("time_us,status,selected_id,target_visible,candidates,margin")
        writer.println("${seedFrame.timeUs},SEED,target,${seedFrame.truth != null},${seedFrame.detections.size},1")
        for (frame in scenario.frames.drop(1)) {
            val match = matcher.step(frame.timeUs, frame.detections.map { it.box })
            val id = match.box?.let { selected -> frame.detections.firstOrNull { it.box === selected }?.id }
            val selectedCorrect = id == "target"
            if (frame.truth != null) { visible++; if (selectedCorrect) visibleCorrect++ }
            if (match.status == MatchStatus.MATCHED) {
                require(id != null) { "Matcher returned a box outside the supplied candidates" }
                if (selectedCorrect) correct++ else falseMatches++
                if (id != previousId) switches++
                previousId = id
                keys.add(TrackingKeyframe(frame.timeUs, match.box!!))
            }
            when (match.status) {
                MatchStatus.AMBIGUOUS -> ambiguous++
                MatchStatus.MISSING -> missing++
                MatchStatus.LOST -> lost++
                else -> Unit
            }
            val elapsed = (frame.timeUs - previousTime).coerceAtLeast(0L)
            if (frame.truth != null && !selectedCorrect) { unavailableUs += elapsed; runUs += elapsed }
            else runUs = 0L
            maxUnavailableUs = max(maxUnavailableUs, runUs)
            previousTime = frame.timeUs
            writer.println("${frame.timeUs},${match.status},${id ?: ""},${frame.truth != null},${frame.detections.size},${match.margin}")
        }
    }
    // Sample the actual trajectory + camera at ~30 Hz in normalized square FIT geometry.
    val camera = SmoothFollowCamera()
    var view = VideoViewport(); var previousView: VideoViewport? = null
    var previousVelocity: Pair<Double, Double>? = null; var accelerationSq = 0.0
    var accelerations = 0; var cameraSamples = 0; var cropped = 0; var maximumZoomStep = 0.0
    var cameraAvailable = 0; val dt = 33_333L; val seconds = dt / 1_000_000.0
    File(directory, "${scenario.name}-camera.csv").printWriter().use { writer ->
        writer.println("time_us,available,center_x,center_y,zoom,body_contained")
        var time = seedFrame.timeUs
        while (time <= scenario.frames.last().timeUs) {
            cameraSamples++
            val box = TrackingTrajectory.at(keys, time)
            if (box != null) {
                cameraAvailable++
                view = camera.follow(time, box, view, DancerFraming.FULL)
                check(listOf(view.centerX, view.centerY, view.zoom).all { it.isFinite() })
                val transform = viewportTransform(1f, 1f, 1f, false, view)
                val actualX = .5f - transform.translateX / view.zoom
                val actualY = .5f - transform.translateY / view.zoom
                val halfVisible = .5f / view.zoom
                val contained = box.left >= actualX - halfVisible - .0001f &&
                    box.right <= actualX + halfVisible + .0001f &&
                    box.top >= actualY - halfVisible - .0001f && box.bottom <= actualY + halfVisible + .0001f
                if (!contained) cropped++
                previousView?.let { old ->
                    val velocity = (view.centerX - old.centerX) / seconds to (view.centerY - old.centerY) / seconds
                    previousVelocity?.let { previous ->
                        accelerationSq += ((velocity.first - previous.first) / seconds).pow(2) +
                            ((velocity.second - previous.second) / seconds).pow(2)
                        accelerations++
                    }
                    previousVelocity = velocity
                    maximumZoomStep = max(maximumZoomStep, abs(view.zoom - old.zoom).toDouble())
                }
                previousView = view
                writer.println("$time,true,${view.centerX},${view.centerY},${view.zoom},$contained")
            } else {
                previousView = null; previousVelocity = null
                writer.println("$time,false,,,,")
            }
            time += dt
        }
    }
    return linkedMapOf("scenario" to scenario.name, "observations" to (scenario.frames.size - 1).toString(),
        "visible_observations" to visible.toString(), "correct_matches" to correct.toString(),
        "false_matches" to falseMatches.toString(), "id_switches" to switches.toString(),
        "ambiguous" to ambiguous.toString(), "missing" to missing.toString(), "lost" to lost.toString(),
        "visible_match_fraction" to number(if (visible == 0) 0.0 else visibleCorrect.toDouble() / visible),
        "unavailable_ms" to number(unavailableUs / 1000.0), "max_unavailable_ms" to number(maxUnavailableUs / 1000.0),
        "detector_target_absent_ms" to number(detectorAbsentUs / 1000.0),
        "camera_samples" to cameraSamples.toString(), "camera_available_samples" to cameraAvailable.toString(),
        "camera_cropped_samples" to cropped.toString(),
        "camera_center_acceleration_rms" to number(if (accelerations == 0) 0.0 else sqrt(accelerationSq / accelerations)),
        "camera_max_zoom_step" to number(maximumZoomStep))
}

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Usage: TrackingScenarioEval output_directory [trace.csv ...]" }
    val output = File(args[0]).also { it.mkdirs() }
    val scenarios = if (args.size > 1) args.drop(1).map { readTrace(File(it)) } else scenarios()
    val results = scenarios.map { scenario ->
        writeTrace(scenario, File(output, "${scenario.name}-input.csv"))
        evaluate(scenario, output)
    }
    File(output, "summary.csv").printWriter().use { writer ->
        writer.println(results.first().keys.joinToString(","))
        results.forEach { writer.println(it.values.joinToString(",")) }
    }
    println("Evaluated ${results.size} scenarios using production Kotlin. Reports: ${output.absolutePath}")
}
