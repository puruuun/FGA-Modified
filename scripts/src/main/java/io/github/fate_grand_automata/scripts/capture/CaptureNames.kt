package io.github.fate_grand_automata.scripts.capture

import java.net.URI

object CaptureNames {
    const val GAME_HOST = "yfgo-game.netmarble.com"
    val HOST_SERVERS = mapOf(GAME_HOST to "KR")
    fun server(target: String): String? = runCatching {
        HOST_SERVERS[URI(target).host?.lowercase()]
    }.getOrNull()
    private val accountPrefixes = listOf("NMAccount", "NetmarbleService", "NetmarbleUserDataService")

    /** Never retain the query: KR puts account identifiers there. */
    fun stripQuery(target: String): String = target.substringBefore('?').substringBefore('#')

    fun path(target: String): String = runCatching {
        URI(stripQuery(target)).path.orEmpty().trimStart('/')
    }.getOrDefault("")

    fun isAccountPath(target: String): Boolean =
        accountPrefixes.any { path(target).startsWith(it, ignoreCase = true) }

    fun classify(target: String): ApiPath = when {
        isAccountPath(target) -> ApiPath.Account
        path(target) == "login/top" -> ApiPath.Login
        else -> ApiPath.Game
    }

    enum class ApiPath { Account, Login, Game }
}
