package com.oaurumo.davsrv.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oaurumo.davsrv.LocaleHelper
import com.oaurumo.davsrv.data.AuthUser
import com.oaurumo.davsrv.data.LogStore
import com.oaurumo.davsrv.data.Passwords
import com.oaurumo.davsrv.data.Repo
import com.oaurumo.davsrv.server.DavNode
import com.oaurumo.davsrv.server.Names
import com.oaurumo.davsrv.server.Storage
import com.oaurumo.davsrv.server.WebDavServer
import com.oaurumo.davsrv.service.WebDavService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.oaurumo.davsrv.R
data class FileItem(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long
)

data class FilesState(
    val path: List<String> = emptyList(),
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val settings = Repo.settings
    val users = Repo.users
    val logs = LogStore.entries
    val server = WebDavServer.state

    private val _files = MutableStateFlow(FilesState(loading = true))
    val files = _files.asStateFlow()

    init {
        refreshFiles()
    }

    // ------------------------------------------------------------ server control

    fun startServer() {
        WebDavServer.clearError()
        WebDavService.start(getApplication())
    }

    fun stopServer() {
        WebDavService.stop(getApplication())
    }

    fun toggleServer(running: Boolean) {
        if (running) stopServer() else startServer()
    }

    fun refreshIp() = WebDavServer.refreshIp()

    // ------------------------------------------------------------------ settings

    fun savePort(port: Int) = Repo.run {
        Repo.setPort(port)
        if (WebDavServer.isRunning) {
            WebDavService.stop(getApplication())
            kotlinx.coroutines.delay(400)
            WebDavService.start(getApplication())
        }
    }

    fun useTreeUri(uri: Uri) {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        Repo.run {
            Repo.setTreeUri(uri.toString())
            refreshFiles(emptyList())
        }
    }

    fun usePathRoot(path: String) = Repo.run {
        Repo.setRootPath(path.trim())
        refreshFiles(emptyList())
    }

    fun setShowHidden(value: Boolean) = Repo.run {
        Repo.setShowHidden(value)
        refreshFiles(_files.value.path)
    }

    fun setBootStart(value: Boolean) = Repo.run { Repo.setBootStart(value) }
    fun setWifiLock(value: Boolean) = Repo.run { Repo.setWifiLock(value) }

    /** Runs a create/rename/delete probe so the user can verify write access on device. */
    fun selfTest(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                Storage.selfTest(getApplication(), Repo.settings.value)
            }
            onResult(result)
        }
    }

    // --------------------------------------------------------------------- users

    fun saveUser(originalName: String?, username: String, password: String?, readOnly: Boolean, onError: (String) -> Unit) {
        val name = username.trim()
        if (name.isEmpty() || name.contains(':')) {
            onError(text(R.string.err_username_empty_colon))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val existing = Repo.users.value.firstOrNull { it.username == originalName }
            val salt = existing?.salt ?: Passwords.newSalt()
            val hash = when {
                !password.isNullOrEmpty() -> Passwords.hash(password, salt)
                existing != null -> existing.hash
                else -> {
                    withContext(Dispatchers.Main) { onError(text(R.string.err_password_required)) }
                    return@launch
                }
            }
            Repo.upsertUser(AuthUser(name, salt, hash, readOnly))
            if (originalName != null && originalName != name) Repo.deleteUser(originalName)
        }
    }

    fun deleteUser(username: String) = Repo.run { Repo.deleteUser(username) }

    fun clearLogs() = LogStore.clear()

    // --------------------------------------------------------------------- files

    fun refreshFiles(path: List<String> = _files.value.path) {
        viewModelScope.launch {
            _files.value = _files.value.copy(path = path, loading = true, error = null)
            val result = withContext(Dispatchers.IO) { loadDirectory(path) }
            _files.value = if (result.first != null) {
                FilesState(path = path, items = result.second, loading = false, error = result.first)
            } else {
                FilesState(path = path, items = result.second, loading = false)
            }
        }
    }

    private fun loadDirectory(path: List<String>): Pair<String?, List<FileItem>> {
        val context = getApplication<Application>()
        val root = Storage.openRoot(context, Repo.settings.value)
            ?: return text(R.string.err_root_unavailable_check) to emptyList()
        val node = Names.resolve(root, path) ?: return text(R.string.err_dir_missing) to emptyList()
        if (!node.isDirectory) return text(R.string.err_not_a_dir) to emptyList()
        return try {
            val showHidden = Repo.settings.value.showHidden
            val items = node.list()
                .filter { showHidden || !Names.isHidden(it.name) }
                .map { FileItem(it.name, it.isDirectory, it.size, it.lastModified) }
            null to items
        } catch (t: Throwable) {
            (t.message ?: text(R.string.err_read_failed)) to emptyList()
        }
    }

    fun openFolder(name: String) = refreshFiles(_files.value.path + name)

    fun goUp() {
        val path = _files.value.path
        if (path.isNotEmpty()) refreshFiles(path.dropLast(1))
    }

    fun createFolder(name: String, onResult: (String?) -> Unit) {
        val target = name.trim()
        if (!Names.isSafe(target)) {
            onResult(text(R.string.err_name_invalid))
            return
        }
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val root = Storage.openRoot(app, Repo.settings.value)
                    ?: return@withContext text(R.string.err_root_unavailable)
                val dir = Names.resolve(root, _files.value.path) ?: return@withContext text(R.string.err_dir_missing)
                if (dir.find(target) != null) return@withContext text(R.string.err_exists)
                if (dir.createChild(target, true) == null) text(R.string.err_create_failed) else null
            }
            refreshFiles()
            onResult(error)
        }
    }

    fun deleteEntry(name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val root = Storage.openRoot(app, Repo.settings.value)
                    ?: return@withContext text(R.string.err_root_unavailable)
                val dir = Names.resolve(root, _files.value.path) ?: return@withContext text(R.string.err_dir_missing)
                val node: DavNode = dir.find(name) ?: return@withContext text(R.string.err_entry_missing)
                if (node.delete()) null else text(R.string.err_delete_failed)
            }
            refreshFiles()
            onResult(error)
        }
    }

    fun rootLabel(): String {
        val s = Repo.settings.value
        return if (s.useSaf) s.treeUri else s.rootPath.ifBlank { Repo.defaultRootPath }
    }

    /**
     * Copy resolved in the language the user picked. The plain application
     * context keeps whatever locale the process started with, so it would go
     * stale as soon as the language is switched.
     */
    private fun text(@StringRes id: Int, vararg args: Any): String {
        val ctx = LocaleHelper.wrap(getApplication())
        return if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
    }
}
