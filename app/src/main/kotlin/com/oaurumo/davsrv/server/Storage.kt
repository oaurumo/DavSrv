package com.oaurumo.davsrv.server

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.oaurumo.davsrv.R
import com.oaurumo.davsrv.data.AppSettings
import java.io.File

/** Root resolution plus storage-permission helpers that work from API 23 to 36. */
object Storage {

    fun openRoot(context: Context, settings: AppSettings): DavNode? {
        if (settings.useSaf) {
            val uri = runCatching { Uri.parse(settings.treeUri) }.getOrNull() ?: return null
            val doc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return null
            return if (doc.exists()) SafNode(context.contentResolver, doc) else null
        }
        val path = settings.rootPath.ifBlank { Environment.getExternalStorageDirectory().absolutePath }
        val dir = File(path)
        if (!dir.exists()) runCatching { dir.mkdirs() }
        return if (dir.isDirectory) FileNode(dir) else null
    }

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun hasLegacyStorage(context: Context): Boolean {
        val read = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return read
        val write = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    /** Whether the configured path root can actually be read/written. */
    fun canUsePath(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) hasAllFilesAccess()
        else hasLegacyStorage(context)

    fun allFilesIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val pkgUri = Uri.parse("package:${context.packageName}")
        val direct = Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", pkgUri)
        return if (direct.resolveActivity(context.packageManager) != null) direct
        else Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION")
    }

    /** Free/total bytes of the volume hosting [node], when it can be determined. */
    fun spaceOf(node: DavNode?): Pair<Long, Long>? {
        val path = (node as? FileNode)?.file?.absolutePath ?: return null
        return runCatching {
            val stat = StatFs(path)
            stat.availableBytes to stat.totalBytes
        }.getOrNull()
    }

    // ------------------------------------------------------------------ access

    /** Coarse read/write capability of the configured root, for UI and server checks. */
    enum class StorageAccess { ReadWrite, ReadOnly, Unavailable }

    fun accessState(context: Context, settings: AppSettings): StorageAccess {
        return if (settings.useSaf) {
            val doc = runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(settings.treeUri))
            }.getOrNull()
            when {
                doc == null || !doc.exists() -> StorageAccess.Unavailable
                doc.canWrite() -> StorageAccess.ReadWrite
                else -> StorageAccess.ReadOnly
            }
        } else {
            val dir = File(settings.rootPath.ifBlank { Environment.getExternalStorageDirectory().absolutePath })
            when {
                !canUsePath(context) -> StorageAccess.Unavailable
                !dir.isDirectory -> StorageAccess.Unavailable
                dir.canWrite() -> StorageAccess.ReadWrite
                else -> StorageAccess.ReadOnly
            }
        }
    }

    /**
     * Proves the root really supports the operations WebDAV clients need:
     * create, write, rename, read back and delete. Returns null on success,
     * otherwise a short reason suitable for a toast.
     */
    fun selfTest(context: Context, settings: AppSettings): String? {
        val root = openRoot(context, settings) ?: return ServerText.s(R.string.srv_selftest_no_root)
        val probe = ".davsrv-probe-" + System.currentTimeMillis()
        val payload = "davsrv-self-test".toByteArray(Charsets.UTF_8)
        val created = root.createChild(probe, false) ?: return ServerText.s(R.string.srv_selftest_no_create)
        var renamed: DavNode? = null
        try {
            runCatching {
                created.openOutput(append = false).use { it.write(payload) }
            }.onFailure {
                val reason = it.message ?: ServerText.s(R.string.srv_selftest_denied)
                return ServerText.s(R.string.srv_selftest_write_failed, reason)
            }

            val renamedName = probe + "-r"
            if (!created.rename(renamedName)) return ServerText.s(R.string.srv_selftest_no_rename)
            renamed = root.find(renamedName) ?: return ServerText.s(R.string.srv_selftest_rename_lost)
            val readBack = runCatching {
                renamed!!.openInput().use { it.readBytes() }
            }.getOrNull() ?: return ServerText.s(R.string.srv_selftest_read_failed)
            if (!readBack.contentEquals(payload)) {
                return ServerText.s(
                    R.string.srv_selftest_mismatch, payload.size, readBack.size
                )
            }
            return null
        } finally {
            runCatching { created.delete() }
            runCatching { renamed?.delete() }
        }
    }
}
