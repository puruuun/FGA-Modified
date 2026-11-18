package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.locations.IScriptAreaTransforms
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations
import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.fate_grand_automata.scripts.modules.SkillLevelScreens
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #504: the selected-slot glow read of the skill / append screens. */
class SkillLevelSelectionTest {
    private val threshold = SkillLevelScreens.SELECTED_GLOW_BLUE_MINUS_RED
    private val clear = listOf(10.0, 12.0, 8.0, 20.0)
    private val glow = listOf(100.0, 140.0, 95.0, 120.0)

    private fun decide(vararg slots: List<Double>?) = SkillLevelScreens.selectedSlotOf(slots.toList())

    @Test fun `all four sides glowing and every other slot clear is that slot`() {
        assertEquals(0, decide(glow, clear, clear))
        assertEquals(2, decide(clear, clear, glow))
        assertEquals(3, decide(clear, clear, clear, glow, clear))
    }

    @Test fun `no glowing slot is no selection`() {
        assertNull(decide(clear, clear, clear))
    }

    @Test fun `one side below the threshold makes the slot unsure`() {
        assertNull(decide(listOf(100.0, 140.0, 95.0, threshold - 1), clear, clear))
    }

    @Test fun `values at the threshold glow and one below do not`() {
        val atThreshold = List(4) { threshold }
        assertEquals(1, decide(clear, atThreshold, clear))
        assertNull(decide(clear, List(4) { threshold - 1 }, clear))
    }

    @Test fun `two fully glowing slots are no selection`() {
        assertNull(decide(glow, glow, clear))
    }

    @Test fun `another slot with a single glowing side is no selection`() {
        assertNull(decide(glow, listOf(clear[0], clear[1], clear[2], threshold), clear))
        assertNull(decide(listOf(threshold, clear[1], clear[2], clear[3]), glow, clear))
    }

    @Test fun `a failed colour read or a wrong side count is no selection`() {
        assertNull(decide(glow, null, clear))
        assertNull(decide(glow, clear.take(3), clear))
    }

    @Test fun `blue minus red uses unsigned bytes and rejects empty or ragged buffers`() {
        val pixels = byteArrayOf(10, 0, 200.toByte(), 0, 0, 255.toByte())
        assertEquals((190 + 255) / 2.0, SkillLevelScreens.meanBlueMinusRed(pixels))
        assertNull(SkillLevelScreens.meanBlueMinusRed(null))
        assertNull(SkillLevelScreens.meanBlueMinusRed(ByteArray(0)))
        assertNull(SkillLevelScreens.meanBlueMinusRed(ByteArray(4)))
    }

    private fun locations(width: Int) = SkillLevelLocations(object : IScriptAreaTransforms {
        override val scriptArea = Region(0, 0, width, 1440)
        override val isWide = scriptArea.size.isWide()
        override val gameServer: GameServer = GameServers.default
        override val canLongSwipe = false
        override fun Location.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Region.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Location.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Region.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Location.yFromBottom() = this + Location(0, scriptArea.bottom)
        override fun Region.yFromBottom() = this + Location(0, scriptArea.bottom)
    })

    @Test fun `glow sides sit 60 px at 720p from the icon centre, 73 px long, and follow the slot click`() {
        for (width in listOf(2560, 3360)) {
            val loc = locations(width)
            for (mode in SkillLevelMode.values()) for (slot in 0 until mode.slots) {
                val click = loc.slotClick(mode, slot)
                val (top, bottom, left, right) = loc.slotGlowSides(mode, slot)
                assertEquals(Region(click.x - 72, click.y - 120, 146, 2), top)
                assertEquals(Region(click.x - 72, click.y + 120, 146, 2), bottom)
                assertEquals(Region(click.x - 120, click.y - 72, 2, 146), left)
                assertEquals(Region(click.x + 120, click.y - 72, 2, 146), right)
            }
        }
        assertEquals(522, locations(2560).slotClick(SkillLevelMode.Skill, 0).y)
        assertEquals(478, locations(3360).slotClick(SkillLevelMode.Skill, 0).y)
    }

    /** Icon-row crops are 860x150 at 720p, placed at (400, 186) of the 1280x720 game area. */
    private fun sideMeans(file: File, mode: SkillLevelMode): List<List<Double>?> {
        val image = ImageIO.read(file)
        val loc = locations(2560)
        return (0 until mode.slots).map { slot ->
            loc.slotGlowSides(mode, slot).map { side ->
                val r = side * 0.5
                val rgb = ByteArray(r.width * r.height * 3)
                var i = 0
                for (y in r.y until r.y + r.height) for (x in r.x until r.x + r.width) {
                    val p = image.getRGB(x - 400, y - 186)
                    rgb[i++] = (p shr 16).toByte(); rgb[i++] = (p shr 8).toByte(); rgb[i++] = p.toByte()
                }
                SkillLevelScreens.meanBlueMinusRed(rgb)!!
            }
        }
    }

    @Test fun `the 16 owner icon-row crops each read exactly their selected slot`() {
        var count = 0
        for (server in listOf("Jp", "Kr")) {
            val dir = listOf(File("docs/reference-images/$server/skill-select"), File("../docs/reference-images/$server/skill-select"))
                .first { it.isDirectory }
            for (file in dir.listFiles { f -> f.extension == "png" }!!.sorted()) {
                val match = Regex("-(skill|append)-.*-sel([1-5])[.]png").find(file.name) ?: error(file.name)
                val mode = if (match.groupValues[1] == "skill") SkillLevelMode.Skill else SkillLevelMode.Append
                val expected = match.groupValues[2].toInt() - 1
                assertEquals(expected, SkillLevelScreens.selectedSlotOf(sideMeans(file, mode)), file.name)
                count++
            }
        }
        assertEquals(16, count)
    }
}
