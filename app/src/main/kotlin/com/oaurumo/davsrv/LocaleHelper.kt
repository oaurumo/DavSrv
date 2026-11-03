package com.oaurumo.davsrv

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import java.util.Locale

/**
 * In-app language selection.
 *
 * The chosen value lives in a plain SharedPreferences file so that
 * `attachBaseContext` can read it synchronously at every process start.
 *
 * By default nothing is stored and the app simply follows the system language;
 * when the system language has no translation we fall back to English.
 */
object LocaleHelper {
    private const val PREFS = "locale_prefs"
    private const val KEY = "language"

    /** Stored value meaning "follow the system language". */
    const val SYSTEM = "system"

    /** Language used when the system language is not translated. */
    const val FALLBACK = "en"

    /** Language tags that ship translated resources. */
    private val SUPPORTED = listOf(
        "en", "zh", "hi", "bn", "ur", "ar", "es", "pt",
        "ru", "de", "fr", "ja", "ko", "id", "ms", "th"
    )

    /** Explicit UI language options; each name is written in its own language. */
    val languages = listOf(
        "en" to "English",
        "zh" to "简体中文",
        "hi" to "हिन्दी",
        "bn" to "বাংলা",
        "ur" to "اردو",
        "ar" to "العربية",
        "es" to "Español",
        "pt" to "Português",
        "ru" to "Русский",
        "de" to "Deutsch",
        "fr" to "Français",
        "ja" to "日本語",
        "ko" to "한국어",
        "id" to "Bahasa Indonesia",
        "ms" to "Bahasa Melayu",
        "th" to "ไทย"
    )

    /** Stored preference: an explicit tag, or [SYSTEM] when never chosen. */
    fun preference(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, SYSTEM) ?: SYSTEM

    /** Concrete language tag in effect right now. */
    fun resolved(context: Context): String {
        val pref = preference(context)
        return if (pref == SYSTEM) systemTag() else pref
    }

    /**
     * The system language mapped onto a tag we ship, or [FALLBACK]. Read from
     * `Resources.getSystem()` so an already-wrapped context cannot influence it.
     */
    fun systemTag(): String {
        val config = Resources.getSystem().configuration
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            config.locale
        } ?: return FALLBACK
        val raw = locale.language.lowercase(Locale.ROOT)
        // Android still reports Indonesian with the legacy ISO code "in".
        val language = if (raw == "in") "id" else raw
        if (language !in SUPPORTED) return FALLBACK
        // Resources ship Simplified Chinese; map any zh-* onto it rather than
        // falling all the way back to English.
        return if (language == "zh") "zh-CN" else language
    }

    /** Human readable name of the resolved system language, e.g. "简体中文". */
    fun systemDisplayName(): String {
        val tag = systemTag()
        return languages.firstOrNull { it.first == tag.substringBefore('-') }?.second ?: "English"
    }

    fun apply(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, tag).apply()
    }

    /** Wraps [context] with the configured locale; also updates Locale.getDefault. */
    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(resolved(context))
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
