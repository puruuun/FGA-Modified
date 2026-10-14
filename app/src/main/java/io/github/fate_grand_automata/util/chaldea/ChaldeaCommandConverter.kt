package io.github.fate_grand_automata.util.chaldea

import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Chaldea action records -> FGA commands, following Unic31/fgo-converter's default rules.
 * Reference: 1c95510bea01c49313f4b17ebcd9e196b385542f, src/routes/converter/+page.svelte.
 * Party stats, damage results, CE artwork, rewards and simulator options aren't imported.
 */
class ChaldeaCommandConverter(private val metadata: ChaldeaMetadata) {
    suspend fun convert(link: String, ignoreEnemyTargeting: Boolean = false): String =
        convert(ChaldeaShare.decode(link), ignoreEnemyTargeting)

    internal suspend fun convert(data: JsonObject, ignoreEnemyTargeting: Boolean = false): String = coroutineScope {
        val team = data.obj("team") ?: fail(ChaldeaImportError.DATA)
        val formation = (team.array("onFieldSvts") ?: fail(ChaldeaImportError.DATA)) +
            (team.array("backupSvts") ?: fail(ChaldeaImportError.DATA))
        if (formation.size != 6) fail(ChaldeaImportError.DATA)
        val ids = formation.mapNotNull { (it as? JsonObject)?.int("svtId") }.distinct()
        if (ids.any { it <= 0 }) fail(ChaldeaImportError.DATA)
        val servants = ids.associateWith { id -> async { metadata.servant(id) } }
        val mc = team.obj("mysticCode")?.int("mysticCodeId")?.takeIf { it > 0 }?.let { id ->
            async { metadata.mysticCode(id) }
        }
        val members = formation.map { element ->
            val share = element as? JsonObject ?: return@map null
            val id = share.int("svtId") ?: return@map null
            Member(share, servants.getValue(id).await())
        }
        val command = Conversion(data, members, mc?.await(), ignoreEnemyTargeting).run()
        if (command.isBlank()) fail(ChaldeaImportError.NO_ACTIONS)
        try {
            AutoSkillCommand.parse(command)
        } catch (_: Exception) {
            fail(ChaldeaImportError.UNSUPPORTED)
        }
        command
    }

    private class Member(share: JsonObject, metadata: JsonObject) {
        val id = share.int("svtId") ?: fail(ChaldeaImportError.DATA)
        private val skills = metadata.array("skills") ?: fail(ChaldeaImportError.METADATA, "$id")
        private val skillIds = share.array("skillIds") ?: fail(ChaldeaImportError.DATA)
        val np = metadata.array("noblePhantasms")?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it.int("id") == share.int("tdId") }
        var npFlags: Set<String>? = null
        var deathTimer: Int? = null

        fun skill(index: Int): JsonObject {
            val id = (skillIds.getOrNull(index) as? JsonPrimitive)?.intOrNull ?: fail(ChaldeaImportError.DATA)
            return skills.filterIsInstance<JsonObject>().firstOrNull { it.int("id") == id }
                ?: fail(ChaldeaImportError.METADATA, "$id")
        }

        fun singleNp() = npFlags?.contains("attackEnemyOne")
            ?: np?.array("effectFlags")?.any { (it as? JsonPrimitive)?.content == "attackEnemyOne" } == true
    }

    private class Conversion(
        val data: JsonObject,
        members: List<Member?>,
        val mc: JsonObject?,
        val ignoreEnemyTargeting: Boolean
    ) {
        val front = members.take(3).toMutableList()
        var back = members.drop(3).toMutableList()
        val delegate = data.obj("delegate")
        val swaps = ArrayDeque(delegate?.array("replaceMemberIndexes").orEmpty())
        val choices = ArrayDeque(delegate?.array("skillActSelectSelections").orEmpty())
        val npTypes = ArrayDeque(delegate?.array("tdTypeChanges").orEmpty())
        val delayed = mutableListOf<Pair<Int, Boolean>>()
        var enemy: Int? = null
        val result = StringBuilder()

        fun run(): String {
            val actions = data.array("actions") ?: fail(ChaldeaImportError.DATA)
            for (element in actions) {
                val action = element.objectOrFail()
                when (action.text("type")) {
                    "skill" -> skill(action)
                    "attack" -> attack(action)
                    "base" -> Unit // Chaldea bookkeeping, also ignored by the reference converter.
                    else -> fail(ChaldeaImportError.UNSUPPORTED)
                }
            }
            return result.toString().removeSuffix(",#,")
        }

        fun replace(index: Int, retreat: Boolean) {
            val previous = front[index]
            val next = back.indexOfFirst { it != null }
            front[index] = if (next >= 0) back[next] else null
            if (next >= 0) back[next] = null
            back = back.filterNotNull().toMutableList<Member?>().apply {
                if (retreat) add(previous)
                while (size < 3) add(null)
            }
        }

        fun targetEnemy(action: JsonObject, needsTarget: Boolean) {
            // Omit all enemy-selection commands at their source, including unsupported slots.
            // Ally/choice targets and the skill itself are still converted normally.
            if (ignoreEnemyTargeting) return
            val target = action.obj("options")?.int("enemyTarget") ?: return
            if (needsTarget && target != enemy) {
                if (target !in 0..2) fail(ChaldeaImportError.TARGETING)
                enemy = target
                result.append('t').append(3 - target)
            }
        }

        fun skill(action: JsonObject) {
            val index = action.int("skill")?.takeIf { it in 0..2 } ?: fail(ChaldeaImportError.DATA)
            val position = action.int("svt")
            val member = position?.let { front.getOrNull(it) ?: fail(ChaldeaImportError.DATA) }
            val skill = if (position == null) {
                (mc?.array("skills")?.getOrNull(index) as? JsonObject) ?: fail(ChaldeaImportError.METADATA)
            } else member!!.skill(index)
            targetEnemy(action, skill.hasFunction(target = "enemy"))
            var target = if (skill.targetsAlly()) {
                action.obj("options")?.int("playerTarget")?.let {
                    if (it !in 0..2) fail(ChaldeaImportError.DATA)
                    (it + 1).toString()
                } ?: fail(ChaldeaImportError.DATA)
            } else ""

            if (position == null) {
                if (skill.hasFunction(type = "replaceMember")) {
                    val swap = swaps.removeFirstOrNull() as? JsonArray ?: fail(ChaldeaImportError.DATA)
                    val field = (swap.getOrNull(0) as? JsonPrimitive)?.intOrNull
                        ?.takeIf { it in 0..2 } ?: fail(ChaldeaImportError.DATA)
                    val reserve = (swap.getOrNull(1) as? JsonPrimitive)?.intOrNull
                        ?.takeIf { it in 0..2 } ?: fail(ChaldeaImportError.DATA)
                    if (back[reserve] == null) fail(ChaldeaImportError.DATA)
                    result.append('x').append(field + 1).append(back.take(reserve + 1).count { it != null })
                    val old = front[field]
                    front[field] = back[reserve]
                    back[reserve] = old
                } else result.append("jkl"[index]).append(target)
                return
            }

            member!!
            when {
                (member.id == 404200 && index == 2) || (member.id == 1102200 && index == 2) ||
                    (member.id == 403900 && index == 1) -> delayed.add(position to false)
                member.id == 1101600 && index == 1 -> delayed.add(position to true)
                member.id == 1100100 && index == 2 -> member.deathTimer = 5
                member.id == 1002200 && index == 2 -> {
                    val victim = action.obj("options")?.int("playerTarget") ?: fail(ChaldeaImportError.DATA)
                    (front.getOrNull(victim) ?: fail(ChaldeaImportError.DATA)).deathTimer = 3
                }
            }
            var choice = ""
            val count = (skill.obj("script")?.array("SelectAddInfo")?.firstOrNull() as? JsonObject)
                ?.array("btn")?.size ?: 0
            if (count > 0) {
                val selected = (choices.removeFirstOrNull() as? JsonPrimitive)?.intOrNull
                    ?.takeIf { it in 0 until count } ?: fail(ChaldeaImportError.DATA)
                // The existing FGA runtime has coordinates for two- and three-option skills.
                if (count !in 2..3) fail(ChaldeaImportError.UNSUPPORTED, "Ch$count")
                choice = "[Ch$count${'A' + selected}]"
                if ((member.id == 2301100 && index == 1) || (member.id == 204900 && index == 2)) {
                    member.npFlags = setOf(if (selected == 0) "attackEnemyAll" else "attackEnemyOne")
                }
            }
            fun nextType(): Int = (npTypes.removeFirstOrNull() as? JsonPrimitive)?.intOrNull
                ?: fail(ChaldeaImportError.DATA)
            when {
                member.id == 1100900 && index == 1 -> target = when (nextType()) {
                    1 -> "2"; 2 -> "3"; 3 -> "1"; else -> fail(ChaldeaImportError.DATA)
                }
                (member.id == 200100 && index == 2) || (member.id == 2300600 && index == 2) -> {
                    target = when (nextType()) { 1 -> "7"; 2 -> "8"; else -> fail(ChaldeaImportError.DATA) }
                    if (member.id == 2300600) member.npFlags = setOf(if (target == "7") "attackEnemyAll" else "support")
                }
                member.id == 205000 && index == 2 -> {
                    member.npFlags = setOf(if (member.singleNp()) "attackEnemyAll" else "attackEnemyOne")
                }
                member.id == 304800 && index == 2 && member.singleNp() -> member.npFlags = setOf("attackEnemyAll")
            }
            result.append("abcdefghi"[position * 3 + index])
            if (choice.isNotEmpty() && target.isNotEmpty()) result.append('(').append(choice).append(target).append(')')
            else result.append(choice).append(target)
        }

        fun attack(action: JsonObject) {
            val attacks = action.array("attacks")?.map { it.objectOrFail() } ?: fail(ChaldeaImportError.DATA)
            targetEnemy(action, attacks.any {
                it.bool("isTD") != true || front.getOrNull(it.int("svt") ?: -1)?.singleNp() == true
            })
            var beforeNp = 0
            val nps = StringBuilder()
            for (attack in attacks) {
                if (attack.bool("isTD") != true) {
                    if (nps.isEmpty()) beforeNp++
                    continue
                }
                val index = attack.int("svt")?.takeIf { it in 0..2 } ?: fail(ChaldeaImportError.DATA)
                val member = front[index] ?: fail(ChaldeaImportError.DATA)
                val np = member.np ?: fail(ChaldeaImportError.METADATA, "${member.id}")
                nps.append(index + 4)
                when {
                    np.hasFunction("forceInstantDeath", "self") -> replace(index, false)
                    np.hasFunction("forceInstantDeath", "ptSelfAnotherFirst") -> {
                        // Preserve the reference converter's slot update for output compatibility.
                        if ((0..2).any { it != index && front[it] != null }) replace(index, false)
                    }
                    np.hasFunction("moveToLastSubmember", "self") -> replace(index, true)
                }
            }
            if (beforeNp in 1..2) result.append('n').append(beforeNp)
            result.append(nps).append(",#,")
            front.forEachIndexed { index, member ->
                member?.deathTimer?.let { timer ->
                    member.deathTimer = timer - 1
                    if (timer <= 1) {
                        delayed.add(index to false)
                        member.deathTimer = null
                    }
                }
            }
            delayed.forEach { (index, retreat) -> replace(index, retreat) }
            delayed.clear()
        }
    }
}

private fun JsonObject.hasFunction(type: String? = null, target: String? = null) =
    array("functions").orEmpty().filterIsInstance<JsonObject>().any {
        (type == null || it.text("funcType") == type) && (target == null || it.text("funcTargetType") == target)
    }

private fun JsonObject.targetsAlly() = hasFunction(target = "ptOne") ||
    obj("script")?.array("condBranchSkillInfo")?.isNotEmpty() == true
