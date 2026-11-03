package com.oaurumo.davsrv.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single source of truth shared by the UI, the foreground service and the HTTP
 * server. Settings/users are mirrored from DataStore into hot StateFlows so the
 * request path never blocks on disk.
 */
object Repo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsStore: SettingsStore
    private lateinit var userStore: UserStore

    @Volatile
    private var initialized = false

    val settings = MutableStateFlow(AppSettings())
    val users = MutableStateFlow<List<AuthUser>>(emptyList())

    val defaultRootPath: String
        get() = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        settingsStore = SettingsStore(app)
        userStore = UserStore(app)

        scope.launch {
            settingsStore.ensureMdnsLabel()
            settingsStore.flow.collect { s ->
                settings.value = if (s.rootPath.isBlank()) s.copy(rootPath = defaultRootPath) else s
            }
        }
        scope.launch { userStore.flow.collect { users.value = it } }
        scope.launch {
            userStore.ensureDefault()
            ensureRootFolder()
        }
    }

    private suspend fun ensureRootFolder() {
        val s = settings.value
        if (!s.useSaf && s.rootPath.isNotBlank()) {
            runCatching { File(s.rootPath).mkdirs() }
        }
    }

    suspend fun setPort(port: Int) = settingsStore.setPort(port)
    suspend fun setRootPath(path: String) = settingsStore.setRootPath(path)
    suspend fun setTreeUri(uri: String) = settingsStore.setTreeUri(uri)
    suspend fun setShowHidden(v: Boolean) = settingsStore.setShowHidden(v)
    suspend fun setBootStart(v: Boolean) = settingsStore.setBootStart(v)
    suspend fun setWifiLock(v: Boolean) = settingsStore.setWifiLock(v)
    suspend fun setKeepAwake(v: Boolean) = settingsStore.setKeepAwake(v)
    suspend fun setMdnsEnabled(v: Boolean) = settingsStore.setMdnsEnabled(v)
    suspend fun setMdnsHost(v: String) = settingsStore.setMdnsHost(v)
    suspend fun setMdnsBindIp(v: String) = settingsStore.setMdnsBindIp(v)
    suspend fun setGuideHidden(v: Boolean) = settingsStore.setGuideHidden(v)

    suspend fun upsertUser(user: AuthUser) = userStore.upsert(user)
    suspend fun deleteUser(username: String) = userStore.delete(username)

    fun run(block: suspend CoroutineScope.() -> Unit) {
        scope.launch { block() }
    }
}
