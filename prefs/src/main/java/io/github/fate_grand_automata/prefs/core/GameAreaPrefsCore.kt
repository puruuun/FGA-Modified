package io.github.fate_grand_automata.prefs.core

import com.fredporciuncula.flow.preferences.Serializer
import io.github.lib_automata.GameAreaOffsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class GameAreaPreset(
    val id: String,
    val name: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun offsets() = GameAreaOffsets(left, top, right, bottom)
}

class GameAreaPrefsCore(maker: PrefMaker) {
    // PrefMaker's enum serializer maps retired values (including Duo) to Default.
    val mode = maker.enum("game_area_mode", GameAreaMode.Default)
    val left = maker.int("game_offset_left")
    val top = maker.int("game_offset_top")
    val right = maker.int("game_offset_right")
    val bottom = maker.int("game_offset_bottom")
    val presetId = maker.string("game_area_preset_id")
    val presets = maker.serialized("game_area_presets", object : Serializer<List<GameAreaPreset>> {
        override fun serialize(value: List<GameAreaPreset>) = Json.encodeToString(value)
        override fun deserialize(serialized: String): List<GameAreaPreset> =
            runCatching { Json.decodeFromString<List<GameAreaPreset>>(serialized) }.getOrDefault(emptyList())
    }, emptyList())

    fun customOffsets() = GameAreaOffsets(left.get(), top.get(), right.get(), bottom.get())

    fun setCustomOffsets(offsets: GameAreaOffsets) {
        left.set(offsets.left)
        top.set(offsets.top)
        right.set(offsets.right)
        bottom.set(offsets.bottom)
    }

    fun selectedPreset() = presets.get().firstOrNull { it.id == presetId.get() }

    fun selectedOffsets(): GameAreaOffsets? = when (mode.get()) {
        GameAreaMode.Default -> null
        GameAreaMode.Custom -> customOffsets()
        GameAreaMode.Preset -> selectedPreset()?.offsets()
    }

    fun addPreset(name: String) {
        addPreset(name, customOffsets())
    }

    /** Adds a new snapshot without editing Custom or any existing preset. Selection is explicit. */
    fun addPreset(name: String, offsets: GameAreaOffsets): GameAreaPreset {
        val preset = GameAreaPreset(UUID.randomUUID().toString(), name, offsets.left, offsets.top, offsets.right, offsets.bottom)
        presets.set(presets.get() + preset)
        return preset
    }

    fun deleteSelectedPreset() {
        val id = presetId.get()
        mode.set(GameAreaMode.Default)
        presetId.set("")
        presets.set(presets.get().filterNot { it.id == id })
    }
}
