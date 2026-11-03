package com.oaurumo.davsrv.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

object DavStatus {
    val MultiStatus = HttpStatusCode(207, "Multi-Status")
    val Unprocessable = HttpStatusCode(422, "Unprocessable Entity")
    val Locked = HttpStatusCode(423, "Locked")
    val InsufficientStorage = HttpStatusCode(507, "Insufficient Storage")
}

object DavHeaders {
    const val DAV = "DAV"
    const val Depth = "Depth"
    const val Destination = "Destination"
    const val Overwrite = "Overwrite"
    const val LockToken = "Lock-Token"
    const val Timeout = "Timeout"
    const val If = "If"
}

suspend fun ApplicationCall.respondXml(xml: String, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(xml, ContentType.parse("application/xml; charset=utf-8"), status)
}

/** Decodes %XX escapes without turning '+' into a space (paths, not form data). */
fun decodePath(raw: String): String {
    if (!raw.contains('%')) return raw
    val bytes = java.io.ByteArrayOutputStream(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length) {
            val hex = raw.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                bytes.write(hex)
                i += 3
                continue
            }
        }
        bytes.write(c.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}
