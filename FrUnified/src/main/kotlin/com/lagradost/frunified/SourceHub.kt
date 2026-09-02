package com.lagradost.frunified

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
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
 * Principe : au lieu de recopier (et de devoir maintenir) le scraping de
 * French-Stream, Movix, Wiflix, FrenchAnime, Frembed, FSTV, Karma…, on
 * réutilise directement les providers déjà installés dans CloudStream via
 * [APIHolder]. Pour une fiche du catalogue, on interroge chaque source en
 * parallèle, on apparie le bon contenu ([TitleMatch]) puis on relaie ses
 * liens vers le lecteur, en les préfixant du nom de la source.
 */
object SourceHub {

    private const val SEARCH_TIMEOUT_MS = 20_000L
    private const val LOAD_TIMEOUT_MS = 25_000L
    private const val LINKS_TIMEOUT_MS = 45_000L

    /** Langues considérées comme « francophones ». */
    private val FRENCH_LANGS = setOf("fr", "fr-fr", "fra", "french")

    /** Sources à ignorer (méta-providers, doublons, agrégateurs). */
    private val BLACKLIST = setOf(
        FrUnifiedProvider.PROVIDER_NAME,
        "Multi", "MultiFR"
    )

    /** Cache court : fiche du catalogue -> (source -> url trouvée). */
    private val matchCache = ConcurrentHashMap<String, Pair<Long, String?>>()
    private const val MATCH_TTL_MS = 30 * 60 * 1000L

    /** Toutes les extensions françaises installées, hors nous-mêmes. */
    fun frenchSources(): List<MainAPI> {
        val fromApis = runCatching {
            APIHolder.apis.withLock { APIHolder.apis.toList() }
        }.getOrNull().orEmpty()

        val fromAll = runCatching {
            APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
        }.getOrNull().orEmpty()

        return (fromApis + fromAll)
            .distinctBy { it.name }
            .filter { api ->
                api.name !in BLACKLIST &&
                    api.lang.lowercase() in FRENCH_LANGS &&
                    api.mainUrl.isNotBlank()
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Cherche la fiche sur une source donnée et renvoie l'URL de la page
     * correspondante (ou null si rien de suffisamment ressemblant).
     */
    private suspend fun locate(api: MainAPI, payload: PlayPayload): String? {
        val cacheKey = "${api.name}|${payload.tmdbId ?: payload.anilistId ?: payload.primaryTitle}|${payload.year}"
        val now = System.currentTimeMillis()
        matchCache[cacheKey]?.let { (expiry, url) -> if (expiry > now) return url }

        val queries = payload.titles
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { TitleMatch.normalize(it) }
            .take(3)

        var best: Pair<Double, String>? = null

        for (query in queries) {
            val results = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                runCatching {
                    if (api.hasQuickSearch) api.quickSearch(query) ?: api.search(query)
                    else api.search(query)
                }.getOrElse { runCatching { api.search(query) }.getOrNull() }
            }.orEmpty()

            results.forEach { result ->
                val candidateYear = when (result) {
                    is MovieSearchResponse -> result.year
                    is TvSeriesSearchResponse -> result.year
                    else -> null
                }
                val score = TitleMatch.score(payload.titles, result.name, payload.year, candidateYear)
                if (score >= TitleMatch.ACCEPT_THRESHOLD && (best == null || score > best!!.first)) {
                    best = score to result.url
                }
            }

            // Correspondance quasi parfaite : inutile d'essayer les autres titres.
            if ((best?.first ?: 0.0) >= 0.95) break
        }

        val url = best?.second
        matchCache[cacheKey] = (now + MATCH_TTL_MS) to url
        return url
    }

    /** Choisit l'entrée à lire dans la fiche renvoyée par la source. */
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

        // 1. saison + épisode exacts
        episodes.firstOrNull { it.season == season && it.episode == episode }?.let { return it.data }
        // 2. saison implicite (sources qui ne renseignent pas la saison)
        episodes.firstOrNull { (it.season == null || it.season == 1) && it.episode == episode && season == 1 }
            ?.let { return it.data }
        // 3. numérotation absolue (fréquent sur les sites d'animés)
        payload.absoluteEpisode?.let { abs ->
            episodes.firstOrNull { it.episode == abs }?.let { return it.data }
            episodes.getOrNull(abs - 1)?.let { return it.data }
        }
        // 4. position dans la liste
        return if (season == 1) episodes.getOrNull(episode - 1)?.data else null
    }

    /** Épisodes d'un [AnimeLoadResponse], VF d'abord (public francophone). */
    private fun anyEpisodes(response: AnimeLoadResponse): List<Episode> =
        response.episodes[DubStatus.Dubbed]
            ?: response.episodes[DubStatus.Subbed]
            ?: response.episodes.values.firstOrNull()
            ?: emptyList()

    /**
     * Récupère les liens de TOUTES les sources françaises installées, en
     * parallèle, pour la fiche demandée.
     *
     * @return le nombre de sources ayant fourni au moins un lien.
     */
    suspend fun aggregateLinks(
        payload: PlayPayload,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int = coroutineScope {
        val sources = frenchSources()
        if (sources.isEmpty()) return@coroutineScope 0

        sources.map { api ->
            async {
                runCatching {
                    withTimeoutOrNull(LINKS_TIMEOUT_MS) { linksFrom(api, payload, subtitleCallback, callback) } ?: false
                }.getOrDefault(false)
            }
        }.awaitAll().count { it }
    }

    private suspend fun linksFrom(
        api: MainAPI,
        payload: PlayPayload,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = locate(api, payload) ?: return false

        val response = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            runCatching { api.load(url) }.getOrNull()
        } ?: return false

        val data = pickData(response, payload) ?: return false

        var emitted = false
        val tagged: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(rename(api, link))
        }

        runCatching { api.loadLinks(data, false, subtitleCallback, tagged) }
        return emitted
    }

    /** Préfixe le lien par le nom de la source pour rester lisible dans le lecteur. */
    @Suppress("DEPRECATION")
    private fun rename(api: MainAPI, link: ExtractorLink): ExtractorLink {
        // Les sous-classes (DRM, torrent…) sont relayées telles quelles pour ne rien perdre.
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

    /** Utilisé par la fiche pour afficher les sources détectées. */
    fun sourcesSummary(): String {
        val names = frenchSources().map { it.name }
        return if (names.isEmpty()) {
            "⚠️ Aucune extension française détectée : installez les addons FR " +
                "(French-Stream, Movix, Wiflix, FrenchAnime, Frembed, FSTV…) puis relancez l'app."
        } else {
            "🔗 ${names.size} source(s) branchée(s) : ${names.joinToString(", ")}"
        }
    }
}
