package io.github.fate_grand_automata.prefs

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.prefs.core.PrefType
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BattleConfigFileTest {
    @Test
    fun `card count start settings survive export and import`() {
        val values = mapOf(
            "command_start_mode" to "CardCount",
            "command_start_servant" to 2,
            "command_start_card_count" to 3,
            "command_start_max_turns" to 0
        )
        assertThat(BattleConfigFile.decode(BattleConfigFile.encode(values))).isEqualTo(values)
    }

    private fun roundTrip(values: Map<String, *>) =
        BattleConfigFile.decode(BattleConfigFile.encode(values))

    @Test
    fun `every value type a battle config stores survives a round trip`() {
        val decoded = roundTrip(
            mapOf(
                "autoskill_name" to "Farming",
                "shuffle_cards_wave" to 3,
                "use_servant_priority" to true,
                "battle_config_mat" to setOf("Proof", "Bone")
            )
        )

        assertThat(decoded["autoskill_name"]).isEqualTo("Farming")
        assertThat(decoded["shuffle_cards_wave"]).isEqualTo(3)
        assertThat(decoded["use_servant_priority"]).isEqualTo(true)
        assertThat(decoded["battle_config_mat"] as List<*>).containsOnly("Proof", "Bone")
    }

    /*
     * An int decoded as a double is not an Int, and the import side drops what it cannot type,
     * so a widened number would silently reset the preference to its default.
     */
    @Test
    fun `a whole number comes back as an Int`() {
        assertThat(roundTrip(mapOf("shuffle_cards_wave" to 3))["shuffle_cards_wave"])
            .isNotNull().isInstanceOf(Int::class)
    }

    /*
     * PrefMaker.stringAsInt stores numbers as strings, so JSON quoting is the only thing that
     * tells those apart from real int preferences.
     */
    @Test
    fun `a numeric string stays a String`() {
        assertThat(roundTrip(mapOf("autoskill_party" to "-1"))["autoskill_party"])
            .isNotNull().isInstanceOf(String::class)
    }

    @Test
    fun `a string that reads as a boolean stays a String`() {
        assertThat(roundTrip(mapOf("autoskill_notes" to "true"))["autoskill_notes"])
            .isEqualTo("true")
    }

    @Test
    fun `keys this version does not know are kept`() {
        assertThat(roundTrip(mapOf("some_future_pref" to "value"))["some_future_pref"])
            .isEqualTo("value")
    }

    @Test
    fun `the folder survives a round trip`() {
        assertThat(roundTrip(mapOf("battle_config_folder" to "Lancer"))["battle_config_folder"])
            .isEqualTo("Lancer")
    }

    @Test
    fun `a file without a folder decodes without one`() {
        val decoded = roundTrip(mapOf("autoskill_name" to "Old"))
        assertThat(decoded.containsKey("battle_config_folder")).isEqualTo(false)
    }

    @Test
    fun `a file that is not a JSON object is rejected`() {
        assertFailsWith<SerializationException> {
            BattleConfigFile.decode("not a battle config")
        }
    }

    @Test
    fun `a boolean given for a string pref is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BattleConfigFile.decode("""{"autoskill_name":true}""")
        }
    }

    @Test
    fun `an int pref given as a string is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BattleConfigFile.decode("""{"shuffle_cards_wave":"3"}""")
        }
    }

    @Test
    fun `a stringAsInt pref given as a JSON number is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BattleConfigFile.decode("""{"autoskill_party":-1}""")
        }
    }

    @Test
    fun `a boolean pref given as a string is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BattleConfigFile.decode("""{"use_servant_priority":"true"}""")
        }
    }

    @Test
    fun `a string set pref given as a non-array or non-string array is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BattleConfigFile.decode("""{"battle_config_mat":"Proof"}""")
        }
        assertFailsWith<IllegalArgumentException> {
            BattleConfigFile.decode("""{"battle_config_mat":[1,2]}""")
        }
    }

    @Test
    fun `int pref accepts Long in Int range and rejects out-of-range Long or Double`() {
        val validated = BattleConfigFile.validate(mapOf("shuffle_cards_wave" to 3L))
        assertThat(validated["shuffle_cards_wave"]).isEqualTo(3)
        assertThat(validated["shuffle_cards_wave"]).isNotNull().isInstanceOf(Int::class)

        assertFailsWith<IllegalArgumentException> {
            BattleConfigFile.decode("""{"shuffle_cards_wave":3000000000}""")
        }
        assertFailsWith<IllegalArgumentException> {
            BattleConfigFile.decode("""{"shuffle_cards_wave":3.5}""")
        }
    }

    @Test
    fun `unknown keys keep existing decode behaviour alongside validated known keys`() {
        val decoded = BattleConfigFile.decode(
            """{"autoskill_name":"Farming","some_future_pref":42,"another_future_flag":true}"""
        )
        assertThat(decoded["autoskill_name"]).isEqualTo("Farming")
        assertThat(decoded["some_future_pref"]).isEqualTo(42)
        assertThat(decoded["another_future_flag"]).isEqualTo(true)
    }

    @Test
    fun `schema covers BattleConfigCore and nested preference groups`() {
        val types = BattleConfigCore.prefTypes
        assertThat(types["autoskill_name"]).isEqualTo(PrefType.String)
        assertThat(types["secondary_autoskill_cmd"]).isEqualTo(PrefType.String)
        assertThat(types["command_start_buster"]).isEqualTo(PrefType.Int)
        assertThat(types["support_append_skill_max_5"]).isEqualTo(PrefType.Boolean)
        assertThat(types["battle_config_mat"]).isEqualTo(PrefType.StringSet)
        assertThat(types[BattleConfigCore.STREAK_COUNT_KEY]).isEqualTo(PrefType.Int)
    }
}
