package io.github.fate_grand_automata.util

import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.scripts.enums.GameServer
import kotlin.test.Test
import kotlin.test.assertEquals

class SupportImageNamingTest {
    @Test fun `JP and KR names use the game server`() {
        assertEquals("JP) 멜뤼진", SupportImageNaming.storedName(SupportImageKind.NoblePhantasm, "멜뤼진", GameServer.Jp.Original))
        assertEquals("KR) 멜뤼진", SupportImageNaming.storedName(SupportImageKind.NoblePhantasm, "멜뤼진", GameServer.Kr))
        assertEquals("JP) 멜뤼진", SupportImageNaming.storedName(SupportImageKind.NoblePhantasm, "멜뤼진", GameServer.Jp.BetterFGO))
    }

    @Test fun `an existing server prefix is preserved even on a different server`() {
        for (name in listOf("JP) 멜뤼진", "KR) 멜뤼진", "EN) Melusine")) {
            assertEquals(name, SupportImageNaming.storedName(SupportImageKind.NoblePhantasm, name, GameServer.Jp.Original))
        }
    }

    @Test fun `servant CE and friend names are unchanged`() {
        for (kind in listOf(SupportImageKind.Servant, SupportImageKind.CE, SupportImageKind.Friend)) {
            assertEquals("멜뤼진", SupportImageNaming.storedName(kind, "멜뤼진", GameServer.Kr))
        }
    }

    @Test fun `repeated saves add distinct files to the same entry without overwriting`() {
        val entries = mutableMapOf<String, MutableSet<String>>()
        repeat(3) {
            val entry = SupportImageNaming.storedName(SupportImageKind.NoblePhantasm, "멜뤼진", GameServer.Jp.Original)
            val files = entries.getOrPut(entry) { mutableSetOf("original.png", "image-2.png") }
            files.add(SupportImageNaming.nextFileName(files))
        }
        assertEquals(mapOf("JP) 멜뤼진" to setOf("original.png", "image-1.png", "image-2.png", "image-3.png", "image-4.png")), entries)
    }

    @Test fun `explicit subfolder file names also append`() {
        assertEquals("JP) 멜뤼진/grand", SupportImageNaming.storedName(SupportImageKind.NoblePhantasm, "멜뤼진/grand", GameServer.Jp.Original))
        assertEquals("grand-2.png", SupportImageNaming.nextFileName(setOf("grand-1.png"), "grand"))
    }
}
