package io.github.fate_grand_automata.qa.suites

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import io.github.fate_grand_automata.imaging.DroidCvPattern
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.FgoGameAreaManager
import io.github.fate_grand_automata.scripts.locations.IScriptAreaTransforms
import io.github.fate_grand_automata.scripts.modules.PanelLevelEvidence
import io.github.lib_automata.Location
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.Size
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.time.Duration
import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations
import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.fate_grand_automata.scripts.modules.SkillLevelPanelReader
import org.json.JSONArray
import org.json.JSONObject

internal fun FixtureApi.verifySkillLevelPanel(): JSONObject {
    check(!live) { "Offline panels only" }
    val mode = SkillLevelMode.valueOf(case.getString("mode"))
    val e = panelReading(mode, case.getInt("slot"))
    val expected = case.getJSONObject("expected").getInt("level")
    val status = if (expected == 0 && e.locked && e.level == null) "locked"
        else if (e.level == expected) "correct" else if (e.level == null) "abstained" else "wrong"
    val actual = JSONObject().put("level", e.level ?: JSONObject.NULL).put("locked", e.locked)
        .put("candidate", e.candidate ?: JSONObject.NULL).put("score", e.score).put("margin", e.margin)
        .put("reason", e.reason).put("status", status)
    if (e.readings.isNotEmpty()) actual.put("ocr", JSONArray(e.readings.map { JSONArray(listOf(it.text, it.confidence)) }))
    check(status != "wrong" && (expected != 0 || e.locked) && (mode != SkillLevelMode.Skill || status == "correct")) {
        "panel $actual expected=$expected"
    }
    return actual
}

/** Synthetic stress geometry lives only here, never in the product locations. Full 1680x720
 * frames are horizontally squeezed to 1280x720; 16:10 then adds 40px bars above/below.
 * Map the normal-layout panel ROIs through that declared render transform. Lock templates
 * receive the same horizontal render scale; the glyph reader receives degraded pixels. */
private fun FixtureApi.panelReading(mode: SkillLevelMode, slot: Int): PanelLevelEvidence {
    val stress = case.optString("stress")
    if (stress.isEmpty()) return SkillLevelPanelReader(this, SkillLevelLocations(locations), ocr).read(mode, slot)
    check(mode == SkillLevelMode.Append && stress in setOf("16:9", "16:10"))
    val game = FgoGameAreaManager(Size(1280, if (stress == "16:10") 800 else 720)) { Location() }.gameArea
    check(game == Region(0, if (stress == "16:10") 40 else 0, 1280, 720))
    val base = this
    val logical = object : IScriptAreaTransforms by locations {
        override val scriptArea = Region(0, 0, 2560, 1440)
        override val isWide = false
        override fun Location.xFromCenter() = this + Location(1280, 0)
        override fun Region.xFromCenter() = this + Location(1280, 0)
    }
    return fixturePattern(case.getString("file")).use { original ->
        check(original.width == 1680 && original.height == 720)
        original.resize(Size(1280, 720)).use { squeezed ->
            val bytes = ByteArrayOutputStream().also { squeezed.save(it) }.toByteArray()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val canvas = Bitmap.createBitmap(1280, game.height + game.y * 2, Bitmap.Config.ARGB_8888)
            Canvas(canvas).drawBitmap(bitmap, 0f, game.y.toFloat(), null)
            bitmap.recycle()
            val padded = ByteArrayOutputStream().also { canvas.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            canvas.recycle()
            DroidCvPattern(ByteArrayInputStream(padded), false).use { rendered ->
                val factor = 1280.0 / 1680
                fun Region.physical(): Region {
                    val p = this * 0.5
                    val left = ((p.x + 200) * factor).roundToInt()
                    val right = ((p.right + 200) * factor).roundToInt()
                    return Region(left, p.y - 22 + game.y, right - left, p.height)
                }
                val api = object : IFgoAutomataApi by base {
                    override fun Region.getPattern(tag: String): Pattern = rendered.crop(physical()).use { it.copy() }
                    override fun Region.exists(image: Pattern, timeout: Duration, similarity: Double?): Boolean =
                        rendered.crop(physical()).use { crop ->
                            image.resize(Size((image.width * factor).roundToInt(), image.height)).use { scaled ->
                                crop.findMatches(scaled, similarity ?: 0.8).toList().isNotEmpty()
                            }
                        }
                }
                SkillLevelPanelReader(api, SkillLevelLocations(logical), ocr).read(mode, slot)
            }
        }
    }
}
