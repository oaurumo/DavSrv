package com.oaurumo.davsrv.server

import android.content.Context
import androidx.annotation.StringRes
import com.oaurumo.davsrv.LocaleHelper
import java.util.Locale

/**
 * Localized text for responses produced by the WebDAV layer.
 *
 * Error bodies are read by people (they show up in Windows Explorer dialogs and
 * in macOS Finder), so they follow the app's language instead of being hard
 * coded in one language. The server runs outside any Activity, hence the
 * context is injected once from the Application and wrapped on demand; the
 * wrapped context is cached until the language changes.
 */
object ServerText {

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedTag: String? = null
    @Volatile private var cachedContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        cachedContext = null
        cachedTag = null
    }

    /** Resolves [id] in the currently selected app language. */
    fun s(@StringRes id: Int, vararg args: Any): String {
        val ctx = context() ?: return ""
        return if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
    }

    /** Language currently in effect, e.g. for HTML `lang` / `dir` attributes. */
    fun locale(): Locale {
        val base = appContext ?: return Locale.ENGLISH
        return Locale.forLanguageTag(LocaleHelper.resolved(base))
    }

    private fun context(): Context? {
        val base = appContext ?: return null
        val tag = LocaleHelper.resolved(base)
        val cached = cachedContext
        if (cached != null && cachedTag == tag) return cached
        return LocaleHelper.wrap(base).also {
            cachedContext = it
            cachedTag = tag
        }
    }
}
