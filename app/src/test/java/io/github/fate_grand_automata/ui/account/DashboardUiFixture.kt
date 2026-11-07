package io.github.fate_grand_automata.ui.account

import android.content.Context
import androidx.compose.runtime.*
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.log.*
import io.github.fate_grand_automata.ui.more.*
import io.github.lib_automata.Size
import io.github.fate_grand_automata.util.DisplayHelper
import io.github.fate_grand_automata.util.CutoutManager
import io.github.fate_grand_automata.runner.GameAreaCaptureSignal
import java.io.File

internal class DashboardUiFixture {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = PrefsCore(PrefMaker(context.getSharedPreferences("round4", Context.MODE_PRIVATE)), context)
    private val display = DisplayHelper(context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
    val preview = GameAreaPreviewManager(CutoutManager(display,prefs),prefs,display,GameAreaCaptureSignal())
    val names = localIconNames(context).let { base ->
        val faces = (1L..35).associateWith { id ->
            val file = File(context.filesDir,"capture/icons/servants/$id.png").apply { parentFile!!.mkdirs() }
            File(context.filesDir,"capture/icons/6510.png").copyTo(file, overwrite = true)
            AtlasBasic(id, "샘플 서번트 $id", "normal", if(id%2==0L) "saber" else "caster", 5, id,
                "https://static.atlasacademy.io/test-only/servant/$id.png")
        }
        base.copy(servantData = faces, equipData = mapOf(9400010L to AtlasBasic(9400010,"샘플 예장","servantEquip")),
            itemData = mapOf(6510L to AtlasBasic(6510,"무간의 톱니바퀴","skillLvUp"),6001L to AtlasBasic(6001,"검의 휘석","skillLvUp")))
    }
    val state = dashboardState(
        "userGame" to """[{"name":"샘플 마스터","lv":160,"exp":1234567,"friendCode":"123456789","costMax":114,"friendKeep":100,
            "actMax":144,"actRecoverAt":1999999999,"carryOverActPoint":12,"stone":123,"freeStone":100,"chargeStone":23,
            "qp":123456789,"mana":500,"rarePri":20,"svtKeep":900,"svtEquipKeep":700,"commandSpellRecoverAt":1999999999,
            "birthday":"PRIVATE-BIRTHDAY","message":"PRIVATE-MESSAGE"}]""",
        "tblUserGame" to """[{"createdAt":1700000000,"friendPoint":123456}]""",
        "userItem" to """[{"itemId":16,"num":6},{"itemId":100,"num":50},{"itemId":101,"num":23},{"itemId":104,"num":10},
            {"itemId":102,"num":9},{"itemId":103,"num":8},{"itemId":6510,"num":60},{"itemId":6001,"num":40},{"itemId":999,"num":1}]""",
        "userSvt" to """[{"svtId":1},{"svtId":2},{"svtId":9400010},{"svtId":999}]""",
        "userSvtCollection" to (1..35).joinToString(",", "[", "]") {
            """{"svtId":$it,"status":2,"friendshipRank":${if(it==1) 15 else 10},"friendship":${10000-it},"treasureDeviceLv1":5,"skillLv1":10,"skillLv2":10,"skillLv3":9,"maxLv":120}"""
        })
    val data = dashboardData(state, names)
    @Composable fun Icons(content: @Composable ()->Unit) {
        val loader = remember { localIconLoader(context) }
        CompositionLocalProvider(LocalLogIconLoader provides loader, content = content)
    }
    @Composable fun More(weekly: ()->Unit = {}, gains: ()->Unit = {}, extract: ()->Unit = {}, download: ()->Unit = {},
        extractSummary: String = "", downloadSummary: String = "", captureOn: Boolean = true) {
        prefs.captureTrackerEnabled.set(captureOn)
        MoreOptionsContent(prefs, "FGA", extractSummary, extract, { additionalFeaturesGroup(prefs,preview) }, {}, {}, {}, weekly, gains, {},
            downloadSummary = downloadSummary, downloadIcons = download,
            additionalTools = { AdditionalToolsContent(listOf(CaptureAccount("fake","샘플 마스터",io.github.fate_grand_automata.scripts.capture.AccountSummary.from(state,100),200)), {}, {}, {}, captureOn) })
    }
}
