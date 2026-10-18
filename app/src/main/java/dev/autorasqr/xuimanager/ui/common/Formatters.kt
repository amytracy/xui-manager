package dev.autorasqr.xuimanager.ui.common

import android.content.Context
import dev.autorasqr.xuimanager.R
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Date

object Formatters {
    private const val KIB = 1024.0

    /** Binary units, matching how 3x-ui counts GB. */
    fun bytes(context: Context, value: Long): String {
        val format = DecimalFormat("0.#")
        var size = value.toDouble()
        val units = intArrayOf(R.string.unit_b, R.string.unit_kb, R.string.unit_mb, R.string.unit_gb, R.string.unit_tb)
        var unit = 0
        while (size >= KIB && unit < units.lastIndex) {
            size /= KIB
            unit++
        }
        return context.getString(units[unit], if (unit == 0) value.toString() else format.format(size))
    }

    fun date(epochMillis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

    fun dateTime(epochMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

    fun gigabytesToBytes(gb: Double): Long = (gb * KIB * KIB * KIB).toLong()

    /** Inverse of [gigabytesToBytes] for prefilling a form; "0" for unlimited. */
    fun bytesToGigabytesText(bytes: Long): String =
        if (bytes <= 0) "0" else DecimalFormat("0.##", java.text.DecimalFormatSymbols(java.util.Locale.US)).format(bytes / (KIB * KIB * KIB))

    fun uptime(context: Context, seconds: Long): String {
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        return if (days > 0) context.getString(R.string.uptime_dhm, days, hours, minutes)
        else context.getString(R.string.uptime_hm, hours, minutes)
    }

    const val DAY_MS = 24L * 60 * 60 * 1000
}
