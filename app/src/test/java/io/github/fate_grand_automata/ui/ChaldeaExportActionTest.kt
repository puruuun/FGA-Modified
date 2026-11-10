package io.github.fate_grand_automata.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.ChaldeaExportStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/** Only a local fake launch activity is registered; no installed app is opened. */
internal fun installFakeChaldea(context: Context, pkg: String = "cc.narumi.chaldea") {
    val manager = shadowOf(context.packageManager)
    manager.addActivityIfNotPresent(ComponentName(pkg,"$pkg.MainActivity"))
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
    manager.addResolveInfoForIntent(intent, ResolveInfo().apply {
        activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.MainActivity"; exported = true }
    })
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class ChaldeaExportActionTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var exported: String? = null
    private fun flow(result: ChaldeaExportStore.Result) {
        compose.setContent { FGATheme {
            val export = rememberChaldeaAccountExportAction { id -> exported = id; result }
            TextButton(onClick = { export("chosen-account") }) { Text("Export") }
        } }
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText(context.getString(R.string.capture_chaldea_create)).assertExists()
        assertEquals("chosen-account",exported)
    }
    @Test fun successWaitsForUserWithoutLaunchingOrToasting() {
        installFakeChaldea(context)
        ShadowToast.reset()
        flow(ChaldeaExportStore.Result.Written("sample.json", "FGA/capture"))
        compose.onNodeWithText("저장했습니다. Chaldea에서 이 파일을 선택하세요",substring=true).assertExists()
        compose.onNodeWithText("sample.json",substring=true).assertExists()
        compose.onNodeWithText("FGA/capture/sample.json",substring=true).assertExists()
        compose.onNodeWithText("HTTPS 스니핑",substring=true).assertExists()
        compose.onNodeWithText("Chaldea로 이동").assertExists()
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
        assertEquals(0,ShadowToast.shownToastCount())
        compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        compose.onNodeWithText("Chaldea로 이동").assertDoesNotExist()
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
    }
    private fun launches(pkg: String) {
        installFakeChaldea(context,pkg)
        flow(ChaldeaExportStore.Result.Written("sample.json"))
        compose.onNodeWithText("Chaldea로 이동").performClick()
        assertEquals(pkg,shadowOf(context as android.app.Application).nextStartedActivity.component!!.packageName)
        compose.onNodeWithText(context.getString(R.string.capture_chaldea_create)).assertDoesNotExist()
    }
    @Test fun openButtonLaunchesChaldeaThenCloses() = launches("cc.narumi.chaldea")
    @Test fun openButtonSupportsFdroid() = launches("cc.narumi.chaldea.fdroid")
    @Test fun noInstalledChaldeaHidesOpenButtonAndShowsNote() {
        flow(ChaldeaExportStore.Result.Written("sample.json", "FGA/capture"))
        compose.onNodeWithText("Chaldea로 이동").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.capture_chaldea_not_installed,"FGA/capture/sample.json"),substring=true).assertExists()
        compose.onNodeWithText("저장했습니다.",substring=true).assertExists()
        compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        compose.onNodeWithText("sample.json",substring=true).assertDoesNotExist()
    }
    private fun error(result: ChaldeaExportStore.Result, text: Int) {
        installFakeChaldea(context)
        flow(result)
        compose.onNodeWithText(context.getString(text)).assertExists()
        compose.onNodeWithText("Chaldea로 이동").assertDoesNotExist()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(isDialog())).assertCountEquals(1)
        compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        compose.onNodeWithText(context.getString(text)).assertDoesNotExist()
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
    }
    @Test fun noSnapshotKeepsSingleButtonDialog() = error(ChaldeaExportStore.Result.NoSnapshot,R.string.capture_no_snapshot)
    @Test fun failedKeepsSingleButtonDialog() = error(ChaldeaExportStore.Result.Failed,R.string.capture_chaldea_failed)
    @Test @Config(sdk = [28]) fun exportGuardStillRejectsBeforeAndroid10() {
        compose.setContent { FGATheme {
            val export = rememberChaldeaAccountExportAction { exported = it; ChaldeaExportStore.Result.Failed }
            TextButton(onClick = { export("chosen-account") }) { Text("Export") }
        } }
        compose.onNodeWithText("Export").performClick()
        assertNull(exported)
        compose.onNodeWithText(context.getString(R.string.capture_chaldea_create)).assertDoesNotExist()
    }
}
