package com.oaurumo.davsrv.server

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Uniform read/write view over either a real path or a SAF document tree. */
interface DavNode {
    val name: String
    val isDirectory: Boolean
    val size: Long
    val lastModified: Long
    val mimeType: String

    fun exists(): Boolean
    fun writable(): Boolean
    fun list(): List<DavNode>
    fun find(name: String): DavNode?
    fun createChild(name: String, directory: Boolean): DavNode?
    fun openInput(): InputStream
    fun openOutput(append: Boolean): OutputStream
    fun delete(): Boolean
    fun rename(newName: String): Boolean
    fun childCount(): Int
}

object Names {
    fun isSafe(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')

    fun isHidden(name: String) = name.startsWith(".")

    /** Resolves a decoded URL path into nodes, never escaping the root. */
    fun resolve(root: DavNode, segments: List<String>): DavNode? {
        var current: DavNode = root
        for (seg in segments) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == ".." || !isSafe(seg)) return null
            if (!current.isDirectory) return null
            current = current.find(seg) ?: return null
        }
        return current
    }

    fun split(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }
}

object Mime {
    private val extra = mapOf(
        "mkv" to "video/x-matroska",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "opus" to "audio/opus",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "apk" to "application/vnd.android.package-archive",
        "md" to "text/markdown",
        "epub" to "application/epub+zip",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )

    fun guess(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        extra[ext]?.let { return it }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun isText(mime: String) = mime.startsWith("text/") ||
        mime.contains("json") || mime.contains("xml") || mime.contains("javascript")

    fun isImage(mime: String) = mime.startsWith("image/")
}

class FileNode(val file: File) : DavNode {
    // Snapshotted once per node: PROPFIND loops hit these properties many times,
    // and every stat on FUSE-backed storage is a costly syscall.
    private val cachedDir: Boolean = file.isDirectory
    override val name: String get() = file.name
    override val isDirectory: Boolean get() = cachedDir
    override val size: Long get() = if (cachedDir) 0L else file.length()
    override val lastModified: Long get() = file.lastModified()
    override val mimeType: String get() = if (cachedDir) "httpd/unix-directory" else Mime.guess(file.name)

    override fun exists() = file.exists()
    override fun writable() = file.canWrite()
    override fun childCount(): Int = file.list()?.size ?: 0

    override fun list(): List<DavNode> = (file.listFiles() ?: emptyArray())
        .map { FileNode(it) }
        .sortedWith(compareByDescending<DavNode> { it.isDirectory }.thenBy { it.name.lowercase() })

    override fun find(name: String): DavNode? =
        if (!Names.isSafe(name)) null else File(file, name).let { if (it.exists()) FileNode(it) else null }

    override fun createChild(name: String, directory: Boolean): DavNode? {
        if (!Names.isSafe(name)) return null
        val target = File(file, name)
        val ok = if (directory) target.mkdir() else runCatching { target.createNewFile() }.getOrDefault(false)
        return if (ok) FileNode(target) else null
    }

    override fun openInput(): InputStream = file.inputStream()
    override fun openOutput(append: Boolean): OutputStream =
        java.io.FileOutputStream(file, append).buffered(64 * 1024)

    override fun delete(): Boolean = if (isDirectory) file.deleteRecursively() else file.delete()

    override fun rename(newName: String): Boolean {
        if (!Names.isSafe(newName)) return false
        return file.renameTo(File(file.parentFile, newName))
    }
}

/**
 * SAF-backed node. Talks to DocumentsContract directly with a projection that
 * fetches every property PROPFIND needs in ONE provider query per directory,
 * instead of DocumentFile which issues a separate cross-process query per
 * property per file (catastrophic for folders with many entries).
 */
class SafNode private constructor(
    private val resolver: ContentResolver,
    private val docUri: Uri,
    private val treeUri: Uri,
    private val snapOrNull: Snap?,
    private val doc: DocumentFile? = null
) : DavNode {

    /** All PROPFIND-relevant properties, captured in a single query. */
    class Snap(
        val docId: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val lastModified: Long,
        val mime: String?
    )

    /** Root node built from a tree DocumentFile; properties are queried lazily. */
    constructor(resolver: ContentResolver, doc: DocumentFile) : this(
        resolver = resolver,
        docUri = doc.uri,
        // getTreeDocumentUri() is absent from newer API levels; rebuild from the id.
        treeUri = DocumentsContract.buildTreeDocumentUri(
            doc.uri.authority, DocumentsContract.getTreeDocumentId(doc.uri)
        ),
        snapOrNull = null,
        doc = doc
    )

    private val snap: Snap by lazy {
        snapOrNull ?: Snap(
            docId = runCatching { DocumentsContract.getDocumentId(docUri) }.getOrDefault(""),
            name = doc?.name ?: "",
            isDir = doc?.isDirectory ?: false,
            size = doc?.length() ?: 0L,
            lastModified = doc?.lastModified() ?: 0L,
            mime = doc?.type
        )
    }

    override val name: String get() = snap.name
    override val isDirectory: Boolean get() = snap.isDir
    override val size: Long get() = if (snap.isDir) 0L else snap.size
    override val lastModified: Long get() = snap.lastModified
    override val mimeType: String
        get() = if (snap.isDir) "httpd/unix-directory"
        else snap.mime?.takeIf { it != DocumentsContract.Document.MIME_TYPE_DIR } ?: Mime.guess(name)

    private val canWriteCached: Boolean by lazy {
        // Files: optimistic — real writes surface precise failures. Directories:
        // cheap flags check for create support (stable constant across APIs).
        if (!isDirectory) true
        else runCatching {
            resolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) true
                else {
                    val flags = c.getInt(0)
                    flags == 0 ||
                        flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
                }
            } ?: true
        }.getOrDefault(true)
    }

    override fun exists() = runCatching {
        resolver.query(
            docUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
        )?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    override fun writable() = canWriteCached

    override fun childCount(): Int = runCatching {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(docUri)
        )
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
        )?.use { it.count } ?: 0
    }.getOrDefault(0)

    override fun list(): List<DavNode> = runCatching {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(docUri)
        )
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
            val out = ArrayList<DavNode>(c.count)
            while (c.moveToNext()) {
                val snap = rowToSnap(c) ?: continue
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, snap.docId)
                out += SafNode(resolver, childUri, treeUri, snap)
            }
            out
        }
    }.getOrNull()
        ?.sortedWith(compareByDescending<DavNode> { it.isDirectory }.thenBy { it.name.lowercase() })
        ?: emptyList()

    override fun find(name: String): DavNode? {
        if (!Names.isSafe(name)) return null
        return runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getDocumentId(docUri)
            )
            resolver.query(
                childrenUri, PROJECTION,
                "${DocumentsContract.Document.COLUMN_DISPLAY_NAME}=?", arrayOf(name), null
            )?.use { c ->
                if (c.moveToFirst()) rowToSnap(c)?.let { s ->
                    SafNode(
                        resolver,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, s.docId),
                        treeUri, s
                    )
                } else null
            }
        }.getOrNull()
    }

    override fun createChild(name: String, directory: Boolean): DavNode? {
        if (!Names.isSafe(name)) return null
        return runCatching {
            val mime = if (directory) DocumentsContract.Document.MIME_TYPE_DIR else Mime.guess(name)
            val created = DocumentsContract.createDocument(resolver, docUri, mime, name)
                ?: return@runCatching null
            var node: DavNode = SafNode(resolver, created, treeUri, fetchSnap(created))
            // Providers may silently append an extension or a " (1)" suffix; force
            // the exact requested name, otherwise clients see a different URL.
            if (node.name != name) {
                val renamed = runCatching {
                    DocumentsContract.renameDocument(resolver, created, name)
                }.getOrNull()
                node = if (renamed != null) {
                    val snap = fetchSnap(renamed)
                    if (snap != null && snap.name == name) SafNode(resolver, renamed, treeUri, snap)
                    else find(name) ?: run {
                        runCatching { DocumentsContract.deleteDocument(resolver, renamed) }
                        return@runCatching null
                    }
                } else {
                    runCatching { DocumentsContract.deleteDocument(resolver, created) }
                    return@runCatching null
                }
            }
            node
        }.getOrNull()
    }

    override fun openInput(): InputStream =
        resolver.openInputStream(docUri) ?: throw IllegalStateException("Cannot open $name")

    override fun openOutput(append: Boolean): OutputStream =
        resolver.openOutputStream(docUri, if (append) "wa" else "wt")
            ?: throw IllegalStateException("Cannot write $name")

    override fun delete(): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, docUri) }.getOrDefault(false)

    override fun rename(newName: String): Boolean {
        if (!Names.isSafe(newName)) return false
        return runCatching {
            DocumentsContract.renameDocument(resolver, docUri, newName) != null
        }.getOrDefault(false)
    }

    private fun fetchSnap(uri: Uri): Snap? = runCatching {
        resolver.query(uri, PROJECTION, null, null, null)?.use { c ->
            if (c.moveToFirst()) rowToSnap(c) else null
        }
    }.getOrNull()

    val uri: Uri get() = docUri

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        private fun rowToSnap(c: android.database.Cursor): Snap? {
            val id = c.getString(0) ?: return null
            val displayName = c.getString(1) ?: return null
            val mime = c.getString(2)
            val size = if (c.isNull(3)) 0L else c.getLong(3)
            val modified = if (c.isNull(4)) 0L else c.getLong(4)
            return Snap(
                docId = id,
                name = displayName,
                isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                size = size,
                lastModified = modified,
                mime = mime
            )
        }
    }
}
