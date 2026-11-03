package com.oaurumo.davsrv.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val Context.settingsDataStore by preferencesDataStore(name = "davsrv_settings")
internal val Context.usersDataStore by preferencesDataStore(name = "davsrv_users")

class SettingsStore(private val context: Context) {

    private object K {
        val PORT = intPreferencesKey("port")
        val ROOT_PATH = stringPreferencesKey("root_path")
        val TREE_URI = stringPreferencesKey("tree_uri")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val BOOT_START = booleanPreferencesKey("boot_start")
        val WIFI_LOCK = booleanPreferencesKey("wifi_lock")
        val KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        val READ_ONLY_GUEST = booleanPreferencesKey("read_only_guest")
        val MDNS_ENABLED = booleanPreferencesKey("mdns_enabled")
        val MDNS_HOST = stringPreferencesKey("mdns_host")
        val MDNS_BIND_IP = stringPreferencesKey("mdns_bind_ip")
        val GUIDE_HIDDEN = booleanPreferencesKey("guide_hidden")
    }

    companion object {
        /** Two random lowercase letters, e.g. "qx" — the default .local label. */
        fun randomLabel(): String {
            val a = 'a' + (Math.random() * 26).toInt()
            val b = 'a' + (Math.random() * 26).toInt()
            return "$a$b"
        }
    }

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        port = this[K.PORT] ?: 8080,
        rootPath = this[K.ROOT_PATH] ?: "",
        treeUri = this[K.TREE_URI] ?: "",
        showHidden = this[K.SHOW_HIDDEN] ?: false,
        bootStart = this[K.BOOT_START] ?: false,
        wifiLock = this[K.WIFI_LOCK] ?: false,
        keepScreenOn = this[K.KEEP_AWAKE] ?: false,
        readOnlyGuest = this[K.READ_ONLY_GUEST] ?: false,
        mdnsEnabled = this[K.MDNS_ENABLED] ?: true,
        mdnsHost = this[K.MDNS_HOST] ?: "",
        mdnsBindIp = this[K.MDNS_BIND_IP] ?: "",
        guideHidden = this[K.GUIDE_HIDDEN] ?: false
    )

    suspend fun setPort(port: Int) = edit { it[K.PORT] = port.coerceIn(1024, 65535) }
    suspend fun setRootPath(path: String) = edit {
        it[K.ROOT_PATH] = path
        it.remove(K.TREE_URI)
    }
    suspend fun setTreeUri(uri: String) = edit { it[K.TREE_URI] = uri }
    suspend fun setShowHidden(v: Boolean) = edit { it[K.SHOW_HIDDEN] = v }
    suspend fun setBootStart(v: Boolean) = edit { it[K.BOOT_START] = v }
    suspend fun setWifiLock(v: Boolean) = edit { it[K.WIFI_LOCK] = v }
    suspend fun setKeepAwake(v: Boolean) = edit { it[K.KEEP_AWAKE] = v }
    suspend fun setReadOnlyGuest(v: Boolean) = edit { it[K.READ_ONLY_GUEST] = v }
    suspend fun setMdnsEnabled(v: Boolean) = edit { it[K.MDNS_ENABLED] = v }
    suspend fun setMdnsHost(v: String) = edit { it[K.MDNS_HOST] = v.trim() }
    suspend fun setMdnsBindIp(v: String) = edit { it[K.MDNS_BIND_IP] = v.trim() }
    suspend fun setGuideHidden(v: Boolean) = edit { it[K.GUIDE_HIDDEN] = v }

    /**
     * Seeds the stable random 2-letter mDNS label on first launch. Done as a
     * write (not just a default) so the label survives restarts and is shared
     * by every reader without re-randomizing.
     */
    suspend fun ensureMdnsLabel() = context.settingsDataStore.edit { prefs ->
        if (prefs[K.MDNS_HOST] == null) prefs[K.MDNS_HOST] = randomLabel()
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { block(it) }
    }
}
