package io.github.fate_grand_automata.scripts.models

/**
 * One list per wave or turn entry, each holding all nine [ServantCardKey]s in click order.
 * The last entry repeats past the end of the list.
 */
class ServantCardPriorityPerWave private constructor(
    private val perEntry: List<List<ServantCardKey>>
) : List<List<ServantCardKey>> by perEntry {
    fun atWave(wave: Int) = perEntry[wave.coerceIn(perEntry.indices)]

    override fun toString() =
        perEntry.joinToString(entrySeparator) { it.joinToString(separator) }

    companion object {
        private const val entrySeparator = "\n"
        private const val separator = ","

        val default get() = from(listOf(ServantCardKey.all))

        fun from(perEntry: List<List<ServantCardKey>>): ServantCardPriorityPerWave {
            require(perEntry.isNotEmpty()) { "At least one entry is needed" }
            perEntry.forEach {
                require(it.size == 9 && it.toSet().size == 9) { "Each entry needs the nine distinct cards" }
            }
            return ServantCardPriorityPerWave(perEntry.map { it.toList() })
        }

        /** Blank text gives the default. Throws [IllegalArgumentException] on malformed text. */
        fun of(text: String): ServantCardPriorityPerWave =
            if (text.isBlank()) default
            else from(
                text.split(entrySeparator).map { line ->
                    line.split(separator).map {
                        ServantCardKey.parse(it) ?: throw IllegalArgumentException("Invalid card '$it'")
                    }
                }
            )
    }
}
