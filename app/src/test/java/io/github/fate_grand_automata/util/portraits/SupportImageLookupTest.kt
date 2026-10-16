package io.github.fate_grand_automata.util.portraits

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SupportImageLookupTest {
    private fun record(id: Int = 100600, number: Int = 90, name: String = "ネロ・クラウディウス〔ブライド〕", clazz: String = "saber") =
        SupportCatalogRecord(id, number, name, name, clazz, 5, "https://static.atlasacademy.io/JP/Faces/f_1006000.png")
    private val jp get() = record()
    private val kr get() = record(number = 900, name = "네로 클라우디우스(브라이드)")
    private fun entry(kind: SupportCatalogKind = SupportCatalogKind.Servant) =
        SupportCatalogEntry(kind, if (kind == SupportCatalogKind.CE) 9400340 else 100600,
            if (kind == SupportCatalogKind.CE) 34 else 90, "이름", "saber", 5, null, setOf("이름"))
    private fun detail(region: String = "JP", kind: SupportCatalogKind = SupportCatalogKind.Servant,
                       id: Int = if (kind == SupportCatalogKind.CE) 9400340 else 100600) =
        """{"id":$id,"type":"${if (kind == SupportCatalogKind.CE) "servantEquip" else "normal"}","extraAssets":{
        "${if (kind == SupportCatalogKind.CE) "equipFace" else "faces"}":{"ascension":{"1":"https://static.atlasacademy.io/$region/${if (kind == SupportCatalogKind.CE) "EquipFaces" else "Faces"}/f_1006000.png"}}}}"""

    private class Source : SupportCatalogSource {
        val calls = mutableListOf<String>()
        val catalogs = mutableMapOf<String, List<SupportCatalogRecord>>()
        val details = mutableMapOf<String, String?>()
        override suspend fun catalog(kind: SupportCatalogKind, region: String): List<SupportCatalogRecord> {
            calls += "catalog:$region"; return catalogs[region].orEmpty()
        }
        override suspend fun detail(kind: SupportCatalogKind, region: String, id: Int): String? {
            calls += "detail:$region:$id"; return details[region]
        }
    }

    @Test fun `effective app languages select regional names with Japanese fallback`() {
        val cases = mapOf("ko-KR" to "KR", "ja-JP" to "JP", "en-GB" to "NA", "zh-Hans" to "CN",
            "zh-Hant" to "TW", "zh-TW" to "TW", "zh-HK" to "TW", "fr-FR" to "JP", "" to "JP")
        cases.forEach { (tag, region) -> assertEquals(region, SupportImageCatalog.languageRegion(tag)) }
    }
    @Test fun `localized names join by internal id and keep the JP collection number`() {
        val result = SupportImageCatalog.merge(SupportCatalogKind.Servant, listOf(jp), listOf(kr), emptyList()).single()
        assertEquals(100600, result.id); assertEquals(90, result.number)
        assertEquals("No.90 네로 클라우디우스(브라이드)", result.folderName)
        assertEquals(result, SupportImageCatalog.search(listOf(result), "네로").single())
        assertEquals(result, SupportImageCatalog.search(listOf(result), "ネロ").single())
    }
    @Test fun `an unreleased item keeps its Japanese name even if regional numbers collide`() {
        val olga = record(4000100, 444, "Ｕ－オルガマリー")
        val unrelated = record(999999, 444, "다른 서번트")
        val result = SupportImageCatalog.merge(SupportCatalogKind.Servant, listOf(olga), listOf(unrelated), emptyList()).single()
        assertEquals("Ｕ－オルガマリー", result.name)
        assertTrue(SupportImageCatalog.search(listOf(result), "다른").isEmpty())
        assertTrue(SupportImageCatalog.search(listOf(result), "u 올가").isEmpty()) // No machine-translated aliases.
        assertEquals(result, SupportImageCatalog.search(listOf(result), "U オルガマリー").single())
    }
    @Test fun `same names with different classes remain separate choices`() {
        val variants = listOf(record(301300,70,"스카사하","lancer"), record(602400,133,"스카사하","assassin"))
        val results = SupportImageCatalog.search(SupportImageCatalog.merge(SupportCatalogKind.Servant, variants, variants, variants), "스카사하")
        assertEquals(listOf(70,133), results.map { it.number }); assertEquals(2, results.map { it.className }.toSet().size)
    }
    @Test fun `number lookup is exact and unicode names ignore case spacing and punctuation`() {
        val all = SupportImageCatalog.merge(SupportCatalogKind.Servant, listOf(jp,record(2,900,"Other")), emptyList(), emptyList())
        assertEquals(90, SupportImageCatalog.search(all,"９０").single().number)
        assertEquals(90, SupportImageCatalog.search(all,"ネロ クラウディウス ブライド").single().number)
        assertTrue(SupportImageCatalog.search(all,"9").isEmpty())
        for (bad in listOf("", "   ", "///", "0", "10000", "99999999999999")) assertFailsWith<PortraitImportException> { SupportImageCatalog.search(all,bad) }
    }
    @Test fun `search does not silently truncate large result sets`() {
        val records = (1..201).map { record(it,it,"Test $it") }
        val entries = SupportImageCatalog.merge(SupportCatalogKind.CE,records,emptyList(),emptyList())
        assertEquals(201, SupportImageCatalog.search(entries,"Test").size)
    }
    @Test fun `catalog excludes enemies exp cards zero collection numbers and unnamed records`() {
        val text = """[{"id":1,"collectionNo":1,"name":"Good","type":"normal"},
        {"id":2,"collectionNo":2,"name":"Enemy","type":"enemyCollectionDetail"},
        {"id":3,"collectionNo":0,"name":"Hidden","type":"normal"},
        {"id":4,"collectionNo":4,"name":"","type":"normal"}]"""
        assertEquals(listOf(1),SupportImageCatalog.parse(text,SupportCatalogKind.Servant).map { it.id })
        assertEquals(emptyList(),SupportImageCatalog.parse(text,SupportCatalogKind.CE))
    }
    @Test fun `CE catalog searches both translated and Japanese names without changing identity`() {
        val jpCe=record(9400340,34,"カレイドスコープ"); val krCe=record(9400340,34,"컬라이더스코프")
        val all=SupportImageCatalog.merge(SupportCatalogKind.CE,listOf(jpCe),listOf(krCe),emptyList())
        for (query in listOf("34","컬라이더","カレイド")) {
            val hit=SupportImageCatalog.search(all,query).single();assertEquals(9400340,hit.id);assertEquals("No.34 컬라이더스코프",hit.folderName)
        }
    }
    @Test fun `lookup uses only necessary catalogs and invalid queries do no network work`() = runBlocking {
        val source=Source();source.catalogs["JP"]=listOf(jp);source.catalogs["KR"]=listOf(kr)
        val lookup=SupportImageLookup(source)
        assertEquals("네로 클라우디우스(브라이드)",lookup.search(SupportCatalogKind.Servant,"90","ko-KR","KR").single().name)
        assertEquals(listOf("catalog:JP","catalog:KR"),source.calls)
        source.calls.clear()
        assertFailsWith<PortraitImportException> { lookup.search(SupportCatalogKind.CE,"0","ko","KR") }
        assertTrue(source.calls.isEmpty())
    }
    @Test fun `game region art wins while JP-only costumes are retained`() = runBlocking {
        val source=Source();source.details["JP"]=detail().replace("}}}}", "},\"2\":{\"2\":\"https://static.atlasacademy.io/JP/Faces/f_1006002.png\"}}}}")
        source.details["KR"]=detail("KR")
        val pictures=SupportImageLookup(source).portraits(entry(),"KR")
        assertEquals(2,pictures.size)
        assertTrue(pictures.first().url.contains("/KR/"));assertTrue(pictures.last().url.contains("/JP/"))
        assertEquals(listOf("detail:JP:100600","detail:KR:100600"),source.calls)
    }
    @Test fun `missing regional entry falls back to JP without losing costumes`() = runBlocking {
        val source=Source();source.details["JP"]=detail()
        assertTrue(SupportImageLookup(source).portraits(entry(),"KR").single().url.contains("/JP/"))
    }
    @Test fun `JP lookup does not fetch its detail twice`() = runBlocking {
        val source=Source();source.details["JP"]=detail()
        SupportImageLookup(source).portraits(entry(),"JP")
        assertEquals(listOf("detail:JP:100600"),source.calls)
    }
    @Test fun `wrong identity and wrong image families stop before storage`() {
        assertFailsWith<PortraitImportException> { SupportAssetMetadata.parse(detail(id=999),entry(),"JP") }
        assertFailsWith<PortraitImportException> { SupportAssetMetadata.parse(detail("KR"),entry(),"JP") }
        assertFailsWith<PortraitImportException> { SupportAssetMetadata.parse(detail().replace("Faces/","CharaGraph/"),entry(),"JP") }
        val ce=entry(SupportCatalogKind.CE)
        assertEquals(1,SupportAssetMetadata.parse(detail(kind=SupportCatalogKind.CE),ce,"JP").size)
        assertFailsWith<PortraitImportException> { SupportAssetMetadata.parse(detail(kind=SupportCatalogKind.CE).replace("EquipFaces/","Faces/"),ce,"JP") }
    }
    @Test fun `unsupported or empty asset data never generates a speculative crop`() = runBlocking {
        val source=Source();source.details["JP"]="""{"id":100600,"type":"normal","extraAssets":{}}"""
        assertEquals(PortraitImportError.Image, assertFailsWith<PortraitImportException> { SupportImageLookup(source).portraits(entry(),"JP") }.reason)
        source.details.clear()
        assertEquals(PortraitImportError.NotFound,assertFailsWith<PortraitImportException> { SupportImageLookup(source).portraits(entry(),"KR") }.reason)
    }
    @Test fun `generated CE folders and ordinary filenames display without truncating No dot`() {
        assertEquals("No.34 컬라이더스코프",ServantPortraitFolder.displayName("No.34 컬라이더스코프"))
        assertEquals("Kaleidoscope",ServantPortraitFolder.displayName("Kaleidoscope.png"))
        assertEquals("No.34 Test",ServantPortraitFolder.displayName("No.34 Test.PNG"))
        assertEquals(setOf("No.34 새 이름","Black Grail.png"),ServantPortraitFolder.renameSelections(setOf("No.34 Old","Black Grail.png"),34,"No.34 새 이름"))
        assertEquals("No.34 새 이름",ServantPortraitFolder.resolve("No.34 Old",listOf("No.34 새 이름")))
    }
}
