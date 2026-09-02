package com.lagradost.frunified

import android.content.Context
import android.content.SharedPreferences

/**
 * Réglages du plugin, stockés dans les SharedPreferences de l'application.
 *
 * On n'utilise volontairement AUCUNE API interne de CloudStream ici : le
 * stockage Android natif fonctionne quelle que soit la version de l'app.
 */
object FrSettings {

    private const val PREFS = "frunified_settings"
    private const val KEY_DISABLED = "disabled_sources"
    private const val KEY_STREMIO = "stremio_urls"
    private const val KEY_USE_LOCAL = "use_local_sources"
    private const val KEY_USE_STREMIO = "use_stremio"
    private const val KEY_USE_SUBS = "use_subtitles"
    private const val KEY_SUB_LANGS = "subtitle_langs"

    /** Addon de sous-titres Stremio gratuit et sans clé, activé par défaut. */
    const val DEFAULT_SUBTITLE_ADDON = "https://opensubtitles-v3.strem.io"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Repli mémoire si le contexte n'est pas encore disponible. */
    private val memory = HashMap<String, String>()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = runCatching {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            }.getOrNull()
        }
    }

    private fun read(key: String, default: String): String =
        runCatching { prefs?.getString(key, null) }.getOrNull() ?: memory[key] ?: default

    private fun write(key: String, value: String) {
        memory[key] = value
        runCatching { prefs?.edit()?.putString(key, value)?.apply() }
    }

    private fun readBool(key: String, default: Boolean): Boolean =
        read(key, if (default) "1" else "0") == "1"

    private fun writeBool(key: String, value: Boolean) = write(key, if (value) "1" else "0")

    /** Sources (extensions FR) désactivées par l'utilisateur. */
    var disabledSources: Set<String>
        get() = read(KEY_DISABLED, "").split("\n").filter { it.isNotBlank() }.toSet()
        set(value) = write(KEY_DISABLED, value.joinToString("\n"))

    /** URLs d'addons Stremio (manifest.json ou URL de base), une par ligne. */
    var stremioUrls: List<String>
        get() = read(KEY_STREMIO, "").split("\n").map { it.trim() }.filter { it.isNotBlank() }
        set(value) = write(KEY_STREMIO, value.joinToString("\n"))

    /** Utiliser les extensions FR installées comme sources de liens. */
    var useLocalSources: Boolean
        get() = readBool(KEY_USE_LOCAL, true)
        set(value) = writeBool(KEY_USE_LOCAL, value)

    /** Utiliser les addons Stremio configurés. */
    var useStremio: Boolean
        get() = readBool(KEY_USE_STREMIO, true)
        set(value) = writeBool(KEY_USE_STREMIO, value)

    /** Récupérer les sous-titres externes (OpenSubtitles via Stremio). */
    var useSubtitles: Boolean
        get() = readBool(KEY_USE_SUBS, true)
        set(value) = writeBool(KEY_USE_SUBS, value)

    /** Langues de sous-titres à conserver (codes ISO 639, séparés par des virgules). */
    var subtitleLangs: List<String>
        get() = read(KEY_SUB_LANGS, "fre,fra,fr,eng,en").split(",").map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        set(value) = write(KEY_SUB_LANGS, value.joinToString(","))

    fun isSourceEnabled(name: String): Boolean = name !in disabledSources

    fun setSourceEnabled(name: String, enabled: Boolean) {
        disabledSources = if (enabled) disabledSources - name else disabledSources + name
    }
}
