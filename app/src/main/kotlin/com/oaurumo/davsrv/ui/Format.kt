package com.oaurumo.davsrv.ui

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.oaurumo.davsrv.R

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return String.format(Locale.US, if (value >= 10) "%.0f %s" else "%.1f %s", value, units[i])
}

fun formatTime(millis: Long): String = timeFmt.format(Date(millis))

fun formatDate(millis: Long): String = dateFmt.format(Date(millis))

fun formatUptime(context: Context, millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> context.getString(R.string.uptime_hm, h, m)
        m > 0 -> context.getString(R.string.uptime_ms, m, s)
        else -> context.getString(R.string.uptime_s, s)
    }
}

fun statusColor(status: Int): Long = when {
    status in 200..299 -> 0xFF00796B
    status in 300..399 -> 0xFF8D6E63
    status in 400..499 -> 0xFFE08A00
    else -> 0xFFC62828
}
