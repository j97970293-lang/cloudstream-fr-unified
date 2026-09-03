package com.lagradost.frunified

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Moteur d'agrégation : c'est lui qui « fait fonctionner » toutes les
 * extensions françaises derrière le catalogue unique.
 *
 * Important : l'accès à la liste des providers installés se fait par
 * RÉFLEXION. Le type de `APIHolder.apis` a changé selon les versions de
 * CloudStream (List puis AtomicList) ; un accès direct provoquait un
 * NoSuchMethodError au chargement d'une fiche sur certaines versions.
 */
object SourceHub {

    private const val SEARCH_TIMEOUT_MS = 20_000L
    private const val LOAD_TIMEOUT_MS = 25_000L
    private const val LINKS_TIMEOUT_MS = 45_000L

    /** Sources à ignorer (méta-providers, doublons, agrégateurs). */
    private val BLACKLIST = setOf(FrUnifiedProvider.PROVIDER_NAME, "Multi", "MultiFR")

    private val matchCache = ConcurrentHashMap<String, Pair<Long, String?>>()
    private const val MATCH_TTL_MS = 30 * 60 * 1000L

    /** Dernier verdict par source (nom -> « ✓ 3 liens » ou « ✗ raison »). */
    private val lastErrors = ConcurrentHashMap<String, String>()
    fun diagnostics(): Map<String, String> = lastErrors.toMap()

    // ------------------------------------------------------- découverte

    /** Lit `APIHolder.apis` / `APIHolder.allProviders` sans dépendre de leur type. */
    private fun providersByReflection(): List<MainAPI> {
        val found = LinkedHashSet<MainAPI>()
        val holder: Any = APIHolder
        val clazz = holder.javaClass

        for (member in listOf("apis", "allProviders")) {
            runCatching {
                val value = runCatching {
                    clazz.getMethod("get" + member.replaceFirstChar { it.uppercase() }).invoke(holder)
                }.getOrElse {
                    clazz.getDeclaredField(member).apply { isAccessible = true }.get(holder)
                }
                when (value) {
                    is Iterable<*> -> value.toList()
                    is Array<*> -> value.toList()
                    else -> emptyList<Any?>()
                }.forEach { entry -> (entry as? MainAPI)?.let { found.add(it) } }
            }
        }
        return found.toList()
    }

    /** Toutes les extensions françaises installées (avant filtrage utilisateur). */
    fun detectedSources(): List<MainAPI> = runCatching {
        providersByReflection()
            .distinctBy { it.name }
            .filter { api ->
                api.name !in BLACKLIST &&
                    runCatching { api.lang.lowercase().startsWith("fr") }.getOrDefault(false)
            }
            .sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    /** Sources réellement utilisées (celles cochées dans les réglages). */
    fun activeSources(): List<MainAPI> =
        if (!FrSettings.useLocalSources) emptyList()
        else detectedSources().filter { FrSettings.isSourceEnabled(it.name) }

    // --------------------------------------------------- appariement

    /**
     * Localise la fiche sur une source.
     *
     * 1. Les extensions qui exploitent déjà TMDB/IMDb (Frembed, Movix…) exposent
     *    `supportedSyncNames` : on leur passe directement l'ID, sans recherche
     *    textuelle — c'est exact et instantané.
     * 2. Sinon, recherche textuelle multi-titres + appariement [TitleMatch].
     */
    /** Détail d'un échec d'appariement (pour les boutons Tester). */
    data class LocateInfo(val results: Int = 0, val bestScore: Double = 0.0, val directId: Boolean = false)

    private suspend fun locate(api: MainAPI, payload: PlayPayload): String? = locateFull(api, payload).first

    private suspend fun locateFull(api: MainAPI, payload: PlayPayload): Pair<String?, LocateInfo> {
        val cacheKey = "${api.name}|${payload.tmdbId ?: payload.anilistId ?: payload.malId ?: payload.primaryTitle}|${payload.year}"
        val now = System.currentTimeMillis()
        matchCache[cacheKey]?.let { (expiry, url) -> if (expiry > now) return url to LocateInfo(directId = url != null) }

        val direct = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { locateBySyncId(api, payload) }
        if (direct != null) {
            matchCache[cacheKey] = (now + MATCH_TTL_MS) to direct
            return direct to LocateInfo(directId = true)
        }

        val queries = buildList {
            payload.titles.take(6).forEach { add(it) }
            payload.titles.firstOrNull()?.let { t ->
                payload.year?.let { year -> add("$t $year") }
            }
            // Les sources qui séparent les saisons en fiches distinctes se
            // retrouvent avec « Titre Saison N ».
            if (payload.isSeries) {
                val season = payload.season ?: 1
                payload.titles.take(2).forEach { t ->
                    if (season > 1) {
                        add("$t saison $season")
                        add("$t season $season")
                    }
                    add("$t s$season")
                }
            }
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { TitleMatch.normalize(it) }
            .take(6)

        var best: Pair<Double, String>? = null
        var totalResults = 0

        for (query in queries) {
            var results = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                runCatching {
                    if (api.hasQuickSearch) api.quickSearch(query) ?: api.search(query)
                    else api.search(query)
                }.getOrElse { runCatching { api.search(query) }.getOrNull() }
            }.orEmpty()
            totalResults = maxOf(totalResults, results.size)

            // quickSearch est parfois limité : on complète avec la recherche complète
            if (results.size <= 2 && api.hasQuickSearch) {
                val full = runCatching {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) { api.search(query) }
                }.getOrNull()
                if (!full.isNullOrEmpty()) results = full
            }

            val wantedSeason = payload.season.takeIf { payload.isSeries }
            results.forEach { result ->
                val candidateYear = when (result) {
                    is MovieSearchResponse -> result.year
                    is TvSeriesSearchResponse -> result.year
                    is AnimeSearchResponse -> result.year   // fiches anime : structure différente
                    else -> null
                }
                val score = TitleMatch.score(
                    payload.titles, result.name, payload.year, candidateYear, wantedSeason
                )
                if (score >= TitleMatch.ACCEPT_THRESHOLD && (best == null || score > best!!.first)) {
                    best = score to result.url
                }
            }

            if ((best?.first ?: 0.0) >= 0.95) break
        }

        val url = best?.second
        matchCache[cacheKey] = (now + MATCH_TTL_MS) to url
        return url to LocateInfo(results = totalResults, bestScore = best?.first ?: 0.0)
    }

    /** Résolution directe par identifiant (IMDb / MAL / AniList / Simkl). */
    private suspend fun locateBySyncId(api: MainAPI, payload: PlayPayload): String? {
        val supported = runCatching { api.supportedSyncNames }.getOrNull().orEmpty()
        if (supported.isEmpty()) return null

        val candidates = buildList {
            payload.imdbId?.let { add(SyncIdName.Imdb to it) }
            payload.malId?.let { add(SyncIdName.MyAnimeList to it.toString()) }
            payload.anilistId?.let { add(SyncIdName.Anilist to it.toString()) }
        }

        for ((name, id) in candidates) {
            if (name !in supported) continue
            val url = runCatching { api.getLoadUrl(name, id) }.getOrNull()
            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    // ------------------------------------------------------- sélection

    private fun pickData(response: LoadResponse, payload: PlayPayload): String? {
        if (!payload.isSeries) {
            return when (response) {
                is MovieLoadResponse -> response.dataUrl.takeIf { it.isNotBlank() }
                is TvSeriesLoadResponse -> response.episodes.firstOrNull()?.data
                is AnimeLoadResponse -> anyEpisodes(response).firstOrNull()?.data
                else -> null
            }
        }

        val season = payload.season ?: 1
        val episode = payload.episode ?: 1

        val episodes: List<Episode> = when (response) {
            is TvSeriesLoadResponse -> response.episodes
            is AnimeLoadResponse -> anyEpisodes(response)
            is MovieLoadResponse -> return response.dataUrl.takeIf { it.isNotBlank() }
            else -> emptyList()
        }
        if (episodes.isEmpty()) return null

        val sorted = { list: List<Episode> ->
            list.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
        }

        // 1. Correspondance exacte saison + épisode
        episodes.firstOrNull { it.season == season && it.episode == episode }?.let { return it.data }

        // 2. Numérotation absolue (sites d'animés qui comptent 1..N toutes saisons)
        payload.absoluteEpisode?.let { abs ->
            episodes.firstOrNull { it.episode == abs }?.let { return it.data }
            sorted(episodes).getOrNull(abs - 1)?.let { return it.data }
        }

        // 3. Même numéro d'épisode, saison la plus proche (fiches « Saison N » séparées)
        episodes.filter { it.episode == episode }
            .minByOrNull { kotlin.math.abs((it.season ?: 1) - season) }?.let { return it.data }

        // 4. Fiche à une seule saison alors qu'on cherche la saison N : numérotation locale
        episodes.filter { it.season == null || it.season == 1 }.let { flat ->
            if (flat.isNotEmpty()) flat.getOrNull(episode - 1)?.let { return it.data }
        }

        // 5. Dernier recours : position dans la liste triée
        return sorted(episodes).getOrNull(episode - 1)?.data
    }

    private fun anyEpisodes(response: AnimeLoadResponse): List<Episode> =
        runCatching {
            response.episodes[DubStatus.Dubbed]
                ?: response.episodes[DubStatus.Subbed]
                ?: response.episodes.values.firstOrNull()
        }.getOrNull() ?: emptyList()

    // ----------------------------------------------------- agrégation

    /**
     * Récupère les liens de toutes les sources actives, en parallèle.
     * @return le nombre de sources ayant fourni au moins un lien.
     */
    suspend fun aggregateLinks(
        payload: PlayPayload,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int = coroutineScope {
        val sources = activeSources()
        if (sources.isEmpty()) return@coroutineScope 0

        sources.map { api ->
            async {
                runCatching {
                    withTimeoutOrNull(LINKS_TIMEOUT_MS) {
                        linksFrom(api, payload, subtitleCallback, callback)
                    } ?: false
                }.getOrDefault(false)
            }
        }.awaitAll().count { it }
    }

    private suspend fun linksFrom(
        api: MainAPI,
        payload: PlayPayload,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = try {
        val (url, info) = locateFull(api, payload)
        if (url == null) {
            lastErrors[api.name] = when {
                info.directId -> "✗ fiche introuvable (ID direct)"
                info.results == 0 -> "✗ fiche introuvable (recherche: 0 résultat)"
                else -> "✗ fiche introuvable (${info.results} résultats, score max ${"%.2f".format(info.bestScore)})"
            }
            return false
        }

        val response = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            runCatching { api.load(url) }.getOrNull()
        }
        if (response == null) {
            lastErrors[api.name] = "✗ chargement de la fiche impossible"
            return false
        }

        val data = pickData(response, payload)
        if (data == null) {
            lastErrors[api.name] = "✗ aucun lecteur/épisode trouvé"
            return false
        }

        var emitted = false
        val tagged: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(rename(api, link))
        }

        runCatching { api.loadLinks(data, false, subtitleCallback, tagged) }
        lastErrors[api.name] = if (emitted) "✓ lien(s) émis" else "✓ 0 lien"
        emitted
    } catch (t: Throwable) {
        lastErrors[api.name] = "✗ " + (t.message?.take(80) ?: t::class.simpleName.orEmpty())
        false
    }

    /**
     * Test rapide d'UNE extension (bouton « Tester » des réglages) :
     * parcourt le même chemin que loadLinks sur Fight Club (TMDB 550).
     */
    suspend fun testSource(name: String): String {
        val api = detectedSources().firstOrNull { it.name == name } ?: return "✗ extension introuvable"
        val isAnimeSource = runCatching {
            api.lang.lowercase().startsWith("fr") &&
                (api.name.lowercase().contains("anime") || api.name.lowercase().contains("manga"))
        }.getOrDefault(false) ||
            runCatching { api.supportedSyncNames.any { it.name.lowercase().contains("anilist") || it.name.lowercase().contains("myanimelist") } }.getOrDefault(false)

        val cases = linkedMapOf(
            "film" to PlayPayload(
                kind = "movie", titles = listOf("Fight Club"), year = 1999,
                tmdbId = 550, imdbId = "tt0137523",
                season = 0, episode = 0
            ),
            "anime" to PlayPayload(
                kind = "tv", titles = listOf("One Piece"), year = 1999,
                tmdbId = 37854, anilistId = 21, malId = 21,
                season = 1, episode = 1
            )
        )

        val parts = mutableListOf<String>()
        var anyOk = false
        for ((label, payload) in cases) {
            if (!isAnimeSource && label == "anime") continue
            if (isAnimeSource && label == "film") continue
            val links = java.util.concurrent.CopyOnWriteArrayList<ExtractorLink>()
            val ok = runCatching {
                withTimeoutOrNull(60_000L) {
                    linksFrom(api, payload, {}, { links += it })
                } ?: false
            }.getOrDefault(false)
            if (ok) {
                anyOk = true
                val names = links.take(3).joinToString(", ") { it.name.take(22) }
                parts += "$label: ${links.size} lien(s) ($names)"
            } else {
                parts += "$label: " + (diagnostics()[api.name] ?: "✗ aucun résultat")
            }
        }
        return if (anyOk) "✓ " + parts.joinToString(" — ") else parts.joinToString(" — ")
    }

    /** Préfixe le lien par le nom de la source pour rester lisible dans le lecteur. */
    @Suppress("DEPRECATION")
    private fun rename(api: MainAPI, link: ExtractorLink): ExtractorLink {
        if (link.javaClass != ExtractorLink::class.java) return link
        return runCatching {
            ExtractorLink(
                source = api.name,
                name = "${api.name} • ${link.name}",
                url = link.url,
                referer = link.referer,
                quality = link.quality,
                headers = link.headers,
                extractorData = link.extractorData,
                type = link.type,
                audioTracks = link.audioTracks
            )
        }.getOrDefault(link)
    }

    /** Résumé affiché dans la description d'une fiche. */
    fun sourcesSummary(): String = runCatching {
        val detected = detectedSources().map { it.name }
        val active = activeSources().map { it.name }
        val stremio = if (FrSettings.useStremio) FrSettings.stremioUrls.size else 0

        buildString {
            if (detected.isEmpty()) {
                append("⚠️ Aucune extension française détectée. Installez les addons FR ")
                append("(French-Stream, Movix, Wiflix, FrenchAnime, Frembed, FSTV…) : ")
                append("FR Unifié s'en sert pour trouver les liens.")
            } else {
                append("🔗 ${active.size}/${detected.size} source(s) active(s) : ")
                append(active.joinToString(", ").ifBlank { "aucune (tout est désactivé dans les réglages)" })
            }
            if (stremio > 0) append("\n🧩 $stremio addon(s) Stremio configuré(s)")
        }
    }.getOrDefault("")
}
