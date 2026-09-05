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
    private const val KEY_SHOW_ALL_SOURCES = "show_all_sources"
    private const val KEY_STREMIO_CATALOG = "use_stremio_catalog"
    private const val KEY_STREMIO_FIRST = "stremio_catalog_first"
    private const val KEY_STREMIO_ROWS = "stremio_catalog_rows"
    private const val KEY_USE_STREMIO = "use_stremio"
    private const val KEY_USE_SUBS = "use_subtitles"
    private const val KEY_SUB_LANGS = "subtitle_langs"
    private const val KEY_TMDB = "tmdb_api_key"
    private const val KEY_TOKENS = "api_tokens"
    private const val KEY_USE_TMDB = "use_tmdb_catalog"
    private const val KEY_USE_ANIME = "use_anime_catalog"

    /** Addon de sous-titres Stremio gratuit et sans clé, activé par défaut. */
    const val DEFAULT_SUBTITLE_ADDON = "https://opensubtitles-v3.strem.io"

    /** Clé TMDB utilisée par le catalogue. */
    const val DEFAULT_TMDB_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"

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

    /**
     * Au premier lancement, écrit les dépôts par défaut dans les préférences.
     * Ensuite l'utilisateur peut les modifier/supprimer librement : les URLs ne
     * sont jamais « réinjectées » par le code (dépôts installables).
     */
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

    /** Afficher les catalogues publiés par les addons Stremio sur l'accueil. */
    var useStremioCatalog: Boolean
        get() = readBool(KEY_STREMIO_CATALOG, false)
        set(value) = writeBool(KEY_STREMIO_CATALOG, value)

    /** Placer les rangées Stremio avant celles de TMDB. */
    var stremioCatalogFirst: Boolean
        get() = readBool(KEY_STREMIO_FIRST, false)
        set(value) = writeBool(KEY_STREMIO_FIRST, value)

    /**
     * Rangées Stremio détectées, mémorisées pour bâtir l'accueil sans appel
     * réseau : une ligne par rangée, « addon#type#id#nom ».
     */
    var stremioCatalogRows: List<String>
        get() = read(KEY_STREMIO_ROWS, "").split("\n").map { it.trim() }.filter { it.isNotBlank() }
        set(value) = write(KEY_STREMIO_ROWS, value.joinToString("\n"))

    /** Utiliser les extensions FR installées comme sources de liens. */
    var useLocalSources: Boolean
        get() = readBool(KEY_USE_LOCAL, true)
        set(value) = writeBool(KEY_USE_LOCAL, value)

    /**
     * Afficher dans l'écran ⚙️ toutes les extensions installées, y compris
     * celles qui ne se déclarent pas françaises (dépannage : une extension
     * attendue reste introuvable dans la liste filtrée).
     */
    var showAllSources: Boolean
        get() = readBool(KEY_SHOW_ALL_SOURCES, false)
        set(value) = writeBool(KEY_SHOW_ALL_SOURCES, value)

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

    // ---------------------------------------------------- API et réseau

    /** Clé API TMDB (catalogue films/séries). */
    var tmdbApiKey: String
        get() = read(KEY_TMDB, DEFAULT_TMDB_KEY).trim().ifBlank { DEFAULT_TMDB_KEY }
        set(value) = write(KEY_TMDB, value.trim())

    /** Clés API supplémentaires, une par ligne au format CLE=valeur. */
    var apiTokens: Map<String, String>
        get() = read(KEY_TOKENS, "").split("\n").map { it.trim() }
            .filter { it.contains("=") }
            .associate { line ->
                val i = line.indexOf("=")
                line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
            }
        set(value) = write(KEY_TOKENS, value.entries.joinToString("\n") { "${it.key}=${it.value}" })

    // ---------------------------------------------------- catalogues

    /** Afficher le catalogue TMDB dans la recherche. */
    var useTmdbCatalog: Boolean
        get() = readBool(KEY_USE_TMDB, true)
        set(value) = writeBool(KEY_USE_TMDB, value)

    /** Afficher le catalogue anime (AniList/Jikan) dans la recherche. */
    var useAnimeCatalog: Boolean
        get() = readBool(KEY_USE_ANIME, true)
        set(value) = writeBool(KEY_USE_ANIME, value)
}
