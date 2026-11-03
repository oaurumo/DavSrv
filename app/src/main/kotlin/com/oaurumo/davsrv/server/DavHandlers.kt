package com.oaurumo.davsrv.server

import com.oaurumo.davsrv.R
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * All WebDAV method implementations. Handlers work against [DavNode] only, so
 * path storage and SAF storage behave identically.
 */
class DavHandlers(
    private val lockManager: LockManager,
    private val onBytesIn: (Long) -> Unit,
    private val onBytesOut: (Long) -> Unit
) {

    suspend fun options(call: ApplicationCall): HttpStatusCode {
        call.response.header(DavHeaders.DAV, "1, 2")
        call.response.header(HttpHeaders.Allow, ALLOWED)
        call.response.header("MS-Author-Via", "DAV")
        call.response.header("Accept-Ranges", "bytes")
        call.respondText("", ContentType.Text.Plain, HttpStatusCode.OK)
        return HttpStatusCode.OK
    }

    // ---------------------------------------------------------------- PROPFIND

    suspend fun propfind(
        call: ApplicationCall,
        node: DavNode,
        segments: List<String>,
        includeHidden: Boolean
    ): HttpStatusCode {
        val depth = (call.request.header(DavHeaders.Depth) ?: "1").lowercase()
        val xml = withContext(Dispatchers.IO) {
            buildString(2048) {
                append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
                append("""<D:multistatus xmlns:D="DAV:">""").append('\n')
                append(resourceXml(node, segments))
                if (node.isDirectory && depth != "0") {
                    val budget = intArrayOf(if (depth == "infinity") 8000 else 20000)
                    appendChildren(this, node, segments, includeHidden, depth == "infinity", 0, budget)
                }
                append("</D:multistatus>\n")
            }
        }
        call.respondXml(xml, DavStatus.MultiStatus)
        return DavStatus.MultiStatus
    }

    private fun appendChildren(
        sb: StringBuilder,
        dir: DavNode,
        segments: List<String>,
        includeHidden: Boolean,
        recursive: Boolean,
        level: Int,
        budget: IntArray
    ) {
        if (level > 24) return
        val children = runCatching { dir.list() }.getOrDefault(emptyList())
        for (child in children) {
            if (budget[0]-- <= 0) return
            if (!includeHidden && Names.isHidden(child.name)) continue
            val childSegments = segments + child.name
            sb.append(resourceXml(child, childSegments))
            if (recursive && child.isDirectory) {
                appendChildren(sb, child, childSegments, includeHidden, true, level + 1, budget)
            }
        }
    }

    private fun resourceXml(node: DavNode, segments: List<String>): String {
        val isDir = node.isDirectory
        val href = Xml.href(segments, isDir)
        val display = if (segments.isEmpty()) "/" else segments.last()
        val mime = if (isDir) "" else node.mimeType
        val sb = StringBuilder(512)
        sb.append("  <D:response>\n")
        sb.append("    <D:href>").append(Xml.escape(href)).append("</D:href>\n")
        sb.append("    <D:propstat>\n      <D:prop>\n")
        sb.append("        <D:displayname>").append(Xml.escape(display)).append("</D:displayname>\n")
        sb.append("        <D:resourcetype>")
        sb.append(if (isDir) "<D:collection/>" else "")
        sb.append("</D:resourcetype>\n")
        if (!isDir) {
            sb.append("        <D:getcontentlength>").append(node.size).append("</D:getcontentlength>\n")
            sb.append("        <D:getcontenttype>").append(Xml.escape(mime)).append("</D:getcontenttype>\n")
            sb.append("        <D:getetag>").append(etag(node, segments)).append("</D:getetag>\n")
        }
        val modified = if (node.lastModified > 0) node.lastModified else System.currentTimeMillis()
        sb.append("        <D:getlastmodified>").append(HttpDates.rfc(modified)).append("</D:getlastmodified>\n")
        sb.append("        <D:creationdate>").append(HttpDates.iso(modified)).append("</D:creationdate>\n")
        sb.append("        <D:supportedlock>")
        sb.append("<D:lockentry><D:lockscope><D:exclusive/></D:lockscope>")
        sb.append("<D:locktype><D:write/></D:locktype></D:lockentry>")
        sb.append("</D:supportedlock>\n")
        sb.append("        <D:lockdiscovery>")
        lockManager.locksFor("/" + segments.joinToString("/")).forEach { sb.append(lockDiscoveryXml(it)) }
        sb.append("</D:lockdiscovery>\n")
        if (segments.isEmpty()) {
            Storage.spaceOf(node)?.let { (free, total) ->
                sb.append("        <D:quota-available-bytes>").append(free).append("</D:quota-available-bytes>\n")
                sb.append("        <D:quota-used-bytes>").append(total - free).append("</D:quota-used-bytes>\n")
            }
        }
        sb.append("      </D:prop>\n      <D:status>HTTP/1.1 200 OK</D:status>\n")
        sb.append("    </D:propstat>\n  </D:response>\n")
        return sb.toString()
    }

    private fun etag(node: DavNode, segments: List<String>): String {
        val raw = segments.joinToString("/") + ":" + node.size + ":" + node.lastModified
        return "\"" + Integer.toHexString(raw.hashCode()) + "-" + node.size.toString(16) + "\""
    }

    // -------------------------------------------------------------------- GET

    suspend fun get(
        call: ApplicationCall,
        node: DavNode,
        segments: List<String>,
        headOnly: Boolean,
        includeHidden: Boolean
    ): HttpStatusCode {
        if (node.isDirectory) {
            if (headOnly) {
                call.respondText("", ContentType.Text.Html, HttpStatusCode.OK)
                return HttpStatusCode.OK
            }
            val html = withContext(Dispatchers.IO) {
                BrowserPage.render(node, segments, includeHidden, lockManager)
            }
            call.respondText(html, ContentType.parse("text/html; charset=utf-8"), HttpStatusCode.OK)
            return HttpStatusCode.OK
        }

        val etag = etag(node, segments)
        val lastModified = node.lastModified
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.LastModified, HttpDates.rfc(lastModified))
        call.response.header(HttpHeaders.AcceptRanges, "bytes")

        val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
        if (ifNoneMatch != null && ifNoneMatch.split(',').any { it.trim() == etag || it.trim() == "*" }) {
            call.respondText("", status = HttpStatusCode.NotModified)
            return HttpStatusCode.NotModified
        }
        val ifModifiedSince = HttpDates.parse(call.request.header(HttpHeaders.IfModifiedSince))
        if (ifNoneMatch == null && ifModifiedSince > 0 && lastModified / 1000 <= ifModifiedSince / 1000) {
            call.respondText("", status = HttpStatusCode.NotModified)
            return HttpStatusCode.NotModified
        }

        val contentType0 = runCatching { ContentType.parse(node.mimeType) }
            .getOrDefault(ContentType.Application.OctetStream)
        val total = node.size

        // Range support is essential for media players and resumable downloads.
        val range = parseRange(call.request.header(HttpHeaders.Range), total)
        if (range != null && !headOnly) {
            val (start, end) = range
            val length = end - start + 1
            call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$total")
            call.respondStream(contentType0, HttpStatusCode.PartialContent, length) { out ->
                node.openInput().use { input -> copyRange(input, out, start, length) }
            }
            return HttpStatusCode.PartialContent
        }

        if (headOnly) {
            // HEAD must report the real length so clients can size a download.
            call.respond(object : OutgoingContent.NoContent() {
                override val status: HttpStatusCode get() = HttpStatusCode.OK
                override val contentType: ContentType get() = contentType0
                override val contentLength: Long? get() = total
            })
            return HttpStatusCode.OK
        }

        call.respondStream(contentType0, HttpStatusCode.OK, total) { out ->
            node.openInput().use { input -> input.copyTo(out, BUFFER) }
        }
        return HttpStatusCode.OK
    }

    private suspend fun ApplicationCall.respondStream(
        contentType: ContentType,
        status: HttpStatusCode,
        length: Long,
        block: suspend (OutputStream) -> Unit
    ) {
        val counter = CountingOutputStream()
        respondOutputStream(contentType, status, length) {
            counter.attach(this)
            try {
                block(counter)
                counter.flush()
            } finally {
                counter.detach()
                onBytesOut(counter.count)
            }
        }
    }

    // -------------------------------------------------------------------- PUT

    suspend fun put(call: ApplicationCall, node: DavNode?, parent: DavNode?, name: String): HttpStatusCode {
        if (node?.isDirectory == true) return HttpStatusCode.MethodNotAllowed
        if (parent == null) return HttpStatusCode.Conflict
        val target = node ?: parent.createChild(name, false)
        if (target == null) {
            call.respondText(
                ServerText.s(R.string.srv_err_create_file),
                status = HttpStatusCode.Forbidden
            )
            return HttpStatusCode.Forbidden
        }

        val counter = CountingInputStream(call.receiveChannel().toInputStream())
        try {
            withContext(Dispatchers.IO) {
                target.openOutput(append = false).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val read = counter.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                    }
                    out.flush()
                }
            }
        } catch (t: Throwable) {
            runCatching { if (node == null) target.delete() }
            throw t
        } finally {
            onBytesIn(counter.count)
        }
        val status = if (node == null) HttpStatusCode.Created else HttpStatusCode.NoContent
        call.respond(status)
        return status
    }

    // ----------------------------------------------------------------- DELETE

    suspend fun delete(call: ApplicationCall, node: DavNode): HttpStatusCode =
        withContext(Dispatchers.IO) {
            if (node.delete()) {
                call.respond(HttpStatusCode.NoContent)
                HttpStatusCode.NoContent
            } else {
                call.respondText(
                    ServerText.s(R.string.srv_err_delete),
                    status = HttpStatusCode.Forbidden
                )
                HttpStatusCode.Forbidden
            }
        }

    // ------------------------------------------------------------------ MKCOL

    suspend fun mkcol(call: ApplicationCall, parent: DavNode?, name: String, exists: Boolean): HttpStatusCode {
        if (exists) {
            call.respondText(ServerText.s(R.string.srv_err_exists), status = HttpStatusCode.MethodNotAllowed)
            return HttpStatusCode.MethodNotAllowed
        }
        if (parent == null) return HttpStatusCode.Conflict
        val created = parent.createChild(name, true)
        return if (created != null) {
            call.respond(HttpStatusCode.Created)
            HttpStatusCode.Created
        } else {
            call.respondText(ServerText.s(R.string.srv_err_mkcol), status = HttpStatusCode.Forbidden)
            HttpStatusCode.Forbidden
        }
    }

    // -------------------------------------------------------------- COPY/MOVE

    suspend fun copyOrMove(
        call: ApplicationCall,
        root: DavNode,
        source: DavNode,
        sourceSegments: List<String>,
        move: Boolean,
        includeHidden: Boolean
    ): HttpStatusCode {
        // Every early exit must still send an HTTP response, otherwise Ktor
        // answers with a plain 404 and clients (Windows) misread the outcome.
        suspend fun fail(status: HttpStatusCode, message: String? = null): HttpStatusCode {
            if (message != null) call.respondText(message, status = status) else call.respond(status)
            return status
        }

        val destinationHeader = call.request.header(DavHeaders.Destination)
            ?: return fail(HttpStatusCode.BadRequest, ServerText.s(R.string.srv_err_dest_missing))
        val rawPath = runCatching {
            if (destinationHeader.startsWith("http", ignoreCase = true)) {
                java.net.URL(destinationHeader).path
            } else destinationHeader
        }.getOrElse { return fail(HttpStatusCode.BadRequest, ServerText.s(R.string.srv_err_dest_unparsable)) }

        val decoded = decodePath(rawPath)
        val destSegments = Names.split(decoded)
        if (destSegments.isEmpty()) return fail(HttpStatusCode.Forbidden, ServerText.s(R.string.srv_err_dest_path_invalid))
        val destName = destSegments.last()
        if (!Names.isSafe(destName)) return fail(HttpStatusCode.Forbidden, ServerText.s(R.string.srv_err_dest_name_invalid))
        val destParentSegments = destSegments.dropLast(1)

        // Guard against no-op and recursive self-copies.
        val sourcePath = sourceSegments.joinToString("/")
        val destPath = destSegments.joinToString("/")
        if (destPath == sourcePath) {
            return if (move) fail(HttpStatusCode.NoContent)
            else fail(HttpStatusCode.Forbidden, ServerText.s(R.string.srv_err_same_path))
        }
        if (destPath.startsWith("$sourcePath/")) {
            return fail(HttpStatusCode.Conflict, ServerText.s(R.string.srv_err_into_self))
        }

        val destParent = Names.resolve(root, destParentSegments)
            ?: return fail(HttpStatusCode.Conflict, ServerText.s(R.string.srv_err_dest_parent_missing))
        if (!destParent.isDirectory) return fail(HttpStatusCode.Conflict, ServerText.s(R.string.srv_err_dest_not_dir))
        val destExisting = destParent.find(destName)
        val overwrite = !(call.request.header(DavHeaders.Overwrite) ?: "T").equals("F", ignoreCase = true)
        if (destExisting != null) {
            if (!overwrite) {
                return fail(HttpStatusCode.PreconditionFailed, ServerText.s(R.string.srv_err_dest_exists))
            }
            if (destExisting.isDirectory && !source.isDirectory) {
                return fail(HttpStatusCode.Conflict, ServerText.s(R.string.srv_err_file_over_dir))
            }
            val removed = withContext(Dispatchers.IO) { destExisting.delete() }
            if (!removed) return fail(HttpStatusCode.Forbidden, ServerText.s(R.string.srv_err_overwrite_failed))
        }

        // A same-parent MOVE is just a rename: atomic, keeps metadata, never loses data.
        val sameParent = destParentSegments == sourceSegments.dropLast(1)
        val ok = withContext(Dispatchers.IO) {
            if (move && sameParent && source.rename(destName)) true
            else runCatching { copyRecursive(source, destParent, destName, includeHidden) }.getOrDefault(false)
        }
        if (!ok) {
            // Never delete the source when the destination could not be written.
            return fail(
                HttpStatusCode.Forbidden,
                ServerText.s(R.string.srv_err_transfer)
            )
        }

        if (move) {
            withContext(Dispatchers.IO) { source.delete() }
            call.respond(HttpStatusCode.NoContent)
            return HttpStatusCode.NoContent
        }
        val status = if (destExisting != null) HttpStatusCode.NoContent else HttpStatusCode.Created
        call.respond(status)
        return status
    }

    private fun copyRecursive(source: DavNode, destParent: DavNode, destName: String, includeHidden: Boolean): Boolean {
        if (source.isDirectory) {
            val dir = destParent.createChild(destName, true) ?: return false
            for (child in source.list()) {
                if (!includeHidden && Names.isHidden(child.name)) continue
                if (!copyRecursive(child, dir, child.name, includeHidden)) return false
            }
            return true
        }
        val target = destParent.createChild(destName, false) ?: return false
        return runCatching {
            source.openInput().use { input ->
                target.openOutput(append = false).use { out -> input.copyTo(out, BUFFER) }
            }
            true
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------- PROPPATCH

    suspend fun proppatch(call: ApplicationCall, node: DavNode, segments: List<String>): HttpStatusCode {
        val href = Xml.href(segments, node.isDirectory)
        val xml = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
            append("""<D:multistatus xmlns:D="DAV:">""").append('\n')
            append("  <D:response>\n    <D:href>").append(Xml.escape(href)).append("</D:href>\n")
            append("    <D:propstat>\n      <D:prop/>\n")
            append("      <D:status>HTTP/1.1 200 OK</D:status>\n")
            append("    </D:propstat>\n  </D:response>\n</D:multistatus>")
        }
        call.respondXml(xml, DavStatus.MultiStatus)
        return DavStatus.MultiStatus
    }

    // ------------------------------------------------------------------- LOCK

    suspend fun lock(call: ApplicationCall, segments: List<String>): HttpStatusCode {
        val path = "/" + segments.joinToString("/")
        val timeout = parseTimeout(call.request.header(DavHeaders.Timeout))
        val existingLockToken = call.request.header(DavHeaders.LockToken)
            ?: extractToken(call.request.header(DavHeaders.If))

        val lock = if (existingLockToken != null && lockManager.active(existingLockToken) != null) {
            lockManager.refresh(existingLockToken, timeout) ?: run {
                call.respondText(ServerText.s(R.string.srv_err_lock_expired), status = DavStatus.Locked)
                return DavStatus.Locked
            }
        } else {
            if (lockManager.conflicts(path, call.request.header(DavHeaders.If))) return DavStatus.Locked
            val depth = call.request.header(DavHeaders.Depth) ?: "infinity"
            lockManager.lock(path, depth, "DavSrv", timeout)
        }

        call.response.header(DavHeaders.LockToken, "<${lock.token}>")
        val xml = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
            append("""<D:prop xmlns:D="DAV:"><D:lockdiscovery>""")
            append(lockDiscoveryXml(lock))
            append("</D:lockdiscovery></D:prop>")
        }
        call.respondXml(xml)
        return HttpStatusCode.OK
    }

    suspend fun unlock(call: ApplicationCall): HttpStatusCode {
        val token = call.request.header(DavHeaders.LockToken)?.trim()?.trim('<', '>')
            ?: extractToken(call.request.header(DavHeaders.If))
        if (token == null) {
            call.respondText(ServerText.s(R.string.srv_err_lock_token_missing), status = HttpStatusCode.BadRequest)
            return HttpStatusCode.BadRequest
        }
        return if (lockManager.unlock(token)) {
            call.respond(HttpStatusCode.NoContent)
            HttpStatusCode.NoContent
        } else {
            call.respondText(ServerText.s(R.string.srv_err_lock_not_found), status = HttpStatusCode.Conflict)
            HttpStatusCode.Conflict
        }
    }

    private fun lockDiscoveryXml(lock: DavLock): String {
        val seconds = ((lock.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return "<D:activelock>" +
            "<D:locktype><D:write/></D:locktype>" +
            "<D:lockscope><D:exclusive/></D:lockscope>" +
            "<D:depth>${Xml.escape(lock.depth)}</D:depth>" +
            "<D:timeout>Second-$seconds</D:timeout>" +
            "<D:locktoken><D:href>${Xml.escape(lock.token)}</D:href></D:locktoken>" +
            "<D:lockroot><D:href>${Xml.escape(lock.path)}</D:href></D:lockroot>" +
            "</D:activelock>"
    }

    private fun parseTimeout(raw: String?): Long {
        val value = raw ?: return 1800
        val secs = if (value.contains("Infinite", ignoreCase = true)) MAX_LOCK_SECONDS
        else value.split(',').firstNotNullOfOrNull { part ->
            part.trim().removePrefix("Second-").toLongOrNull()
        } ?: 1800
        // Cap lock lifetime: a client that dies without UNLOCK must not block writes for days.
        return secs.coerceIn(60, MAX_LOCK_SECONDS)
    }

    private fun extractToken(ifHeader: String?): String? {
        if (ifHeader == null) return null
        val idx = ifHeader.indexOf("opaquelocktoken:")
        if (idx < 0) return null
        return ifHeader.substring(idx).takeWhile { !it.isWhitespace() && it != '>' && it != ')' }.trim()
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null || !header.startsWith("bytes=") || total <= 0) return null
        val spec = header.removePrefix("bytes=").split(',').first().trim()
        val parts = spec.split('-', limit = 2)
        if (parts.size != 2) return null
        val startStr = parts[0].trim()
        val endStr = parts[1].trim()
        return runCatching {
            if (startStr.isEmpty()) {
                val suffix = endStr.toLong().coerceAtMost(total)
                (total - suffix) to (total - 1)
            } else {
                val start = startStr.toLong()
                val end = if (endStr.isEmpty()) total - 1 else endStr.toLong().coerceAtMost(total - 1)
                if (start > end || start >= total) null else start to end
            }
        }.getOrNull()
    }

    private fun copyRange(input: InputStream, out: OutputStream, start: Long, length: Long) {
        val buf = ByteArray(BUFFER)
        var skipped = 0L
        while (skipped < start) {
            val want = minOf(buf.size.toLong(), start - skipped).toInt()
            val read = input.read(buf, 0, want)
            if (read < 0) return
            skipped += read
        }
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val MAX_LOCK_SECONDS = 2 * 3600L
        const val ALLOWED = "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, PROPPATCH, COPY, MOVE, LOCK, UNLOCK"
    }
}

private class CountingInputStream(private val delegate: InputStream) : InputStream() {
    var count = 0L
        private set

    override fun read(): Int = delegate.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, len).also { if (it > 0) count += it }
    override fun close() = delegate.close()
    override fun available(): Int = delegate.available()
}

private class CountingOutputStream : OutputStream() {
    var count = 0L
        private set
    private var target: OutputStream? = null

    fun attach(stream: OutputStream) { target = stream }
    fun detach() { target = null }

    private val out get() = target ?: throw IllegalStateException("detached")

    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    override fun flush() { target?.flush() }
    override fun close() { /* managed by Ktor */ }
}
