package io.github.fate_grand_automata.qa.suites

import io.github.fate_grand_automata.qa.FixtureApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.*
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.weekly.*
import org.json.*

internal fun FixtureApi.verifyWeeklyNavigation(): JSONObject {
    check(!live) { "navigation QA is offline only" }
    val loc=WeeklyRouteLocations(locations)
    val actual=JSONObject()
    when(case.getString("view")) {
        "scrollbar" -> {
            val state = ListScrollbar.read(this, loc)
            actual.put("top", state.top).put("bottom", state.bottom)
                .put("scores", state.toString())
        }
        "spotPanel" -> useSameSnapIn {
            val reader = QuestPanelReader(this, QuestPanelLocations(locations), ocr)
            val names = JSONArray(fixtureText(imports.getJSONArray("names:JP").getString(0))).let { a -> (0 until a.length()).map { a.getString(it) } }
            val warSpots = case.getJSONArray("spotNames").let { a -> (0 until a.length()).map { a.getString(it) } }
            val spot = reader.readSpot(prefs.gameServer, warSpots)
            // D-37: the tapped spot's own free quests; absent in the older cases, which keep the chapter-wide vote.
            val spotQuests = case.optJSONArray("spotQuests")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val only = if (case.has("anchorIndex")) case.getInt("anchorIndex") else null
            val panels = JSONArray()
            var by: String? = null
            for ((index, anchor) in reader.anchors().withIndex()) {
                if (only != null && index != only) continue
                var panel = reader.readAnchored(prefs.gameServer, anchor, names, spot, spotQuests)
                if (case.optBoolean("forceNullName")) panel = panel.copy(nameText = null)
                var found = QuestPanelMatch.matchBy(panel, case.getString("targetName"), case.getString("targetSpot"), case.getInt("freeQuests"))
                if (found == null && case.has("questId")) {
                    // D-42 (#130): the name-band template, only for a case that names its quest id.
                    val questId = case.getInt("questId")
                    val score = reader.nameImageScore(prefs.gameServer, anchor, questId)
                    found = QuestNameTemplates.matchBy(panel, score, QuestNameTemplates.entry(prefs.gameServer, questId), case.optInt("targetAp", 21))
                    actual.put("nameImageScore", score ?: -1.0)
                }
                if (found != null && by == null) by = found
                panels.put(JSONObject().put("name", panel.nameText ?: "unread").put("spot", panel.spotText ?: "unread")
                    .put("cells", panel.nameCells ?: -1).put("by", found ?: "mismatch")
                    .put("rawNames", JSONArray(panel.rawNames.map { "${it.text.trim()}@${it.confidence}" }))
                    .put("rawSpots", JSONArray(panel.rawSpots.map { "${it.text.trim()}@${it.confidence}" })))
            }
            actual.put("match", by != null).put("by", by ?: "mismatch").put("found", panels.length() > 0).put("panels", panels)
        }
        "evidence" -> useColor {
            // D-30: the picture the probe saves for a success: panel name band above the spot ribbon, doubled.
            val reader = QuestPanelReader(this, QuestPanelLocations(locations), ocr)
            val anchor = reader.anchors().firstOrNull()
            val image = anchor?.let { reader.evidence(it) }
            actual.put("found", anchor != null).put("made", image != null)
            image?.use { actual.put("width", it.width).put("height", it.height).put("color", it.grayPixels() == null) }
        }
        "spotLegacy" -> useSameSnapIn {
            // Root-cause probe for the beta.8 null names: the beta.8 crop (ink width + 8 from the band's left edge) and its two reads.
            val reader = QuestPanelReader(this, QuestPanelLocations(locations), ocr)
            val names = JSONArray(fixtureText(imports.getJSONArray("names:JP").getString(0))).let { a -> (0 until a.length()).map { a.getString(it) } }
            val rows = JSONArray()
            for (anchor in reader.anchors()) anchor.name.getPattern("legacy name").use { band ->
                val width = band.grayPixels()?.let { QuestPanelMatch.inkExtent(it, band.width, band.height) }
                val old = band.crop(io.github.lib_automata.Region(0, 0, minOf((width ?: band.width) + 8, band.width), band.height)).use { image ->
                    listOf(2, 3).map { scale -> ocr.detectTextLine(image, io.github.lib_automata.TextLineOcrOptions(scale = scale, threshold = 0.6,
                        invert = true, padding = 12, language = "jpn_weekly")) }
                }
                rows.put(JSONObject().put("name", QuestPanelMatch.canonicalName(old, names) ?: "unread").put("width", width ?: -1)
                    .put("raw", JSONArray(old.map { "${it.text.trim()}@${it.confidence}" })))
            }
            actual.put("legacy", rows).put("found", rows.length() > 0)
        }
        "edgeSpot" -> {
            val width = loc.scriptArea.width / 2
            val height = loc.scriptArea.height / 2
            val drag = MapView.edgeDrag(case.getDouble("spotX"), case.getDouble("spotY"), width, height)
            actual.put("needsScroll", drag != null).put("dragY", drag?.y ?: 0.0)
                .put("screen", WeeklyRouteScreens(this, loc).read().name)
        }
        "spotTappable" -> {
            val x = case.getDouble("spotX")
            val y = case.getDouble("spotY")
            val width = loc.scriptArea.width / 2
            val height = loc.scriptArea.height / 2
            actual.put("tappable", MapView.isTappable(x, y, width, height))
                .put("needsScroll", MapView.dragToward(x, y, width, height) != null)
                .put("fallback", MapView.visibleTap(x, y, width, height) != null)
                .put("screen", WeeklyRouteScreens(this, loc).read().name)
        }
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
        "repeatExit" -> {
            actual.put("visible", WeeklyRepeatDialog(this, loc).visible())
                .put("closeX", loc.repeatDialogClose.x / 2)
                .put("closeY", loc.repeatDialogClose.y / 2)
        }
        "button" -> useColor {
            val image=Images.valueOf(case.getString("image"))
            val score=loc.listArea.findAll(images[image],0.1).maxOfOrNull { it.score } ?: 0.0
            actual.put("match",score>=WeeklyRouteDriver.BUTTON_SIMILARITY).put("score",score)
        }
        "bannerRaw" -> useColor {
            // D-35: the Atlas banner as the app prepares it (AtlasAssets.banner: body crop, 1.25x), against a list frame.
            fixturePattern(imports.getJSONArray(case.getString("asset")).getString(0),true).use { full ->
                full.crop(AtlasAssets.BANNER_BODY).use { body ->
                    body.resize(io.github.lib_automata.Size((body.width * AtlasAssets.BANNER_SCALE).toInt(), (body.height * AtlasAssets.BANNER_SCALE).toInt())).use { pattern ->
                        val best = loc.listArea.findAll(pattern,0.1).maxByOrNull { it.score }
                        actual.put("match",(best?.score ?: 0.0)>=WeeklyRouteDriver.BANNER_SIMILARITY).put("score",best?.score ?: 0.0)
                            .put("x",best?.region?.center?.x ?: -1).put("y",best?.region?.center?.y ?: -1)
                    }
                }
            }
        }
        "banner" -> useColor {
            fixturePattern(imports.getJSONArray(case.getString("asset")).getString(0),true).use { pattern ->
                val score=loc.listArea.findAll(pattern,0.1).maxOfOrNull { it.score } ?: 0.0
                actual.put("match",score>=WeeklyRouteDriver.BANNER_SIMILARITY).put("score",score)
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
        "anchoredPanel" -> useSameSnapIn {
            val reader = QuestPanelReader(this, QuestPanelLocations(locations), ocr)
            val region = if (prefs.gameServer is io.github.fate_grand_automata.scripts.enums.GameServer.Kr) "KR" else "JP"
            val lexicon = JSONArray(fixtureText(imports.getJSONArray("names:$region").getString(0)))
            val names = (0 until lexicon.length()).map { lexicon.getString(it) }
            val anchor = reader.anchors().getOrNull(case.optInt("anchorIndex", 0))
            actual.put("found", anchor != null)
            if (anchor != null) {
                val panel = reader.readAnchored(prefs.gameServer, anchor, names)
                val verdict = QuestPanelMatch.strictVerdict(panel, case.getInt("targetAp"), case.getString("targetName"), prefs.gameServer)
                actual.put("match", verdict == PanelVerdict.Match).put("ap", panel.ap ?: "unread")
                    .put("name", panel.nameText ?: "unread").put("anchorX", anchor.mark.x / 2).put("anchorY", anchor.mark.y / 2)
            }
        }
        else -> error("unknown weekly navigation QA view")
    }
    val expected=case.getJSONObject("expected")
    for(key in expected.keys()) check(actual.get(key).toString()==expected.get(key).toString()) { "weekly navigation $key actual=$actual expected=$expected" }
    return actual
}
