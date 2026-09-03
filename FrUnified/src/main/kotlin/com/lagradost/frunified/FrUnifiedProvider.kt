package com.lagradost.frunified

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * FR Unifié — UN catalogue, TOUTES les sources françaises.
 *
 * Toutes les écritures de champs « avancés » (score, acteurs, trailers, IDs de
 * synchronisation…) sont protégées par [safe] : selon la version de CloudStream
 * installée, certaines de ces API n'existent pas et lèvent un NoSuchMethodError
 * qui ferait échouer tout le chargement de la fiche.
 */
private const val LOG_TAG = "FrUnified"

class FrUnifiedProvider : MainAPI() {

    override var mainUrl = "https://www.themoviedb.org"
    override var name = PROVIDER_NAME
    override var lang = "fr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "tmdb|trending/all/week" to "🔥 Tendances de la semaine",
        "tmdb|movie/popular" to "🎬 Films populaires",
        "tmdb|movie/now_playing" to "🍿 Films récents",
        "tmdb|discover/movie?with_original_language=fr&sort_by=popularity.desc" to "🇫🇷 Films français",
        "tmdb|tv/popular" to "📺 Séries populaires",
        "tmdb|tv/on_the_air" to "🆕 Séries en cours",
        "tmdb|discover/tv?with_original_language=fr&sort_by=popularity.desc" to "🇫🇷 Séries françaises",
        "anime|trending" to "🌸 Animés de la saison",
        "anime|popular" to "🌸 Animés populaires",
        "tmdb|discover/movie?with_genres=16&sort_by=popularity.desc" to "🧸 Animation / jeunesse",
        "tmdb|movie/top_rated" to "⭐ Films les mieux notés",
        "tmdb|tv/top_rated" to "⭐ Séries les mieux notées"
    )

    /** Exécute un bloc en avalant toute erreur de liaison (API absente selon la version). */
    private inline fun safe(block: () -> Unit) {
        runCatching { block() }
    }

    // ---------------------------------------------------------------- accueil

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (catalog, target) = request.data.split("|", limit = 2)
            .let { it[0] to it.getOrElse(1) { "" } }

        val items = when (catalog) {
            "anime" -> AnimeCatalog.row(target, page)
            else -> {
                val path = target.substringBefore("?")
                val params = target.substringAfter("?", "")
                    .split("&")
                    .filter { it.contains("=") }
                    .associate { it.substringBefore("=") to it.substringAfter("=") }
                TmdbCatalog.row(path, page, params)
            }
        }

        return newHomePageResponse(request, items.map { it.toSearchResponse() }, hasNext = items.isNotEmpty())
    }

    // -------------------------------------------------------------- recherche

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val tmdb = async { runCatching { TmdbCatalog.search(query) }.getOrDefault(emptyList()) }
        val anime = async { runCatching { AnimeCatalog.search(query) }.getOrDefault(emptyList()) }

        val tmdbItems = tmdb.await()
        val seen = tmdbItems.map { TitleMatch.normalize(it.title) }.toMutableSet()
        val animeItems = anime.await().filter { item -> seen.add(TitleMatch.normalize(item.title)) }

        (tmdbItems + animeItems).map { it.toSearchResponse() }
    }

    // ------------------------------------------------------------------ fiche

    override suspend fun load(url: String): LoadResponse? {
        val id = CatalogId.parse(url)
        if (id == null) {
            // Fiche « diagnostic » plutôt qu'un écran d'erreur aveugle.
            return errorResponse("Adresse non reconnue : « ${url.take(140)} »", url)
        }
        return try {
            when (id.catalog) {
                "anilist" -> loadAnime(id)
                "mal" -> loadMalAnime(id)
                else -> loadTmdb(id)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            errorResponse(
                "Erreur interne FR Unifié : ${t::class.simpleName ?: t.javaClass.simpleName} — " +
                    (t.message?.take(240) ?: "détail inconnu"),
                url
            )
        }
    }

    private suspend fun errorResponse(message: String, url: String): LoadResponse? = runCatching {
        newMovieLoadResponse("FR Unifié — diagnostic", url, TvType.Movie, "{}") {
            this.plot = message
        }
    }.getOrNull()

    private fun describe(overview: String?): String = buildString {
        overview?.let { append(it).append("\n\n") }
        append(runCatching { SourceHub.sourcesSummary() }.getOrDefault(""))
    }.trim()

    private suspend fun loadTmdb(id: CatalogId): LoadResponse? {
        val details = TmdbCatalog.details(id) ?: return null
        val item = TmdbCatalog.toItem(details, id.kind) ?: return null
        val alternatives = runCatching { TmdbCatalog.alternativeTitles(id) }.getOrDefault(emptyList())
        val titles = (item.titles + alternatives).distinctBy { TitleMatch.normalize(it) }

        val imdbId = runCatching {
            details.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
        }.getOrNull()

        val genres = runCatching {
            details.optJSONArray("genres")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
            }
        }.getOrNull().orEmpty()

        val isAnimation = genres.any { it.contains("Animation", true) }
        val isJapanese = details.optString("original_language") == "ja"

        val actors = runCatching {
            details.optJSONObject("credits")?.optJSONArray("cast")?.let { cast ->
                (0 until minOf(cast.length(), 20)).mapNotNull { i ->
                    val person = cast.optJSONObject(i) ?: return@mapNotNull null
                    val actorName = person.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Actor(actorName, TmdbCatalog.image(person.optString("profile_path"), "w185")) to
                        person.optString("character").takeIf { it.isNotBlank() }
                }
            }
        }.getOrNull()

        val trailers = runCatching { youtubeTrailers(details) }.getOrDefault(emptyList())

        val recommendations = runCatching {
            details.optJSONObject("recommendations")?.optJSONArray("results")?.let { results ->
                (0 until results.length()).mapNotNull { i ->
                    results.optJSONObject(i)?.let { TmdbCatalog.toItem(it)?.toSearchResponse() }
                }
            }
        }.getOrNull().orEmpty()

        val plot = describe(item.overview)

        if (id.kind == "movie") {
            val payload = PlayPayload(
                kind = "movie",
                titles = titles,
                year = item.year,
                season = null,
                episode = null,
                tmdbId = id.id.toIntOrNull(),
                imdbId = imdbId
            )
            return newMovieLoadResponse(
                item.title,
                id.serialize(),
                if (isAnimation && isJapanese) TvType.AnimeMovie else TvType.Movie,
                payload.serialize()
            ) {
                this.posterUrl = item.posterUrl
                this.backgroundPosterUrl = item.backdropUrl
                this.year = item.year
                this.plot = plot
                this.tags = genres
                safe { this.score = Score.from10(item.rating10) }
                safe { this.duration = details.optInt("runtime").takeIf { it > 0 } }
                safe { this.recommendations = recommendations }
                safe { addActors(actors) }
                safe { addTMDbId(id.id) }
                safe { addImdbId(imdbId) }
                trailers.forEach { url -> safe { addTrailer(url) } }
            }
        }

        val seasonNumbers = runCatching {
            details.optJSONArray("seasons")?.let { seasons ->
                (0 until seasons.length()).mapNotNull { i -> seasons.optJSONObject(i)?.optInt("season_number") }
                    .filter { it > 0 }
            }
        }.getOrNull().orEmpty().ifEmpty { listOf(1) }

        // Numérotation absolue (ép. 1..N toutes saisons confondues) : les sites
        // d'animés/séries qui comptent en absolu retrouvent ainsi le bon épisode.
        val episodeCounts = runCatching {
            details.optJSONArray("seasons")?.let { seasons ->
                buildMap {
                    (0 until seasons.length()).forEach { i ->
                        val s = seasons.optJSONObject(i) ?: return@forEach
                        val n = s.optInt("season_number")
                        val c = s.optInt("episode_count")
                        if (n >= 0 && c > 0) this[n] = c
                    }
                }
            }
        }.getOrNull().orEmpty()

        val offsets = mutableMapOf<Int, Int>()
        var running = 0
        for (n in seasonNumbers.sorted()) {
            offsets[n] = running
            running += episodeCounts[n] ?: 0
        }

        val episodes = coroutineScope {
            seasonNumbers.take(40).map { seasonNumber ->
                async {
                    runCatching {
                        buildSeason(id, seasonNumber, titles, item.year, imdbId, offsets[seasonNumber])
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }

        return newTvSeriesLoadResponse(
            item.title,
            id.serialize(),
            when {
                isAnimation && isJapanese -> TvType.Anime
                isAnimation -> TvType.Cartoon
                else -> TvType.TvSeries
            },
            episodes
        ) {
            this.posterUrl = item.posterUrl
            this.backgroundPosterUrl = item.backdropUrl
            this.year = item.year
            this.plot = plot
            this.tags = genres
            safe { this.score = Score.from10(item.rating10) }
            safe { this.recommendations = recommendations }
            safe { addActors(actors) }
            safe { addTMDbId(id.id) }
            safe { addImdbId(imdbId) }
            trailers.forEach { url -> safe { addTrailer(url) } }
        }
    }

    /** Bandes-annonces YouTube TMDB, VF prioritaire. */
    private fun youtubeTrailers(details: JSONObject): List<String> {
        val videos = details.optJSONObject("videos")?.optJSONArray("results") ?: return emptyList()
        val all = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
            .filter { it.optString("site").equals("YouTube", true) }
            .filter { it.optString("key").isNotBlank() }

        val ranked = all.sortedWith(
            compareByDescending<JSONObject> { it.optString("iso_639_1") == "fr" }
                .thenByDescending { it.optString("type") == "Trailer" }
                .thenByDescending { it.optBoolean("official") }
        )
        return ranked.take(3).map { "https://www.youtube.com/watch?v=${it.optString("key")}" }
    }

    private suspend fun buildSeason(
        id: CatalogId,
        seasonNumber: Int,
        titles: List<String>,
        year: Int?,
        imdbId: String?,
        absoluteOffset: Int? = null
    ): List<Episode> {
        val season = TmdbCatalog.season(id.id, seasonNumber) ?: return emptyList()
        val list = season.optJSONArray("episodes") ?: return emptyList()

        return (0 until list.length()).mapNotNull { i ->
            val episodeJson: JSONObject = list.optJSONObject(i) ?: return@mapNotNull null
            val episodeNumber = episodeJson.optInt("episode_number").takeIf { it > 0 } ?: return@mapNotNull null

            val payload = PlayPayload(
                kind = "tv",
                titles = titles,
                year = year,
                season = seasonNumber,
                episode = episodeNumber,
                absoluteEpisode = absoluteOffset?.let { it + episodeNumber },
                tmdbId = id.id.toIntOrNull(),
                imdbId = imdbId
            )

            newEpisode(payload.serialize()) {
                this.name = episodeJson.optString("name").takeIf { it.isNotBlank() }
                this.season = seasonNumber
                this.episode = episodeNumber
                safe { this.posterUrl = TmdbCatalog.image(episodeJson.optString("still_path"), "w300") }
                safe { this.description = episodeJson.optString("overview").takeIf { it.isNotBlank() } }
                safe { this.runTime = episodeJson.optInt("runtime").takeIf { it > 0 } }
            }
        }
    }

    private suspend fun loadAnime(id: CatalogId): LoadResponse? {
        val media = AniListCatalog.details(id.id) ?: return null
        val item = AniListCatalog.item(media) ?: return null
        val titles = AniListCatalog.allTitles(media).ifEmpty { item.titles }
        val malId = media.optInt("idMal").takeIf { it > 0 }
        val genres = runCatching {
            media.optJSONArray("genres")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { g -> g.isNotBlank() } }
            }
        }.getOrNull().orEmpty()

        val plot = describe(item.overview)

        if (media.optString("format") == "MOVIE") {
            val payload = PlayPayload(
                kind = "movie",
                titles = titles,
                year = item.year,
                season = null,
                episode = null,
                anilistId = id.id.toIntOrNull(),
                malId = malId
            )
            return newMovieLoadResponse(item.title, id.serialize(), TvType.AnimeMovie, payload.serialize()) {
                this.posterUrl = item.posterUrl
                this.backgroundPosterUrl = item.backdropUrl
                this.year = item.year
                this.plot = plot
                this.tags = genres
                safe { this.score = Score.from10(item.rating10) }
                safe { addAniListId(id.id.toIntOrNull()) }
                safe { addMalId(malId) }
            }
        }

        val count = media.optInt("episodes").takeIf { it > 0 } ?: 24
        val episodes = animeEpisodes(titles, item.year, count, anilistId = id.id.toIntOrNull(), malId = malId)

        return newAnimeLoadResponse(item.title, id.serialize(), TvType.Anime) {
            this.posterUrl = item.posterUrl
            this.backgroundPosterUrl = item.backdropUrl
            this.year = item.year
            this.plot = plot
            this.tags = genres
            safe { this.score = Score.from10(item.rating10) }
            safe {
                this.episodes = mutableMapOf(
                    DubStatus.Dubbed to episodes,
                    DubStatus.Subbed to episodes
                )
            }
            safe { addAniListId(id.id.toIntOrNull()) }
            safe { addMalId(malId) }
        }
    }

    private suspend fun loadMalAnime(id: CatalogId): LoadResponse? {
        val data = JikanCatalog.details(id.id) ?: return null
        val item = JikanCatalog.item(data) ?: return null
        val titles = JikanCatalog.allTitles(data).ifEmpty { item.titles }
        val malId = id.id.toIntOrNull()
        val genres = runCatching {
            data.optJSONArray("genres")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
            }
        }.getOrNull().orEmpty()

        val trailer = runCatching {
            data.optJSONObject("trailer")?.optString("url")?.takeIf { it.startsWith("http") }
        }.getOrNull()

        val plot = describe(item.overview)

        if (data.optString("type").equals("Movie", true)) {
            val payload = PlayPayload(
                kind = "movie",
                titles = titles,
                year = item.year,
                season = null,
                episode = null,
                malId = malId
            )
            return newMovieLoadResponse(item.title, id.serialize(), TvType.AnimeMovie, payload.serialize()) {
                this.posterUrl = item.posterUrl
                this.year = item.year
                this.plot = plot
                this.tags = genres
                safe { this.score = Score.from10(item.rating10) }
                safe { addMalId(malId) }
                safe { addTrailer(trailer) }
            }
        }

        val count = data.optInt("episodes").takeIf { it > 0 } ?: 24
        val episodes = animeEpisodes(titles, item.year, count, anilistId = null, malId = malId)

        return newAnimeLoadResponse(item.title, id.serialize(), TvType.Anime) {
            this.posterUrl = item.posterUrl
            this.year = item.year
            this.plot = plot
            this.tags = genres
            safe { this.score = Score.from10(item.rating10) }
            safe {
                this.episodes = mutableMapOf(
                    DubStatus.Dubbed to episodes,
                    DubStatus.Subbed to episodes
                )
            }
            safe { addMalId(malId) }
            safe { addTrailer(trailer) }
        }
    }

    private fun animeEpisodes(
        titles: List<String>,
        year: Int?,
        count: Int,
        anilistId: Int?,
        malId: Int?
    ): List<Episode> = (1..count).map { number ->
        val payload = PlayPayload(
            kind = "anime",
            titles = titles,
            year = year,
            season = 1,
            episode = number,
            absoluteEpisode = number,
            anilistId = anilistId,
            malId = malId
        )
        newEpisode(payload.serialize()) {
            this.name = "Épisode $number"
            this.season = 1
            this.episode = number
        }
    }

    // ------------------------------------------------------------------ liens

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val payload = PlayPayload.parse(data) ?: return@coroutineScope false

        val linkJobs = mutableListOf<Deferred<Boolean>>()
        val sideJobs = mutableListOf<Deferred<Boolean>>()

        // 1. Extensions FR installées (cochées dans les réglages)
        linkJobs += async {
            runCatching { SourceHub.aggregateLinks(payload, subtitleCallback, callback) > 0 }
                .getOrDefault(false)
        }

        // 2. Addons Stremio configurés (Torrentio, Comet, debrid perso…)
        if (FrSettings.useStremio) {
            FrSettings.stremioUrls.forEach { addon ->
                linkJobs += async {
                    runCatching {
                        withTimeoutOrNull(45_000L) { StremioClient.streams(addon, payload, callback) } ?: false
                    }.getOrDefault(false)
                }
            }
        }

        // 3. Scrapeurs Nuvio (plugins locaux du projet Nuvio : Gowaru FR…)
        if (FrSettings.useNuvio) {
            linkJobs += async {
                runCatching {
                    val collected = java.util.concurrent.CopyOnWriteArrayList<ExtractorLink>()
                    val ok = withTimeoutOrNull(3 * 60_000L) {
                        NuvioClient.streams(payload) { collected += it }
                    } ?: false

                    // Serveurs prioritaires (VF, 1080, HD…) en tête de liste
                    val patterns = FrSettings.nuvioPriorityPatterns
                    val links: List<ExtractorLink> = if (patterns.isEmpty()) collected.toList() else {
                        fun rank(l: ExtractorLink): Int {
                            val name = l.name.uppercase()
                            patterns.forEachIndexed { i, p -> if (name.contains(p)) return i }
                            return patterns.size
                        }
                        collected.sortedBy { rank(it) } // tri stable
                    }
                    links.forEach(callback)

                    // Diagnostic : ne jamais avaler une erreur sans trace
                    NuvioClient.diagnostics().toSortedMap().forEach { (id, status) ->
                        Log.i(LOG_TAG, "[Nuvio] $id → $status")
                    }
                    ok
                }.getOrDefault(false)
            }
        }

        // 4. Sous-titres externes (n'entrent pas dans le décompte des liens)
        if (FrSettings.useSubtitles) {
            (FrSettings.stremioUrls + FrSettings.DEFAULT_SUBTITLE_ADDON).distinct().forEach { addon ->
                sideJobs += async {
                    runCatching {
                        withTimeoutOrNull(25_000L) { StremioClient.subtitles(addon, payload, subtitleCallback) } ?: false
                    }.getOrDefault(false)
                }
            }
        }

        val hasLinks = linkJobs.awaitAll().any { it }
        sideJobs.awaitAll()
        hasLinks
    }

    // ------------------------------------------------------------------ utils

    private fun CatalogItem.toSearchResponse(): SearchResponse {
        val url = id.serialize()
        val poster = posterUrl
        val itemYear = year
        val rating = rating10

        return when (id.kind) {
            "anime" -> newAnimeSearchResponse(title, url, TvType.Anime, fix = false) {
                this.posterUrl = poster
                this.year = itemYear
                safe { this.score = Score.from10(rating) }
            }

            "tv" -> newTvSeriesSearchResponse(title, url, TvType.TvSeries, fix = false) {
                this.posterUrl = poster
                this.year = itemYear
                safe { this.score = Score.from10(rating) }
            }

            else -> newMovieSearchResponse(title, url, TvType.Movie, fix = false) {
                this.posterUrl = poster
                this.year = itemYear
                safe { this.score = Score.from10(rating) }
            }
        }
    }

    companion object {
        const val PROVIDER_NAME = "FR Unifié"
    }
}
