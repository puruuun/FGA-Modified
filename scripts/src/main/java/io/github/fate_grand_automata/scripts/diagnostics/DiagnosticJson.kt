package io.github.fate_grand_automata.scripts.diagnostics

/**
 * Minimal JSON writer for diagnostic records. The scripts module has no JSON library, and the
 * records are small. Supports null, String, Boolean, Number, Map (string keys) and Iterable.
 */
object DiagnosticJson {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> appendString(out, value)
            is Boolean -> out.append(value)
            is Number -> out.append(value)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) out.append(',')
                    appendString(out, entry.key.toString())
                    out.append(':')
                    append(out, entry.value)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }
            else -> appendString(out, value.toString())
        }
    }

    private fun appendString(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
