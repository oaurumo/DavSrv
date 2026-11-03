package com.oaurumo.davsrv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.oaurumo.davsrv.data.SettingsStore
import com.oaurumo.davsrv.server.WebDavServer
import com.oaurumo.davsrv.service.WebDavService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (WebDavServer.isRunning) WebDavServer.refreshIp()
    }
}

/**
 * Restores the server after a reboot when the user enabled auto start. Settings
 * are read directly from DataStore because the in-memory snapshot may not have
 * loaded yet in a cold boot process.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsStore(context.applicationContext).flow.first()
                if (settings.bootStart) WebDavService.start(context.applicationContext)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }
}
