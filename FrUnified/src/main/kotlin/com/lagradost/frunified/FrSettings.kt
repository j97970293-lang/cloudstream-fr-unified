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
    private const val KEY_USE_NUVIO = "use_nuvio"
    private const val KEY_NUVIO_REPOS = "nuvio_repos"
    private const val KEY_NUVIO_DISABLED = "nuvio_disabled"
    private const val KEY_NUVIO_ALL = "nuvio_all_langs"
    private const val KEY_NUVIO_REPOS_DISABLED = "nuvio_repos_disabled"
    private const val KEY_NUVIO_MAX = "nuvio_max_per_scraper"
    private const val KEY_NUVIO_PRIORITY = "nuvio_priority_patterns"
    private const val KEY_NUVIO_ORDER = "nuvio_order"
    private const val KEY_NUVIO_CONCURRENCY = "nuvio_concurrency"
    private const val KEY_NUVIO_REMOVED = "nuvio_repos_removed"
    private const val KEY_TMDB = "tmdb_api_key"
    private const val KEY_TOKENS = "api_tokens"
    private const val KEY_UA = "nuvio_ua"
    private const val KEY_REFERER = "nuvio_referer"
    private const val KEY_COOKIES = "nuvio_cookies"
    private const val KEY_USE_TMDB = "use_tmdb_catalog"
    private const val KEY_USE_ANIME = "use_anime_catalog"

    /** Addon de sous-titres Stremio gratuit et sans clé, activé par défaut. */
    const val DEFAULT_SUBTITLE_ADDON = "https://opensubtitles-v3.strem.io"

    /** Dépôts Nuvio proposés au premier lancement (jamais réappliqués ensuite). */
    val DEFAULT_NUVIO_REPOS = listOf(
        "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/refs/heads/main/manifest.json",
        "https://raw.githubusercontent.com/z7kx/z7kx-nuvio-provider/refs/heads/main/manifest.json",
        "https://raw.githubusercontent.com/phisher98/phisher-nuvio-providers/refs/heads/main/manifest.json"
    )

    /** Motifs prioritaires par défaut (titre des liens). */
    val DEFAULT_NUVIO_PRIORITY = listOf("VF", "FRENCH", "VOSTFR", "1080", "HD")

    /** Clé TMDB utilisée par le catalogue ET par le repli de recherche des bundles. */
    const val DEFAULT_TMDB_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

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
        seedNuvioRepos()
    }

    /**
     * Au premier lancement, écrit les dépôts par défaut dans les préférences.
     * Ensuite l'utilisateur peut les modifier/supprimer librement : les URLs ne
     * sont jamais « réinjectées » par le code (dépôts installables).
     */
    private fun seedNuvioRepos() {
        val raw = runCatching { prefs?.getString(KEY_NUVIO_REPOS, null) }.getOrNull()
        val prio = runCatching { prefs?.getString(KEY_NUVIO_PRIORITY, null) }.getOrNull()
        if (prio == null) {
            write(KEY_NUVIO_PRIORITY, DEFAULT_NUVIO_PRIORITY.joinToString(","))
        }
        if (raw == null) {
            // Premier lancement : installe les dépôts par défaut.
            write(KEY_NUVIO_REPOS, DEFAULT_NUVIO_REPOS.joinToString("\n"))
        } else {
            // Mise à niveau : propose les nouveaux dépôts par défaut (z7kx…)
            // sans réinjecter ceux que l'utilisateur a explicitement retirés.
            val current = raw.split("\n").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
            val removed = read(KEY_NUVIO_REMOVED, "").split("\n").map { it.trim() }
                .filter { it.isNotBlank() }.toSet()
            var changed = false
            for (repo in DEFAULT_NUVIO_REPOS) {
                if (repo !in current && repo !in removed) {
                    current.add(repo)
                    changed = true
                }
            }
            if (changed) write(KEY_NUVIO_REPOS, current.joinToString("\n"))
        }
    }

    /** Dépôts par défaut retirés par l'utilisateur (pour ne pas les réinjecter). */
    var nuvioRemovedDefaults: Set<String>
        get() = read(KEY_NUVIO_REMOVED, "").split("\n").map { it.trim() }
            .filter { it.isNotBlank() }.toSet()
        set(value) = write(KEY_NUVIO_REMOVED, value.joinToString("\n"))

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

    // ------------------------------------------------------------- Nuvio

    /** Utiliser les scrapeurs Nuvio (plugins locaux) comme sources de liens. */
    var useNuvio: Boolean
        get() = readBool(KEY_USE_NUVIO, true)
        set(value) = writeBool(KEY_USE_NUVIO, value)

    /** URLs des dépôts de scrapeurs Nuvio (manifest.json), une par ligne. */
    var nuvioRepos: List<String>
        get() = read(KEY_NUVIO_REPOS, "").split("\n").map { it.trim() }
            .filter { it.isNotBlank() }
        set(value) = write(KEY_NUVIO_REPOS, value.joinToString("\n"))

    /** Dépôts Nuvio désactivés (URLs complètes), une par ligne. */
    var nuvioReposDisabled: Set<String>
        get() = read(KEY_NUVIO_REPOS_DISABLED, "").split("\n").map { it.trim() }
            .filter { it.isNotBlank() }.toSet()
        set(value) = write(KEY_NUVIO_REPOS_DISABLED, value.joinToString("\n"))

    fun isNuvioRepoEnabled(repo: String): Boolean = repo.trim() !in nuvioReposDisabled

    fun setNuvioRepoEnabled(repo: String, enabled: Boolean) {
        val r = repo.trim()
        nuvioReposDisabled = if (enabled) nuvioReposDisabled - r else nuvioReposDisabled + r
    }

    /** Limite de serveurs par scrapeur (0 = pas de limite). */
    var nuvioMaxPerScraper: Int
        get() = read(KEY_NUVIO_MAX, "12").toIntOrNull() ?: 12
        set(value) = write(KEY_NUVIO_MAX, value.coerceIn(0, 200).toString())

    /** Motifs prioritaires (titre du lien contient le motif, insensible à la casse). */
    var nuvioPriorityPatterns: List<String>
        get() = read(KEY_NUVIO_PRIORITY, "").split(",").map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
        set(value) = write(KEY_NUVIO_PRIORITY, value.joinToString(","))

    /** Ordre manuel des scrapeurs (IDs, un par ligne ; les absents restent triés). */
    var nuvioOrder: List<String>
        get() = read(KEY_NUVIO_ORDER, "").split("\n").map { it.trim() }
            .filter { it.isNotBlank() }
        set(value) = write(KEY_NUVIO_ORDER, value.joinToString("\n"))

    /** Scrapeurs Nuvio désactivés par l'utilisateur. */
    var nuvioDisabled: Set<String>
        get() = read(KEY_NUVIO_DISABLED, "").split("\n").filter { it.isNotBlank() }.toSet()
        set(value) = write(KEY_NUVIO_DISABLED, value.joinToString("\n"))

    /** Afficher aussi les scrapeurs Nuvio non français (EN, TR…) ou seulement les FR. */
    var nuvioAllLangs: Boolean
        get() = readBool(KEY_NUVIO_ALL, false)
        set(value) = writeBool(KEY_NUVIO_ALL, value)

    /** Nombre de scrapeurs exécutés en parallèle. */
    var nuvioConcurrency: Int
        get() = read(KEY_NUVIO_CONCURRENCY, "6").toIntOrNull()?.coerceIn(1, 12) ?: 6
        set(value) = write(KEY_NUVIO_CONCURRENCY, value.coerceIn(1, 12).toString())

    // ---------------------------------------------------- API et réseau

    /** Clé API TMDB (catalogue + repli des bundles Nuvio). */
    var tmdbApiKey: String
        get() = read(KEY_TMDB, DEFAULT_TMDB_KEY).trim().ifBlank { DEFAULT_TMDB_KEY }
        set(value) = write(KEY_TMDB, value.trim())

    /**
     * Clés API supplémentaires, une par ligne au format CLE=valeur.
     * Injectées dans `process.env` de l'environnement JavaScript : les bundles
     * Nuvio lisent leurs clés comme NUVIO_XXX_API_KEY…
     */
    var apiTokens: Map<String, String>
        get() = read(KEY_TOKENS, "").split("\n").map { it.trim() }
            .filter { it.contains("=") }
            .associate { line ->
                val i = line.indexOf("=")
                line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
            }
        set(value) = write(KEY_TOKENS, value.entries.joinToString("\n") { "${it.key}=${it.value}" })

    /** User-Agent appliqué aux requêtes des scrapeurs Nuvio. */
    var nuvioUserAgent: String
        get() = read(KEY_UA, DEFAULT_USER_AGENT)
        set(value) = write(KEY_UA, value.trim())

    /** Referer par défaut des requêtes Nuvio (vide = celui du bundle). */
    var nuvioReferer: String
        get() = read(KEY_REFERER, "https://www.google.com/")
        set(value) = write(KEY_REFERER, value.trim())

    /** Cookies par défaut des requêtes Nuvio (contournement Cloudflare). */
    var nuvioCookies: String
        get() = read(KEY_COOKIES, "")
        set(value) = write(KEY_COOKIES, value.trim())

    // ---------------------------------------------------- catalogues

    /** Afficher le catalogue TMDB dans la recherche. */
    var useTmdbCatalog: Boolean
        get() = readBool(KEY_USE_TMDB, true)
        set(value) = writeBool(KEY_USE_TMDB, value)

    /** Afficher le catalogue anime (AniList/Jikan) dans la recherche. */
    var useAnimeCatalog: Boolean
        get() = readBool(KEY_USE_ANIME, true)
        set(value) = writeBool(KEY_USE_ANIME, value)

    fun isNuvioEnabled(id: String): Boolean = id !in nuvioDisabled

    fun setNuvioEnabled(id: String, enabled: Boolean) {
        nuvioDisabled = if (enabled) nuvioDisabled - id else nuvioDisabled + id
    }
}
