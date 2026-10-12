package io.github.fate_grand_automata.prefs.core

import com.fredporciuncula.flow.preferences.Serializer
import io.github.fate_grand_automata.scripts.models.ServantSpamConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val spamJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Shared automatic skill/NP use, retaining the original key and file format. */
class SpamConfigCore(maker: PrefMaker) {
    private val defaultSpamConfig = (1..6).map { ServantSpamConfig() }

    val spam = maker.serialized(
        "spam_x",
        serializer = object : Serializer<List<ServantSpamConfig>> {
            override fun deserialize(serialized: String) =
                try {
                    spamJson.decodeFromString<List<ServantSpamConfig>>(serialized)
                } catch (e: SerializationException) {
                    defaultSpamConfig
                }

            override fun serialize(value: List<ServantSpamConfig>) =
                spamJson.encodeToString(value)
        },
        defaultSpamConfig
    )
}
