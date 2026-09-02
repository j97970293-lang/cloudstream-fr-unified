package com.lagradost.frunified

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * FR Unifié — UN catalogue, TOUTES les sources françaises.
 *
 * - Catalogue : TMDB (films / séries / animation) + AniList (animés).
 * - Recherche : une seule entrée, dédupliquée, en français.
 * - Lecture : [SourceHub] interroge en parallèle toutes les extensions FR
 *   installées et remonte leurs liens dans la même fiche.
 */
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

    // ---------------------------------------------------------------- accueil

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (catalog, target) = request.data.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }

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
        val anilist = async { runCatching { AnimeCatalog.search(query) }.getOrDefault(emptyList()) }

        val tmdbItems = tmdb.await()
        val seen = tmdbItems.map { TitleMatch.normalize(it.title) }.toMutableSet()

        val animeItems = anilist.await().filter { item ->
            // On complète le catalogue TMDB avec les animés absents, sans doublon visuel.
            seen.add(TitleMatch.normalize(item.title))
        }

        (tmdbItems + animeItems).map { it.toSearchResponse() }
    }

    // ------------------------------------------------------------------ fiche

    override suspend fun load(url: String): LoadResponse? {
        val id = CatalogId.parse(url) ?: return null
        return when (id.catalog) {
            "anilist" -> loadAnime(id)
            "mal" -> loadMalAnime(id)
            else -> loadTmdb(id)
        }
    }

    private suspend fun loadTmdb(id: CatalogId): LoadResponse? {
        val details = TmdbCatalog.details(id) ?: return null
        val item = TmdbCatalog.toItem(details, id.kind) ?: return null
        val alternatives = runCatching { TmdbCatalog.alternativeTitles(id) }.getOrDefault(emptyList())
        val titles = (item.titles + alternatives).distinctBy { TitleMatch.normalize(it) }

        val imdbId = details.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
        val genres = details.optJSONArray("genres")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
        }.orEmpty()
        val isAnimation = genres.any { it.contains("Animation", true) }
        val isJapanese = details.optString("original_language") == "ja"

        val actors = details.optJSONObject("credits")?.optJSONArray("cast")?.let { cast ->
            (0 until minOf(cast.length(), 20)).mapNotNull { i ->
                val person = cast.optJSONObject(i) ?: return@mapNotNull null
                val actorName = person.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Actor(actorName, TmdbCatalog.image(person.optString("profile_path"), "w185")) to
                    person.optString("character").takeIf { it.isNotBlank() }
            }
        }

        val trailer = details.optJSONObject("videos")?.optJSONArray("results")?.let { videos ->
            (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
                .firstOrNull { it.optString("site") == "YouTube" && it.optString("type") == "Trailer" }
                ?.optString("key")
        }?.let { "https://www.youtube.com/watch?v=$it" }

        val recommendations = details.optJSONObject("recommendations")?.optJSONArray("results")?.let { results ->
            (0 until results.length()).mapNotNull { i ->
                results.optJSONObject(i)?.let { TmdbCatalog.toItem(it)?.toSearchResponse() }
            }
        }.orEmpty()

        val plot = buildString {
            item.overview?.let { append(it).append("\n\n") }
            append(SourceHub.sourcesSummary())
        }

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
                this.score = Score.from10(item.rating10)
                this.duration = details.optInt("runtime").takeIf { it > 0 }
                this.recommendations = recommendations
                addActors(actors)
                addTMDbId(id.id)
                addImdbId(imdbId)
                addTrailer(trailer)
            }
        }

        // Série : on construit les épisodes à partir des saisons TMDB.
        val seasonNumbers = details.optJSONArray("seasons")?.let { seasons ->
            (0 until seasons.length()).mapNotNull { i ->
                seasons.optJSONObject(i)?.optInt("season_number")
            }.filter { it > 0 }
        }.orEmpty().ifEmpty { listOf(1) }

        val episodes = coroutineScope {
            seasonNumbers.take(40).map { seasonNumber ->
                async { buildSeason(id, seasonNumber, titles, item.year, imdbId) }
            }.awaitAll().flatten()
        }

        return newTvSeriesLoadResponse(
            item.title,
            id.serialize(),
            if (isAnimation && isJapanese) TvType.Anime else if (isAnimation) TvType.Cartoon else TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = item.posterUrl
            this.backgroundPosterUrl = item.backdropUrl
            this.year = item.year
            this.plot = plot
            this.tags = genres
            this.score = Score.from10(item.rating10)
            this.recommendations = recommendations
            addActors(actors)
            addTMDbId(id.id)
            addImdbId(imdbId)
            addTrailer(trailer)
        }
    }

    private suspend fun buildSeason(
        id: CatalogId,
        seasonNumber: Int,
        titles: List<String>,
        year: Int?,
        imdbId: String?
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
                tmdbId = id.id.toIntOrNull(),
                imdbId = imdbId
            )

            newEpisode(payload.serialize()) {
                this.name = episodeJson.optString("name").takeIf { it.isNotBlank() }
                this.season = seasonNumber
                this.episode = episodeNumber
                this.posterUrl = TmdbCatalog.image(episodeJson.optString("still_path"), "w300")
                this.description = episodeJson.optString("overview").takeIf { it.isNotBlank() }
                this.runTime = episodeJson.optInt("runtime").takeIf { it > 0 }
            }
        }
    }

    private suspend fun loadAnime(id: CatalogId): LoadResponse? {
        val media = AniListCatalog.details(id.id) ?: return null
        val item = AniListCatalog.item(media) ?: return null
        val titles = AniListCatalog.allTitles(media).ifEmpty { item.titles }
        val malId = media.optInt("idMal").takeIf { it > 0 }
        val genres = media.optJSONArray("genres")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { g -> g.isNotBlank() } }
        }.orEmpty()

        val plot = buildString {
            item.overview?.let { append(it).append("\n\n") }
            append(SourceHub.sourcesSummary())
        }

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
                this.score = Score.from10(item.rating10)
                addAniListId(id.id.toIntOrNull())
                addMalId(malId)
            }
        }

        val count = media.optInt("episodes").takeIf { it > 0 } ?: 24
        val episodes = (1..count).map { number ->
            val payload = PlayPayload(
                kind = "anime",
                titles = titles,
                year = item.year,
                season = 1,
                episode = number,
                absoluteEpisode = number,
                anilistId = id.id.toIntOrNull(),
                malId = malId
            )
            newEpisode(payload.serialize()) {
                this.name = "Épisode $number"
                this.season = 1
                this.episode = number
            }
        }

        return newAnimeLoadResponse(item.title, id.serialize(), TvType.Anime) {
            this.posterUrl = item.posterUrl
            this.backgroundPosterUrl = item.backdropUrl
            this.year = item.year
            this.plot = plot
            this.tags = genres
            this.score = Score.from10(item.rating10)
            this.episodes = mutableMapOf(
                DubStatus.Dubbed to episodes,
                DubStatus.Subbed to episodes
            )
            addAniListId(id.id.toIntOrNull())
            addMalId(malId)
        }
    }

    private suspend fun loadMalAnime(id: CatalogId): LoadResponse? {
        val data = JikanCatalog.details(id.id) ?: return null
        val item = JikanCatalog.item(data) ?: return null
        val titles = JikanCatalog.allTitles(data).ifEmpty { item.titles }
        val malId = id.id.toIntOrNull()
        val genres = data.optJSONArray("genres")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
        }.orEmpty()

        val plot = buildString {
            item.overview?.let { append(it).append("\n\n") }
            append(SourceHub.sourcesSummary())
        }

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
                this.score = Score.from10(item.rating10)
                addMalId(malId)
            }
        }

        val count = data.optInt("episodes").takeIf { it > 0 } ?: 24
        val episodes = (1..count).map { number ->
            val payload = PlayPayload(
                kind = "anime",
                titles = titles,
                year = item.year,
                season = 1,
                episode = number,
                absoluteEpisode = number,
                malId = malId
            )
            newEpisode(payload.serialize()) {
                this.name = "Épisode $number"
                this.season = 1
                this.episode = number
            }
        }

        return newAnimeLoadResponse(item.title, id.serialize(), TvType.Anime) {
            this.posterUrl = item.posterUrl
            this.year = item.year
            this.plot = plot
            this.tags = genres
            this.score = Score.from10(item.rating10)
            this.episodes = mutableMapOf(
                DubStatus.Dubbed to episodes,
                DubStatus.Subbed to episodes
            )
            addMalId(malId)
        }
    }

    // ------------------------------------------------------------------ liens

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = PlayPayload.parse(data) ?: return false
        return SourceHub.aggregateLinks(payload, subtitleCallback, callback) > 0
    }

    // ------------------------------------------------------------------ utils

    private fun CatalogItem.toSearchResponse(): SearchResponse {
        val url = id.serialize()
        return when {
            id.kind == "anime" -> newAnimeSearchResponse(title, url, TvType.Anime, fix = false) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
                this.score = Score.from10(this@toSearchResponse.rating10)
            }

            id.kind == "tv" -> newTvSeriesSearchResponse(title, url, TvType.TvSeries, fix = false) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
                this.score = Score.from10(this@toSearchResponse.rating10)
            }

            else -> newMovieSearchResponse(title, url, TvType.Movie, fix = false) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
                this.score = Score.from10(this@toSearchResponse.rating10)
            }
        }
    }

    companion object {
        const val PROVIDER_NAME = "FR Unifié"
    }
}
