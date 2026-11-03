package com.oaurumo.davsrv.server

import android.content.Context
import android.util.Base64
import com.oaurumo.davsrv.R
import com.oaurumo.davsrv.data.AppSettings
import com.oaurumo.davsrv.data.AuthUser
import com.oaurumo.davsrv.data.LogStore
import com.oaurumo.davsrv.data.Passwords
import com.oaurumo.davsrv.data.Repo
import com.oaurumo.davsrv.data.ServerState
import com.oaurumo.davsrv.data.SettingsStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import java.net.InetSocketAddress
import java.net.ServerSocket
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Embedded WebDAV server. One Ktor CIO engine, one catch-all router, stateless
 * handlers; settings and users are read live from [Repo] so changes apply
 * without restarting the listener.
 */
object WebDavServer {

    private var engine: ApplicationEngine? = null
    private lateinit var appContext: Context
    private val lockManager = LockManager()
    private val bytesIn = AtomicLong(0)
    private val bytesOut = AtomicLong(0)
    private val requests = AtomicLong(0)
    private var lastPublish = 0L

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val handlers = DavHandlers(
        lockManager = lockManager,
        onBytesIn = { addIn(it) },
        onBytesOut = { addOut(it) }
    )

    private val authCache = ConcurrentHashMap<String, Long>()


    fun init(context: Context) {
        appContext = context.applicationContext
        ServerText.init(context)
        MdnsAnnouncer.init(context)
        MdnsAnnouncer.onAnnounced = { label ->
            // null means registration failed; the IP URL keeps working either way.
            if (isRunning) _state.value = _state.value.copy(mdnsHost = label)
        }
    }

    val isRunning: Boolean get() = engine != null

    @Synchronized
    fun start(port: Int): Result<Unit> {
        if (engine != null) return Result.success(Unit)
        val safePort = port.coerceIn(1024, 65535)

        // CIO surfaces bind failures (port busy, or ports below 1024 which
        // unprivileged Android apps may not claim) asynchronously inside the
        // engine - an unhandled BindException there kills the whole process.
        // Probe the port synchronously first and turn failure into a clean,
        // localized error instead of a crash.
        val probe = runCatching {
            ServerSocket().use { ss ->
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", safePort))
            }
        }
        if (probe.isFailure) {
            val msg = ServerText.s(R.string.srv_err_port_bind, safePort)
            _state.value = _state.value.copy(running = false, error = msg)
            return Result.failure(probe.exceptionOrNull() ?: IllegalStateException(msg))
        }

        return runCatching {
            val instance = embeddedServer(CIO, port = safePort, host = "0.0.0.0") {
                configureRouting()
            }
            instance.start(wait = false)
            engine = instance
            bytesIn.set(0)
            bytesOut.set(0)
            requests.set(0)
            _state.value = ServerState(
                running = true,
                port = safePort,
                ip = NetworkUtils.localIpv4(),
                startedAt = System.currentTimeMillis()
            )
            announceMdns()
        }.onFailure { t ->
            engine = null
            _state.value = _state.value.copy(running = false, error = t.message ?: ServerText.s(R.string.srv_err_start_failed))
        }
    }

    @Synchronized
    fun stop() {
        MdnsAnnouncer.stop()
        runCatching { engine?.stop(300, 800) }
        engine = null
        lockManager.clear()
        authCache.clear()
        _state.value = ServerState(running = false, port = _state.value.port)
    }

    fun refreshIp() {
        if (!_state.value.running) return
        _state.value = _state.value.copy(ip = NetworkUtils.localIpv4())
        // The A record binds to the interface address: re-announce when the
        // phone moved to another network or the bound NIC disappeared.
        applyMdns(Repo.settings.value)
    }

    /**
     * Re-evaluates the mDNS announcement after a settings change. Callers MUST
     * pass the new settings: [Repo.settings] is updated by an asynchronous
     * DataStore collector, so reading it here would see the previous values
     * (that is why a saved custom domain once never took effect).
     */
    fun applyMdnsSettings(settings: AppSettings) = applyMdns(settings)

    private fun applyMdns(settings: AppSettings) {
        if (engine == null || !settings.mdnsEnabled) {
            MdnsAnnouncer.stop()
            _state.value = _state.value.copy(mdnsHost = null)
            return
        }
        val nics = NetworkUtils.allIps()
        val ip = when {
            // Pinned NIC: use it while it still exists, fall back to auto.
            settings.mdnsBindIp.isNotBlank() -> nics.firstOrNull { it.ip == settings.mdnsBindIp }?.ip
            else -> nics.firstOrNull()?.ip
        }
        if (ip == null) {
            MdnsAnnouncer.stop()
            _state.value = _state.value.copy(mdnsHost = null)
            return
        }
        val label = settings.mdnsHost.ifBlank { SettingsStore.randomLabel() }
        MdnsAnnouncer.start(label, InetAddress.getByName(ip), settings.port)
    }

    private fun announceMdns() = applyMdns(Repo.settings.value)

    fun clearError() {
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
    }

    private fun addIn(n: Long) {
        if (n <= 0) return
        val total = bytesIn.addAndGet(n)
        publish(bytesIn = total)
    }

    private fun addOut(n: Long) {
        if (n <= 0) return
        val total = bytesOut.addAndGet(n)
        publish(bytesOut = total)
    }

    private fun publish(bytesIn: Long? = null, bytesOut: Long? = null) {
        val now = System.currentTimeMillis()
        if (now - lastPublish < 250) return
        lastPublish = now
        val current = _state.value
        _state.value = current.copy(
            bytesIn = bytesIn ?: current.bytesIn,
            bytesOut = bytesOut ?: current.bytesOut
        )
    }

    private fun Application.configureRouting() {
        routing {
            METHODS.forEach { method ->
                route("{...}", method) { handle { dispatch(call) } }
            }
        }
    }

    private suspend fun dispatch(call: ApplicationCall) {
        val method = call.request.httpMethod.value.uppercase()
        val rawPath = call.request.path()
        val segments = Names.split(decodePath(rawPath))

        // Unauthenticated probes: OPTIONS is what every client uses to detect DAV.
        if (method == "OPTIONS") {
            val status = handlers.options(call)
            LogStore.add(method, rawPath, "—", status.value, 0)
            return
        }
        // Browsers ask for this on every page load; do not trigger a 401 dialog.
        if (rawPath == "/favicon.ico" || rawPath == "/favicon.png") {
            call.respondText("", status = HttpStatusCode.NoContent)
            return
        }

        val user = authenticate(call)
        if (user == null) {
            challenge(call)
            LogStore.add(method, rawPath, "—", 401, 0)
            return
        }

        if (method == "POST") {
            call.respondText(ServerText.s(R.string.srv_err_method), status = HttpStatusCode.MethodNotAllowed)
            LogStore.add(method, rawPath, user.username, 405, 0)
            return
        }

        val beforeIn = bytesIn.get()
        val beforeOut = bytesOut.get()
        val settings = Repo.settings.value
        val root = Storage.openRoot(appContext, settings)

        var status = HttpStatusCode.InternalServerError
        var note = ""
        try {
            if (root == null) {
                note = ServerText.s(R.string.srv_err_root_unavailable_short)
                call.respondText(
                    ServerText.s(R.string.srv_err_root_unavailable),
                    status = HttpStatusCode.InternalServerError
                )
            } else {
                status = route(call, method, segments, root, user, settings)
            }
        } catch (t: Throwable) {
            note = t.message ?: t.javaClass.simpleName
            runCatching {
                call.respondText(
                    ServerText.s(R.string.srv_err_internal, note),
                    status = HttpStatusCode.InternalServerError
                )
            }
            status = HttpStatusCode.InternalServerError
        }

        requests.incrementAndGet()
        publish(bytesIn = bytesIn.get(), bytesOut = bytesOut.get())
        _state.value = _state.value.copy(requests = requests.get())
        LogStore.add(
            method = method,
            path = rawPath,
            user = user.username,
            status = status.value,
            bytes = (bytesIn.get() - beforeIn) + (bytesOut.get() - beforeOut)
        )
    }

    private suspend fun route(
        call: ApplicationCall,
        method: String,
        segments: List<String>,
        root: DavNode,
        user: AuthUser,
        settings: AppSettings
    ): HttpStatusCode {
        val node = Names.resolve(root, segments)
        val name = segments.lastOrNull() ?: ""
        // Resolving the parent costs a full directory listing on SAF; only the
        // methods that actually need it (PUT, MKCOL) should pay for it.
        val parent: DavNode? by lazy {
            if (segments.isEmpty()) null else Names.resolve(root, segments.dropLast(1))
        }
        val path = "/" + segments.joinToString("/")
        val includeHidden = settings.showHidden

        if (method in WRITE_METHODS && user.readOnly) {
            call.respondText(ServerText.s(R.string.srv_err_readonly_account), status = HttpStatusCode.Forbidden)
            return HttpStatusCode.Forbidden
        }
        if (method in WRITE_METHODS && !root.writable()) {
            val hint = if (settings.useSaf)
                ServerText.s(R.string.srv_hint_saf_readonly)
            else
                ServerText.s(R.string.srv_hint_path_readonly)
            call.respondText(hint, status = HttpStatusCode.Forbidden)
            return HttpStatusCode.Forbidden
        }
        // UNLOCK must bypass the lock-conflict check: it is the way out of a lock.
        if (method in WRITE_METHODS && method != "UNLOCK" &&
            lockManager.conflicts(path, call.request.headers[DavHeaders.If])
        ) {
            call.respondText(ServerText.s(R.string.srv_err_locked), status = DavStatus.Locked)
            return DavStatus.Locked
        }

        return when (method) {
            "PROPFIND" -> {
                if (node == null) {
                    call.respondXml(notFoundXml(segments), HttpStatusCode.NotFound)
                    HttpStatusCode.NotFound
                } else handlers.propfind(call, node, segments, includeHidden)
            }
            "GET", "HEAD" -> {
                if (node == null) {
                    call.respondText(ServerText.s(R.string.srv_err_not_found), status = HttpStatusCode.NotFound)
                    HttpStatusCode.NotFound
                } else handlers.get(call, node, segments, method == "HEAD", includeHidden)
            }
            "PUT" -> {
                if (segments.isEmpty()) {
                    call.respondText(ServerText.s(R.string.srv_err_put_root), status = HttpStatusCode.MethodNotAllowed)
                    HttpStatusCode.MethodNotAllowed
                } else if (parent == null) {
                    call.respondText(ServerText.s(R.string.srv_err_parent_missing), status = HttpStatusCode.Conflict)
                    HttpStatusCode.Conflict
                } else handlers.put(call, node, parent, name)
            }
            "DELETE" -> when {
                node == null -> {
                    call.respondText(ServerText.s(R.string.srv_err_not_found), status = HttpStatusCode.NotFound)
                    HttpStatusCode.NotFound
                }
                segments.isEmpty() -> {
                    call.respondText(ServerText.s(R.string.srv_err_delete_root), status = HttpStatusCode.Forbidden)
                    HttpStatusCode.Forbidden
                }
                else -> handlers.delete(call, node)
            }
            "MKCOL" -> handlers.mkcol(call, parent, name, node != null)
            "COPY", "MOVE" -> when {
                node == null -> {
                    call.respondText(ServerText.s(R.string.srv_err_not_found), status = HttpStatusCode.NotFound)
                    HttpStatusCode.NotFound
                }
                segments.isEmpty() -> {
                    call.respondText(ServerText.s(R.string.srv_err_transfer_root), status = HttpStatusCode.Forbidden)
                    HttpStatusCode.Forbidden
                }
                else -> handlers.copyOrMove(call, root, node, segments, method == "MOVE", includeHidden)
            }
            "PROPPATCH" -> if (node == null) {
                call.respondText(ServerText.s(R.string.srv_err_not_found), status = HttpStatusCode.NotFound)
                HttpStatusCode.NotFound
            } else handlers.proppatch(call, node, segments)
            "LOCK" -> handlers.lock(call, segments)
            "UNLOCK" -> handlers.unlock(call)
            else -> {
                call.respondText(ServerText.s(R.string.srv_err_method), status = HttpStatusCode.MethodNotAllowed)
                HttpStatusCode.MethodNotAllowed
            }
        }
    }

    private fun notFoundXml(segments: List<String>): String =
        """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>${Xml.escape(Xml.href(segments, false))}</D:href>
    <D:status>HTTP/1.1 404 Not Found</D:status>
  </D:response>
</D:multistatus>"""

    private suspend fun challenge(call: ApplicationCall) {
        call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"DavSrv\", charset=\"UTF-8\"")
        call.response.header(DavHeaders.DAV, "1, 2")
        call.respondText(ServerText.s(R.string.srv_err_unauthorized), status = HttpStatusCode.Unauthorized)
    }

    private fun authenticate(call: ApplicationCall): AuthUser? {
        val header = call.request.headers[HttpHeaders.Authorization] ?: return null
        if (!header.startsWith("Basic ", ignoreCase = true)) return null
        val decoded = runCatching {
            String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val sep = decoded.indexOf(':')
        if (sep <= 0) return null
        val username = decoded.substring(0, sep)
        val password = decoded.substring(sep + 1)

        val account = Repo.users.value.firstOrNull { it.username == username } ?: return null
        val key = username + ":" + sha256(password + "|" + account.salt)
        val now = System.currentTimeMillis()
        val cached = authCache[key]
        if (cached != null && cached > now) return account

        val ok = Passwords.verify(password, account.salt, account.hash)
        if (ok) {
            if (authCache.size > 256) authCache.clear()
            authCache[key] = now + 10 * 60_000
            return account
        }
        return null
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private val WRITE_METHODS = setOf("PUT", "DELETE", "MKCOL", "COPY", "MOVE", "PROPPATCH", "LOCK", "UNLOCK")

    private val METHODS = listOf(
        HttpMethod("OPTIONS"), HttpMethod.Get, HttpMethod.Head, HttpMethod.Post,
        HttpMethod.Put, HttpMethod.Delete, HttpMethod("MKCOL"), HttpMethod("PROPFIND"),
        HttpMethod("PROPPATCH"), HttpMethod("COPY"), HttpMethod("MOVE"),
        HttpMethod("LOCK"), HttpMethod("UNLOCK"), HttpMethod("PATCH")
    )
}
