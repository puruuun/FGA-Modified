package io.github.fate_grand_automata.ui.battle_config_list

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.prefs.BattleConfigFile
import io.github.fate_grand_automata.prefs.PreferencesImpl
import io.github.fate_grand_automata.prefs.core.CompletedRunsHolder
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class BattleConfigListViewModelTest {
    private lateinit var context: Application
    private lateinit var prefsCore: PrefsCore
    private lateinit var realPrefs: PreferencesImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val sharedPrefs = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().commit()
        prefsCore = PrefsCore(PrefMaker(sharedPrefs), context)
        realPrefs = PreferencesImpl(prefsCore, CompletedRunsHolder())
    }

    private fun viewModel(prefs: IPreferences = realPrefs) =
        BattleConfigListViewModel(prefsCore = prefsCore, prefs = prefs)

    private fun registerImportJson(uri: Uri, content: String) {
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream(content.toByteArray())
        )
    }

    @Test
    fun `importAsync rejects invalid pref type and leaves no new config`() = runBlocking {
        val vm = viewModel()
        val uri = Uri.parse("content://test/invalid_type.fga")
        registerImportJson(uri, """{"autoskill_name":true}""")

        val result = vm.importAsync(listOf(uri), context)

        assertThat(result.failureCount).isEqualTo(1)
        assertThat(realPrefs.battleConfigs).isEmpty()
        assertThat(prefsCore.battleConfigList.get()).isEmpty()
    }

    @Test
    fun `importAsync rejects broken JSON and leaves no new config`() = runBlocking {
        val vm = viewModel()
        val uri = Uri.parse("content://test/broken.fga")
        registerImportJson(uri, "{not valid json")

        val result = vm.importAsync(listOf(uri), context)

        assertThat(result.failureCount).isEqualTo(1)
        assertThat(realPrefs.battleConfigs).isEmpty()
        assertThat(prefsCore.battleConfigList.get()).isEmpty()
    }

    @Test
    fun `importAsync rejects int pref given as string and leaves no new config`() = runBlocking {
        val vm = viewModel()
        val uri = Uri.parse("content://test/int_as_string.fga")
        registerImportJson(uri, """{"shuffle_cards_wave":"3"}""")

        val result = vm.importAsync(listOf(uri), context)

        assertThat(result.failureCount).isEqualTo(1)
        assertThat(realPrefs.battleConfigs).isEmpty()
        assertThat(prefsCore.battleConfigList.get()).isEmpty()
    }

    @Test
    fun `importAsync imports valid exported config and keeps unknown keys`() = runBlocking {
        val vm = viewModel()
        val uri = Uri.parse("content://test/valid.fga")
        val json = BattleConfigFile.encode(
            mapOf(
                "autoskill_name" to "Lotto",
                "shuffle_cards_wave" to 2,
                "use_servant_priority" to true,
                "battle_config_mat" to setOf("Proof", "Bone"),
                "some_future_pref" to "kept"
            )
        )
        registerImportJson(uri, json)

        val result = vm.importAsync(listOf(uri), context)

        assertThat(result.failureCount).isEqualTo(0)
        assertThat(realPrefs.battleConfigs.size).isEqualTo(1)
        val imported = realPrefs.battleConfigs.single()
        assertThat(imported.name).isEqualTo("Lotto")
        assertThat(imported.shuffleCardsWave).isEqualTo(2)
        assertThat(imported.useServantPriority).isEqualTo(true)
        assertThat(imported.export()["some_future_pref"]).isEqualTo("kept")
    }

    @Test
    fun `importAsync rolls back newly created config if import throws after newConfig`() = runBlocking {
        val removedIds = mutableListOf<String>()
        var addedId: String? = null
        val failingPrefs = object : IPreferences by realPrefs {
            override fun addBattleConfig(id: String): IBattleConfig {
                addedId = id
                val realConfig = realPrefs.addBattleConfig(id)
                return object : IBattleConfig by realConfig {
                    override fun import(map: Map<String, *>) {
                        throw IllegalStateException("simulated write failure")
                    }
                }
            }

            override fun removeBattleConfig(id: String) {
                removedIds.add(id)
                realPrefs.removeBattleConfig(id)
            }
        }
        val vm = viewModel(failingPrefs)
        val uri = Uri.parse("content://test/rollback.fga")
        registerImportJson(uri, """{"autoskill_name":"Valid"}""")

        val result = vm.importAsync(listOf(uri), context)

        assertThat(result.failureCount).isEqualTo(1)
        assertThat(removedIds).containsExactly(addedId!!)
        assertThat(realPrefs.battleConfigs).isEmpty()
        assertThat(prefsCore.battleConfigList.get()).isEmpty()
    }

    @Test
    fun `exportConfigs counts a failure when dir or createFile returns null`() {
        val vm = viewModel()
        val first = realPrefs.addBattleConfig("cfg-1").apply { name = "First" }
        val second = realPrefs.addBattleConfig("cfg-2").apply { name = "Second" }

        val result = vm.exportConfigs(
            configs = listOf(first, second),
            createFile = { null },
            openOutputStream = { ByteArrayOutputStream() }
        )

        assertThat(result.failureCount).isEqualTo(2)
    }

    @Test
    fun `exportConfigs counts a failure when openOutputStream returns null`() {
        val vm = viewModel()
        val first = realPrefs.addBattleConfig("cfg-1").apply { name = "First" }
        val second = realPrefs.addBattleConfig("cfg-2").apply { name = "Second" }
        val secondStream = ByteArrayOutputStream()

        val result = vm.exportConfigs(
            configs = listOf(first, second),
            createFile = { name -> Uri.parse("content://test/$name") },
            openOutputStream = { uri ->
                if (uri.lastPathSegment == "First.fga") null else secondStream
            }
        )

        assertThat(result.failureCount).isEqualTo(1)
        assertThat(BattleConfigFile.decode(secondStream.toString())["autoskill_name"]).isEqualTo("Second")
    }
}
