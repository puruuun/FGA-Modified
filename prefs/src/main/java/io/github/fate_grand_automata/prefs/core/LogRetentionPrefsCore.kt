package io.github.fate_grand_automata.prefs.core

import android.content.Context

class LogRetentionPrefsCore(maker: PrefMaker) {
    val limit = maker.int("log_retention", DEFAULT)

    companion object {
        const val DEFAULT = 200
        val choices = listOf(50, 100, 200)
        fun normalize(value: Int) = value.takeIf { it in choices } ?: DEFAULT
        fun fromContext(context: Context) = LogRetentionPrefsCore(PrefMaker(
            context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)))
    }
}
