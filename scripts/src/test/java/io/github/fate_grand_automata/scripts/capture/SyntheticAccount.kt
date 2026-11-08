package io.github.fate_grand_automata.scripts.capture

/** Synthetic login/top bodies; no real account data. Table names mimic the game, rows are filler. */
object SyntheticAccount {
    private val big = linkedMapOf("userSvt" to 1.07, "userSvtCollection" to 1.04, "userQuest" to 0.92, "userSvtStorage" to 0.53,
        "userEventMission" to 0.32, "userShop" to 0.30)

    /** About [megabytes] MB of JSON in [tables] tables, plus an other-players table the state must drop. */
    fun loginBody(userId: String = "7001", tables: Int = 76, megabytes: Double = 4.9, otherPlayers: Boolean = true): String {
        val out = StringBuilder(((megabytes + 1) * 1_000_000).toInt())
        out.append("""{"response":[{"nid":"login","resCode":"00","success":{"x":1}},{"nid":"userdata","resCode":"00","success":{}}],"cache":{"replaced":{""")
        out.append(""""userGame":[{"userId":"$userId","name":"Synthetic master","lv":120,"actMax":142,"actRecoverAt":2000000000,"stone":10}],""")
        out.append(""""userItem":[""")
        for (i in 0 until 300) { if (i > 0) out.append(','); out.append("""{"userId":"$userId","itemId":${100 + i},"num":$i}""") }
        out.append("]")
        val fixed = if (megabytes >= 4.0) big else emptyMap()
        val rest = (megabytes - fixed.values.sum()).coerceAtLeast(0.2)
        val smallCount = (tables - 2 - fixed.size).coerceAtLeast(1)
        for (t in 0 until tables - 2) {
            val name = fixed.keys.elementAtOrNull(t) ?: "userTable$t"
            val size = (fixed[name] ?: (rest / smallCount)) * 1_000_000
            out.append(""","$name":[""")
            var written = 0
            var i = 0
            while (written < size) {
                val row = """{"id":${t * 100000 + i},"userId":"$userId","svtId":$i,"lv":${i % 90},"note":"filler row $i","flag":true,"values":[1,2,3]}"""
                if (i > 0) out.append(',')
                out.append(row)
                written += row.length + 1
                i++
            }
            out.append("]")
        }
        if (otherPlayers) out.append(""","otherUserGame":[{"userId":"11","userName":"Friend"},{"userId":"12","userName":"Other"}]""")
        out.append("""},"updated":{"userItem":[{"userId":"$userId","itemId":100,"num":999}]},"deleted":{"userItem":[{"userId":"$userId","itemId":101}]},"serverTime":1800000000}}""")
        return out.toString()
    }
}
