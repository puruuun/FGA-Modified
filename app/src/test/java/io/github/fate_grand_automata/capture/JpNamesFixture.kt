package io.github.fate_grand_automata.capture

/** Handwritten public-list and account fixtures, shared with the dashboard screenshot test. */
internal object JpNamesFixture {
    fun icon(server: String, id: Long) = "https://static.atlasacademy.io/$server/test-only/$id.png"

    private fun row(server: String, id: Long, name: String, type: String, image: String = "face") =
        """{"id":$id,"name":"$name","type":"$type","className":"saber","rarity":5,"collectionNo":$id,"$image":"${icon(server, id)}","private":"discarded"}"""

    fun download(url: String): String {
        if (url == "https://api.atlasacademy.io/nice/JP/item/300") {
            return """{"name":"個別アイテム","icon":"${icon("JP", 300)}"}"""
        }
        val server = url.substringAfter("/export/").substringBefore('/')
        check(server in setOf("KR", "JP")) { "Unexpected public URL" }
        val jp = server == "JP"
        val rows = when (url.substringAfterLast('/')) {
            "nice_item.json" -> listOf(100L, 101L, 102L, 104L).map {
                row(server, it, if (jp) "日本の果実" else "한국 과일", "apRecover", "icon")
            } + if (jp) listOf(row(server, 200, "日本の素材", "skillLvUp", "icon")) else emptyList()
            "basic_servant.json", "basic_svt.json" -> listOf(row(server, 10, if (jp) "日本の既知騎士" else "한국 기사", "normal")) +
                if (jp) listOf(row(server, 20, "日本の騎士", "normal"), row(server, 21, "敵", "enemy"),
                    row(server, 22, "日本のヒロイン", "heroine")) else emptyList()
            "basic_equip.json" -> listOf(row(server, 30, if (jp) "日本の既知礼装" else "한국 예장", "servantEquip")) +
                if (jp) listOf(row(server, 40, "日本の礼装", "servantEquip")) else emptyList()
            "basic_mystic_code.json" -> listOf(row(server, 1, if (jp) "日本の既知制服" else "한국 제복", "mystic", "icon")) +
                if (jp) listOf(row(server, 2, "日本の制服", "mystic", "icon")) else emptyList()
            "basic_command_code.json" -> listOf(row("KR", 50, "한국 코드", "commandCode"))
            else -> error("Unexpected list")
        }
        return rows.joinToString(",", "[", "]")
    }

    val state get() = dashboardState(
        "userGame" to """[{"userId":7002,"name":"합성 JP 계정"}]""",
        "userSvt" to """[{"svtId":10},{"svtId":20},{"svtId":30},{"svtId":40}]""",
        "userSvtCollection" to """[{"svtId":10,"status":2,"maxLv":90},{"svtId":20,"status":2,"maxLv":90}]""",
    )
}
