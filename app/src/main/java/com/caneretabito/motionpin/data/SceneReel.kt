package com.caneretabito.motionpin.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Scene reels reference existing interval pins; the source files are never copied. */
data class SceneReel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pinIds: List<String> = emptyList(),
    val shuffle: Boolean = false,
    val repeat: Boolean = true
)

/**
 * Stored as one bounded versioned setting. The existing Room settings backup includes it;
 * no DB schema migration or separate media export is required.
 */
object SceneReelCodec {
    const val SETTINGS_KEY = "sceneReels.v1"
    const val MAX_VALUE_CHARS = 4000 // BackupCodec enforces <= 4096 per setting.
    fun encode(reels: List<SceneReel>): String {
        require(reels.size <= 20 && reels.distinctBy { it.id }.size == reels.size)
        require(reels.sumOf { it.pinIds.size } <= 80) { "Too many clips for a backed-up reel" }
        val root = JSONObject().put("version", 1)
        val array = JSONArray()
        for (reel in reels) {
            require(reel.name.isNotBlank() && reel.name.length <= 100)
            require(reel.pinIds.size <= 80 && reel.pinIds.distinct().size == reel.pinIds.size)
            require(UUID.fromString(reel.id).toString() == reel.id)
            reel.pinIds.forEach { require(UUID.fromString(it).toString() == it) }
            array.put(JSONObject().put("id", reel.id).put("name", reel.name)
                .put("pins", JSONArray(reel.pinIds)).put("shuffle", reel.shuffle).put("repeat", reel.repeat))
        }
        val text = root.put("reels", array).toString()
        require(text.length <= MAX_VALUE_CHARS) { "リールが保存上限に達しました。不要なシーンを整理してください。" }
        return text
    }
    fun decode(source: String?): List<SceneReel> {
        if (source.isNullOrBlank()) return emptyList()
        require(source.length <= MAX_VALUE_CHARS)
        val root = JSONObject(source)
        require(root.getInt("version") == 1) { "Unsupported reel version" }
        val items = root.getJSONArray("reels")
        require(items.length() <= 20)
        val reels = (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            val pins = item.getJSONArray("pins")
            require(pins.length() <= 80)
            SceneReel(id = item.getString("id"), name = item.getString("name"),
                pinIds = (0 until pins.length()).map { pins.getString(it) },
                shuffle = item.getBoolean("shuffle"), repeat = item.getBoolean("repeat"))
        }
        encode(reels) // Validate ids, duplicates, limits and safe backup size.
        return reels
    }
}
