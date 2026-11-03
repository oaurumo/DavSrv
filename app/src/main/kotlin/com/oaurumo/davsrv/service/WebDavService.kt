package com.oaurumo.davsrv.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.oaurumo.davsrv.data.Repo
import com.oaurumo.davsrv.server.WebDavServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the WebDAV listener alive. Uses the specialUse foreground type on
 * Android 14+ so the service is not cut off by the six-hour dataSync quota,
 * and holds no CPU wake lock — only an optional Wi-Fi lock for reliability.
 */
class WebDavService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> startServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        val settings = Repo.settings.value
        promoteToForeground(WebDavServer.state.value)

        val result = WebDavServer.start(settings.port)
        if (result.isFailure) {
            // Nothing to keep alive — surface the failure and stop.
            Notifications.update(this, WebDavServer.state.value)
            shutdown()
            return
        }

        applyPowerLocks(settings.wifiLock)
        registerNetworkObserver()
        observeState()
    }

    /** Keeps the displayed address correct when Wi-Fi switches networks. */
    private fun registerNetworkObserver() {
        if (networkCallback != null) return
        val manager = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = WebDavServer.refreshIp()
            override fun onLost(network: android.net.Network) = WebDavServer.refreshIp()
        }
        runCatching {
            manager.registerNetworkCallback(
                android.net.NetworkRequest.Builder().build(),
                callback
            )
            networkCallback = callback
        }
    }

    private fun unregisterNetworkObserver() {
        val manager = getSystemService(android.net.ConnectivityManager::class.java)
        networkCallback?.let { cb -> runCatching { manager?.unregisterNetworkCallback(cb) } }
        networkCallback = null
    }

    private fun promoteToForeground(state: com.oaurumo.davsrv.data.ServerState) {
        val notification = Notifications.build(this, state)
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        runCatching {
            ServiceCompat.startForeground(this, Notifications.NOTIFICATION_ID, notification, type)
        }.onFailure {
            // Older devices reject unknown types — fall back to a plain foreground service.
            runCatching {
                ServiceCompat.startForeground(this, Notifications.NOTIFICATION_ID, notification, 0)
            }
        }
    }

    private fun observeState() {
        observeJob?.cancel()
        observeJob = scope.launch {
            WebDavServer.state.collectLatest { state ->
                if (!state.running && state.error == null) return@collectLatest
                Notifications.update(this@WebDavService, state)
                if (!state.running) shutdown()
            }
        }
    }

    private fun applyPowerLocks(wifiOnly: Boolean) {
        if (wifiOnly && wifiLock == null) {
            runCatching {
                val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DavSrv:wifi")
                lock.setReferenceCounted(false)
                lock.acquire()
                wifiLock = lock
            }
        } else if (!wifiOnly) {
            releaseWifiLock()
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private fun shutdown() {
        observeJob?.cancel()
        observeJob = null
        unregisterNetworkObserver()
        releaseWifiLock()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        WebDavServer.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        // Android 15+ dataSync quota fallback.
        shutdown()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        unregisterNetworkObserver()
        releaseWifiLock()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        WebDavServer.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.oaurumo.davsrv.action.START"
        const val ACTION_STOP = "com.oaurumo.davsrv.action.STOP"

        @Volatile
        var alive = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WebDavService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WebDavService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
