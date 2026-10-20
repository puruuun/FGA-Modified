package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.*
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.weekly.*
import org.json.*
import kotlin.math.hypot

internal fun FixtureApi.verifyWeeklyNavigation(): JSONObject {
    check(!live) { "navigation QA is offline only" }
    val loc=WeeklyRouteLocations(locations)
    val actual=JSONObject()
    when(case.getString("view")) {
        "nameProbe" -> {
            val reader=QuestPanelReader(this,QuestPanelLocations(locations),ocr)
            val locName=QuestPanelLocations(locations)
            val slot=case.optInt("slot",0)
            val lang=if(prefs.gameServer is io.github.fate_grand_automata.scripts.enums.GameServer.Kr) "kor_weekly" else "jpn_weekly"
            val rows=JSONArray()
            locName.slot(locName.name,slot).getPattern("name profile probe").use { band ->
                val w=(reader.nameWidthPx(slot) ?: band.width)+8
                band.crop(io.github.lib_automata.Region(0,0,minOf(w,band.width),band.height)).use { tight ->
                    for(scale in listOf(1,2,3)) for(stretch in listOf(0.8,1.0,1.2)) for(th in listOf(0.3,0.4,0.5,0.6,0.7,0.8,-1.0)) {
                        val o=io.github.lib_automata.TextLineOcrOptions(scale=scale,threshold=maxOf(0.0,th),invert=true,padding=12,horizontalScale=stretch,language=lang,rawGrayscale=th<0)
                        val read=ocr.detectTextLine(tight,o)
                        rows.put(JSONObject().put("scale",scale).put("stretch",stretch).put("threshold",th).put("text",read.text).put("confidence",read.confidence))
                    }
                }
            }
            actual.put("readings",rows)
        }
        "pinch" -> {
            val plan=io.github.lib_automata.PinchGesture.zoomOut(io.github.lib_automata.Region(200,150,880,400))
            val gesture=io.github.fate_grand_automata.accessibility.PinchGestureBuilder.build(plan)
            actual.put("strokes",gesture.strokeCount).put("simultaneous",gesture.getStroke(0).startTime==0L && gesture.getStroke(1).startTime==0L)
                .put("duration",gesture.getStroke(0).duration).put("converges",plan.endSpan<plan.startSpan)
        }
        "screen" -> actual.put("screen",WeeklyRouteScreens(this,loc).read().name)
        "button" -> useColor {
            val image=Images.valueOf(case.getString("image"))
            val score=loc.listArea.findAll(images[image],0.1).maxOfOrNull { it.score } ?: 0.0
            actual.put("match",score>=WeeklyRouteDriver.BUTTON_SIMILARITY).put("score",score)
        }
        "banner" -> useColor {
            fixturePattern(imports.getJSONArray(case.getString("asset")).getString(0),true).use { pattern ->
                val score=loc.listArea.findAll(pattern,0.1).maxOfOrNull { it.score } ?: 0.0
                actual.put("match",score>=WeeklyRouteDriver.BANNER_SIMILARITY).put("score",score)
            }
        }
        "map" -> {
            fixturePattern(imports.getJSONArray(case.getString("asset")).getString(0)).use { map ->
                loc.scriptArea.getPattern("weekly localization QA").use { screen ->
                    val fit=MapLocalizer().localize(screen,map)
                    actual.put("accepted",MapLocalizer.accepts(fit))
                    if(fit!=null) {
                        actual.put("scaleX",fit.scaleX).put("scaleY",fit.scaleY).put("originX",fit.originX).put("originY",fit.originY)
                            .put("inliers",fit.inliers).put("ratio",fit.score).put("rms",fit.rms)
                        val positions=JSONArray()
                        val anchors=case.optJSONArray("anchors") ?: JSONArray()
                        for(i in 0 until anchors.length()) {
                            val e=anchors.getJSONObject(i);val x=fit.screenX(e.getDouble("mapX"));val y=fit.screenY(e.getDouble("mapY"))
                            val error=hypot(x-e.getDouble("screenX"),y-e.getDouble("screenY"))
                            check(error<=e.getDouble("tolerance")) { "spot ${e.getInt("id")} error=$error expected=$e fit=$fit" }
                            positions.put(JSONObject().put("id",e.getInt("id")).put("x",x).put("y",y).put("error",error))
                        }
                        actual.put("positions",positions)
                    }
                }
            }
        }
        "strictPanel" -> {
            val reader=QuestPanelReader(this,QuestPanelLocations(locations),ocr)
            val slot=case.optInt("slot",0)
            val region=if(prefs.gameServer is io.github.fate_grand_automata.scripts.enums.GameServer.Kr) "KR" else "JP"
            val lexicon=JSONArray(fixtureText(imports.getJSONArray("names:$region").getString(0)))
            val names=(0 until lexicon.length()).map { lexicon.getString(it) }
            val panel=reader.read(prefs.gameServer,slot,names)
            val verdict=QuestPanelMatch.strictVerdict(panel,case.getInt("targetAp"),case.getString("targetName"),prefs.gameServer)
            actual.put("match",verdict==PanelVerdict.Match).put("free",panel.free).put("ap",panel.ap ?: "unread")
                .put("name",panel.nameText ?: "unread").put("rawNames",JSONArray(reader.nameReadings(prefs.gameServer,slot).map { "${it.text}@${it.confidence}" }))
        }
        else -> error("unknown weekly navigation QA view")
    }
    val expected=case.getJSONObject("expected")
    for(key in expected.keys()) check(actual.get(key).toString()==expected.get(key).toString()) { "weekly navigation $key actual=$actual expected=$expected" }
    return actual
}
