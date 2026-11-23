package io.github.fate_grand_automata.scripts.capture

/**
 * Things in a JSON text that a parse and write-back would silently change: an object with the same key twice
 * (the tree keeps one) and a lone surrogate escape (it cannot be written back as valid UTF-8).
 * The scan reads the text once and keeps one key set per open object.
 */
internal object JsonRisks {
    fun found(text: String): Boolean {
        val keys = ArrayList<HashSet<String>?>()
        var i = 0
        val n = text.length
        while (i < n) {
            when (text[i]) {
                '{' -> keys.add(HashSet())
                '[' -> keys.add(null)
                '}', ']' -> if (keys.isNotEmpty()) keys.removeAt(keys.size - 1)
                '"' -> {
                    val start = i + 1
                    i = start
                    var escaped = false
                    while (i < n && text[i] != '"') {
                        if (text[i] == '\\') {
                            escaped = true
                            i++
                        }
                        i++
                    }
                    if (i >= n) return false
                    val raw = text.substring(start, i)
                    val value = if (escaped) unescape(raw) ?: return true else raw
                    var j = i + 1
                    while (j < n && text[j].isWhitespace()) j++
                    val top = keys.lastOrNull()
                    if (j < n && text[j] == ':' && top != null && !top.add(value)) return true
                }
            }
            i++
        }
        return false
    }

    /** The string value of [raw] (the text between the quotes), or null when it holds a lone surrogate escape. */
    private fun unescape(raw: String): String? {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                sb.append(c)
                i++
                continue
            }
            when (val e = raw[i + 1]) {
                'u' -> {
                    val unit = raw.substring(i + 2, minOf(i + 6, raw.length)).toIntOrNull(16) ?: return null
                    sb.append(unit.toChar())
                    i += 6
                }
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                else -> { sb.append(e); i += 2 }
            }
        }
        val s = sb.toString()
        for (k in s.indices) {
            val ch = s[k]
            if (Character.isHighSurrogate(ch)) {
                if (k + 1 >= s.length || !Character.isLowSurrogate(s[k + 1])) return null
            } else if (Character.isLowSurrogate(ch) && (k == 0 || !Character.isHighSurrogate(s[k - 1]))) return null
        }
        return s
    }
}
