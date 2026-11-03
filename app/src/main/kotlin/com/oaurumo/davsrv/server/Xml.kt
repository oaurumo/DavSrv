package com.oaurumo.davsrv.server

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object HttpDates {
    // SimpleDateFormat is not thread-safe and synchronized formatting serializes
    // every PROPFIND listing; one formatter per thread removes the contention.
    private val rfc1123 = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
    }
    private val isoFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
    }

    fun rfc(millis: Long): String = rfc1123.get()!!.format(Date(millis))
    fun iso(millis: Long): String = isoFmt.get()!!.format(Date(millis))

    /** Parses If-Modified-Since style values; returns 0 when unparseable. */
    fun parse(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { rfc1123.get()!!.parse(value.trim())?.time ?: 0L }.getOrDefault(0L)
    }
}

object Xml {
    fun escape(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (c in value) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Percent-encodes one path segment (UTF-8), keeping hrefs valid for all clients. */
    fun encodeSegment(segment: String): String {
        val bytes = segment.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            val unreserved = ch.isLetterOrDigit() && c < 128 || ch in "-._~"
            if (unreserved) sb.append(ch) else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    fun href(segments: List<String>, trailingSlash: Boolean): String {
        if (segments.isEmpty()) return "/"
        val body = segments.joinToString("/") { encodeSegment(it) }
        return "/$body" + if (trailingSlash) "/" else ""
    }
}
