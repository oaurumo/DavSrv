package com.oaurumo.davsrv.server

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises the server on the local network via mDNS (Bonjour / zeroconf):
 *
 *  - an A record for `<label>.local`, so `http://<label>.local:<port>` works in
 *    any mDNS capable client (Windows 10+, macOS, iOS, Linux with Avahi);
 *  - a `_webdav._tcp` and a `_http._tcp` service, so the server shows up in
 *    Bonjour browsers and Finder's shared sidebar.
 *
 * All socket work runs on a daemon thread — JmDNS.create and registerService
 * block for hundreds of milliseconds and must stay off the main thread. A
 * generation counter makes a stop() during registration a no-op.
 */
object MdnsAnnouncer {

    private const val TYPE_WEBDAV = "_webdav._tcp.local."
    private const val TYPE_HTTP = "_http._tcp.local."
    private const val FALLBACK_LABEL = "davsrv"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Called with the live full host, or null on failure. Fired on a worker thread. */
    @Volatile
    var onAnnounced: ((String?) -> Unit)? = null

    /** Full host currently announced (may lag a second behind start()), or null. */
    @Volatile
    var activeLabel: String? = null
        private set

    private val lock = Any()
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var generation = 0

    /**
     * Registers the host name and the two services on [ip]. Returns
     * immediately; [onAnnounced] reports the outcome. A null return label
     * means the server keeps running — mDNS is a convenience, not a
     * requirement.
     */
    fun start(label: String, ip: InetAddress, port: Int) {
        synchronized(lock) {
            stopLocked()
            generation += 1
            val gen = generation
            val safeLabel = sanitize(label)
            val host = safeLabel + ".local"
            acquireMulticastLock()
            Thread {
                var created: JmDNS? = null
                try {
                    val mdns = JmDNS.create(ip, host)
                    created = mdns
                    mdns.registerService(
                        ServiceInfo.create(TYPE_WEBDAV, safeLabel, port, 0, 0, "path=/")
                    )
                    mdns.registerService(
                        ServiceInfo.create(TYPE_HTTP, safeLabel, port, 0, 0, "path=/")
                    )
                    synchronized(lock) {
                        if (gen == generation) {
                            jmdns = mdns
                            activeLabel = host
                            onAnnounced?.invoke(host)
                        }
                    }
                    if (gen != generation) {
                        // Stopped while we were probing — undo immediately.
                        runCatching { mdns.unregisterAllServices() }
                        runCatching { mdns.close() }
                    }
                } catch (t: Throwable) {
                    runCatching { created?.close() }
                    synchronized(lock) {
                        if (gen == generation) onAnnounced?.invoke(null)
                    }
                }
            }.apply {
                name = "DavSrv-mdns"
                isDaemon = true
                start()
            }
        }
    }

    /** Safe to call from any thread, any number of times. */
    fun stop() {
        synchronized(lock) {
            stopLocked()
            generation += 1
        }
    }

    private fun stopLocked() {
        activeLabel = null
        val current = jmdns
        jmdns = null
        if (current != null) {
            Thread {
                runCatching { current.unregisterAllServices() }
                runCatching { current.close() }
            }.apply {
                name = "DavSrv-mdns-stop"
                isDaemon = true
                start()
            }
        }
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null || !::appContext.isInitialized) return
        multicastLock = runCatching {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.createMulticastLock("DavSrv:mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
    }

    /**
     * Keeps letters (any script) and digits, turns separators into '-' and
     * drops everything else, then caps the UTF-8 length under DNS's 63-octet
     * label limit.
     */
    fun sanitize(raw: String): String {
        val sb = StringBuilder()
        raw.forEach { c ->
            when {
                c.isLetterOrDigit() -> sb.append(c)
                c == ' ' || c == '-' || c == '_' || c == '.' -> sb.append('-')
                else -> { /* dropped */ }
            }
        }
        var label = sb.toString().trim('-')
        if (label.isEmpty()) return FALLBACK_LABEL
        // 55 UTF-8 bytes leaves room for ".local" plus DNS overhead.
        while (label.toByteArray(Charsets.UTF_8).size > 55) {
            label = label.dropLast(1).trimEnd('-')
            if (label.isEmpty()) return FALLBACK_LABEL
        }
        return label
    }
}
