package io.github.fate_grand_automata.util

import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.scripts.enums.GameServer
import java.util.Locale

/** Names stored on disk, independent of the app's display language. */
object SupportImageNaming {
    private val serverPrefix = Regex("^(JP|KR|EN|CN|TW)\\) ")

    fun storedName(kind: SupportImageKind, name: String, server: GameServer): String =
        if (kind != SupportImageKind.NoblePhantasm || serverPrefix.containsMatchIn(name)) name
        else "${server.simple.uppercase(Locale.ROOT)}) $name"

    /** Each save gets a new file in the same entry, including two slots saved together. */
    fun nextFileName(existing: Set<String>, stem: String = "image"): String =
        generateSequence(1) { it + 1 }
            .map { "$stem-$it.png" }
            .first { it !in existing }
}
