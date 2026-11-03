package com.oaurumo.davsrv.data

/** Persisted user settings. Empty [treeUri] means path based storage access. */
data class AppSettings(
    val port: Int = 8080,
    val rootPath: String = "",
    val treeUri: String = "",
    val showHidden: Boolean = false,
    val bootStart: Boolean = false,
    val wifiLock: Boolean = false,
    val keepScreenOn: Boolean = false,
    val readOnlyGuest: Boolean = false,
    val mdnsEnabled: Boolean = true,
    /** mDNS host label; seeded once with two random lowercase letters. */
    val mdnsHost: String = "",
    /** NIC IP the mDNS record binds to; blank = best interface (auto). */
    val mdnsBindIp: String = "",
    /** Whether the home-screen "How to connect" card is collapsed. */
    val guideHidden: Boolean = false
) {
    val useSaf: Boolean get() = treeUri.isNotBlank()
}

/** A credentials record; the password itself is never stored. */
data class AuthUser(
    val username: String,
    val salt: String,
    val hash: String,
    val readOnly: Boolean = false
)

/** One served request, kept in a small in-memory ring buffer. */
data class LogEntry(
    val id: Long,
    val time: Long,
    val method: String,
    val path: String,
    val user: String,
    val status: Int,
    val bytes: Long
)

/** Live server runtime state, observed by the UI and the notification. */
data class ServerState(
    val running: Boolean = false,
    val port: Int = 8080,
    val ip: String? = null,
    /** Full mDNS host currently announced, e.g. "living-room.local"; null while off. */
    val mdnsHost: String? = null,
    val startedAt: Long = 0L,
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L,
    val requests: Long = 0L,
    val error: String? = null
) {
    val address: String get() = ip?.let { "http://$it:$port" } ?: "—"
    /** http://<host>:<port> once the mDNS name is registered. */
    val localAddress: String? get() = mdnsHost?.let { "http://$it:$port" }
    val uptimeMillis: Long get() = if (running && startedAt > 0) System.currentTimeMillis() - startedAt else 0L
}
