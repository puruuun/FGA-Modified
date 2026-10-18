package io.github.fate_grand_automata.util

import android.content.Context
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy

fun fpSellSummary(context: Context, summary: FpSellPolicy.Summary): String = context.getString(
    R.string.fp_sell_summary, summary.total,
    *FpSellPolicy.Category.entries.map { summary.counts[it] ?: 0 }.toTypedArray(), summary.unknownLock)
