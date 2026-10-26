package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.PanelAppendMatcher
import io.github.fate_grand_automata.scripts.modules.PanelSampleSheet
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlinx.serialization.json.*

/** Host measurement only. No Python reimplementation of features, channels, gates or bank decisions. */
object PanelAppendEvaluation {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0])
        fun json(path: String) = Json.parseToJsonElement(File(root, path).readText())
        val cases = json("docs/reference-images/cases/skillLevelPanel.json").jsonObject.getValue("cases").jsonArray
            .map { it.jsonObject }.filter { it.getValue("mode").jsonPrimitive.content == "Append" }
        val crossSources = json("docs/reference-images/panel-0021-template-sources.json").jsonArray.map { it.jsonObject }
        val ownSources = json("docs/reference-images/panel-0021-own-template-sources.json").jsonObject
            .getValue("samples").jsonArray.map { it.jsonObject }
        fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
        fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.int
        val layouts = cases.associate { it.str("file") to it.str("layout") }
        check((crossSources + ownSources).all { layouts[it.str("reference")] == "native-wide" }) {
            "Native 16:9 is validation only, never a sample source"
        }
        val images = (cases.map { it.str("file") } + crossSources.map { it.str("reference") } +
            ownSources.map { it.str("reference") }).distinct().associateWith { ImageIO.read(File(root, it)) }
        fun rgb(path: String, x: Int = 0, y: Int = 0, w: Int = images.getValue(path).width,
                h: Int = images.getValue(path).height): ByteArray {
            val im = images.getValue(path)
            return ByteArray(w * h * 3) { i ->
                val pixel = im.getRGB(x + i / 3 % w, y + i / 3 / w)
                (pixel shr (16 - i % 3 * 8)).toByte()
            }
        }
        // Decoded full-frame RGB identifies duplicates even when PNG encodings differ.
        val hashes = images.keys.associateWith { path ->
            MessageDigest.getInstance("SHA-256").digest(rgb(path)).joinToString("") { "%02x".format(it) }
        }
        fun sheet(server: String, own: Boolean): PanelSampleSheet {
            val file = File(root, "app/src/main/assets/$server/skill_level_panel_append_${if (own) "own_" else ""}samples.png")
            val im = ImageIO.read(file)
            val pixels = ByteArray(im.width * im.height * 3) { i ->
                (im.getRGB(i / 3 % im.width, i / 3 / im.width) shr (16 - i % 3 * 8)).toByte()
            }
            return PanelSampleSheet(pixels, im.width, im.height)
        }
        val banks = listOf("Kr", "Jp").associateWith { sheet(it, false) to sheet(it, true) }
        val output = buildJsonArray {
            for (version in listOf("beta.3", "beta.4")) for (lofo in listOf(false, true)) for (case in cases) {
                val server = case.str("server")
                val slot = case.num("slot")
                val expected = case.getValue("expected").jsonObject.num("level")
                if (expected == 0) continue // Lock recognition is validated by the device suite.
                val file = case.str("file")
                val (cross, own) = banks.getValue(server)
                val heldOut = if (lofo) hashes.filterValues { it == hashes.getValue(file) }.keys else emptySet()
                val crossExcluded = crossSources.filter { it.str("server") == server && it.str("reference") in heldOut }.map {
                    (it.num("level") - 1) * 4 + listOf(1, 3, 4, 5).indexOf(it.num("sourceSlot"))
                }.toSet()
                val ownExcluded = ownSources.filter { it.str("server") == server && it.str("reference") in heldOut }
                    .map { it.num("cellIndex") }.toSet()
                check(!lofo || (crossSources.none { it.str("server") == server && it.str("reference") in heldOut &&
                    (it.num("level") - 1) * 4 + listOf(1, 3, 4, 5).indexOf(it.num("sourceSlot")) !in crossExcluded }))
                check(!lofo || ownSources.none { it.str("server") == server && it.str("reference") in heldOut && it.num("cellIndex") !in ownExcluded })
                val matcher = PanelAppendMatcher(cross.rgb, cross.width, cross.height,
                    if (version == "beta.4") own.copy(excludedCells = ownExcluded) else null, crossExcluded)
                val x = listOf(473, 650, 828, 1005, 1183)[slot] - 5 + if (case.str("layout") == "native-wide") 200 else 0
                val y = if (case.str("layout") == "native-wide") 273 else 294
                val e = matcher.read(rgb(file, x, y, 33, 21), 33, 21, slot)
                add(buildJsonObject {
                    put("version", version); put("measurement", if (lofo) "leave-one-frame-out" else "product-bank")
                    put("name", case.str("name")); put("server", server); put("slot", slot + 1)
                    put("layout", case.str("layout")); put("expected", expected)
                    put("level", e.level?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("candidate", e.candidate?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("score", e.score); put("margin", e.margin); put("reason", e.reason)
                    put("status", if (e.level == expected) "correct" else if (e.level == null) "abstained" else "wrong")
                    put("excludedCrossCells", crossExcluded.size); put("excludedOwnCells", ownExcluded.size)
                    put("heldOutReferences", buildJsonArray { heldOut.sorted().forEach { add(it) } })
                    put("framePixelSha256", hashes.getValue(file))
                })
            }
        }
        val destination = File(args[1])
        destination.parentFile.mkdirs()
        destination.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), output) + "\n")
        for ((group, entries) in output.map { it.jsonObject }.groupBy { it.str("version") + " / " + it.str("measurement") }) {
            println("$group: ${entries.groupingBy { it.str("status") }.eachCount()}")
        }
        check(output.none { it.jsonObject.str("status") == "wrong" }) { "Wrong reading: stop; do not relax gates. See $destination" }
        println(destination)
    }
}
