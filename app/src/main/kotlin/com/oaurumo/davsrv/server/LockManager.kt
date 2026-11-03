package com.oaurumo.davsrv.server

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class DavLock(
    val token: String,
    val path: String,
    val depth: String,
    val owner: String,
    val expiresAt: Long
)

/**
 * Minimal RFC 4918 exclusive-write lock manager. Locks are in memory only,
 * which is what single-device clients (Windows, macOS, Office) expect.
 */
class LockManager {
    private val locks = ConcurrentHashMap<String, DavLock>()

    fun lock(path: String, depth: String, owner: String, timeoutSeconds: Long): DavLock {
        val token = "opaquelocktoken:" + UUID.randomUUID().toString()
        val lock = DavLock(token, path, depth, owner, System.currentTimeMillis() + timeoutSeconds * 1000)
        locks[token] = lock
        return lock
    }

    fun refresh(token: String, timeoutSeconds: Long): DavLock? {
        val existing = locks[token] ?: return null
        val updated = existing.copy(expiresAt = System.currentTimeMillis() + timeoutSeconds * 1000)
        locks[token] = updated
        return updated
    }

    fun unlock(token: String): Boolean = locks.remove(token) != null

    fun active(token: String): DavLock? {
        val l = locks[token] ?: return null
        if (l.expiresAt < System.currentTimeMillis()) {
            locks.remove(token)
            return null
        }
        return l
    }

    /** True when [path] is covered by a lock whose token is not in [ifHeader]. */
    fun conflicts(path: String, ifHeader: String?): Boolean {
        purgeExpired()
        return locks.values.any { lock ->
            if (lock.token in (ifHeader ?: "")) return@any false
            path == lock.path || path.startsWith(lock.path.trimEnd('/') + "/") ||
                (lock.depth == "infinity" && lock.path.startsWith(path.trimEnd('/') + "/"))
        }
    }

    fun locksFor(path: String): List<DavLock> {
        purgeExpired()
        return locks.values.filter { it.path == path }
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        locks.entries.removeIf { it.value.expiresAt < now }
    }

    fun clear() = locks.clear()
}
