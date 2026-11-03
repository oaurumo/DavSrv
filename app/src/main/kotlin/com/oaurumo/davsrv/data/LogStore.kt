package com.oaurumo.davsrv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** In-memory request log (ring buffer, no disk I/O on the request path). */
object LogStore {
    private const val CAPACITY = 300
    private val nextId = AtomicLong(0)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun add(method: String, path: String, user: String, status: Int, bytes: Long) {
        val entry = LogEntry(nextId.incrementAndGet(), System.currentTimeMillis(), method, path, user, status, bytes)
        val updated = ArrayList<LogEntry>(minOf(_entries.value.size + 1, CAPACITY))
        updated.add(0, entry)
        val old = _entries.value
        for (i in 0 until minOf(old.size, CAPACITY - 1)) updated.add(old[i])
        _entries.value = updated
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
