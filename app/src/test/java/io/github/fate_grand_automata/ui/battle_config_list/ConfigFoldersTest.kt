package io.github.fate_grand_automata.ui.battle_config_list

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test

class ConfigFoldersTest {
    private fun item(id: String, name: String, folder: String = "") = FolderItem(id, name, folder)

    private fun List<ConfigEntry>.describe() = map {
        when (it) {
            is ConfigEntry.Folder -> "F:${it.displayName}[" + it.items.joinToString(",") { i -> i.id } + "]"
            is ConfigEntry.Unfiled -> "U:${it.item.id}"
        }
    }

    @Test
    fun `empty input gives no entries`() {
        assertThat(ConfigFolders.group(emptyList())).isEmpty()
    }

    @Test
    fun `folders come first then unfiled configs, each sorted by name`() {
        val result = ConfigFolders.group(
            listOf(
                item("1", "zeta"),
                item("2", "Beta", "Zebra"),
                item("3", "alpha"),
                item("4", "Alpha", "apple"),
                item("5", "gamma", "apple")
            )
        )
        assertThat(result.describe()).isEqualTo(listOf("F:apple[4,5]", "F:Zebra[2]", "U:3", "U:1"))
    }

    @Test
    fun `folder names merge case-insensitively and show the first config's spelling`() {
        val result = ConfigFolders.group(
            listOf(
                item("1", "b", " Lancer "),
                item("2", "a", "LANCER"),
                item("3", "c", "lancer")
            )
        )
        assertThat(result.describe()).isEqualTo(listOf("F:LANCER[2,1,3]"))
        assertThat((result.single() as ConfigEntry.Folder).key).isEqualTo("lancer")
    }

    @Test
    fun `a whitespace-only folder counts as unfiled`() {
        assertThat(ConfigFolders.group(listOf(item("1", "a", "   "))).describe()).isEqualTo(listOf("U:1"))
    }

    @Test
    fun `blank names are invalid`() {
        assertThat(ConfigFolders.normalizeName("   ", emptyList())).isEqualTo(FolderNameResult.Blank)
        assertThat(ConfigFolders.normalizeName("", listOf("A"))).isEqualTo(FolderNameResult.Blank)
    }

    @Test
    fun `name is trimmed and 30 characters are accepted`() {
        val thirty = "x".repeat(30)
        assertThat(ConfigFolders.normalizeName("  $thirty ", emptyList())).isEqualTo(FolderNameResult.Valid(thirty))
        assertThat(ConfigFolders.normalizeName(" Saber ", emptyList())).isEqualTo(FolderNameResult.Valid("Saber"))
    }

    @Test
    fun `31 characters are rejected`() {
        assertThat(ConfigFolders.normalizeName("x".repeat(31), emptyList())).isEqualTo(FolderNameResult.TooLong)
    }

    @Test
    fun `an existing folder is reused with its own spelling`() {
        assertThat(ConfigFolders.normalizeName("lancer", listOf("Archer", "LaNcEr")))
            .isEqualTo(FolderNameResult.Valid("LaNcEr"))
    }
}
