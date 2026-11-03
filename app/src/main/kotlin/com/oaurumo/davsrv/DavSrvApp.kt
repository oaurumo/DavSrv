package com.oaurumo.davsrv

import android.app.Application
import com.oaurumo.davsrv.data.Repo
import com.oaurumo.davsrv.server.WebDavServer
import com.oaurumo.davsrv.service.Notifications

class DavSrvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        WebDavServer.init(this)
        Notifications.ensureChannel(this)
    }
}
