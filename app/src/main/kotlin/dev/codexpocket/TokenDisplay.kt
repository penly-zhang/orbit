package dev.codexpocket

import java.util.Locale
import kotlin.math.round

/** Decimal units, one fractional digit; promote rounded values at unit boundaries. */
internal fun compactTokenCount(count: Long): String {
    if (count < 1_000) return count.toString()
    val units = listOf("", "K", "M", "B")
    var value = count.toDouble()
    var unit = 0
    while (value >= 1_000 && unit < units.lastIndex) { value /= 1_000; unit++ }
    var rounded = round(value * 10) / 10
    if (rounded >= 1_000 && unit < units.lastIndex) { rounded /= 1_000; unit++ }
    return String.format(Locale.ROOT, "%.1f", rounded).removeSuffix(".0") + units[unit]
}
