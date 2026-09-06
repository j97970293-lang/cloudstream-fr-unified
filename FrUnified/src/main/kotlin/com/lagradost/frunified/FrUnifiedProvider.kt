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
import com.lagradost.cloudstream3.MainPageData
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
 * FR Hub — UN catalogue, TOUTES les sources françaises.
 *
 * Le nom dit ce que fait l'extension : c'est un HUB, pas un site. Elle
 * n'héberge aucun scraper et ne connaît aucun site de streaming en propre ;
 * elle agrège les extensions FR installées dans CloudStream et les addons
 * Stremio configurés, derrière un catalogue TMDB/AniList unique.
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

    /**
     * Rangées issues des addons Stremio, mémorisées dans les réglages
     * (« addon#type#id#nom ») pour éviter tout appel réseau à la construction
     * de l'accueil.
     */
    private fun stremioRows(): List<Pair<String, String>> =
        if (!FrSettings.useStremioCatalog) emptyList()
        else FrSettings.stremioCatalogRows.mapNotNull { line ->
            // « addon#type#id#nom#extra » (extra facultatif, ex. genre=Action)
            val parts = line.split("#")
            if (parts.size < 4) return@mapNotNull null
            val addon = parts[0]
            val type = parts[1]
            val id = parts[2]
            val name = parts[3]
            val extra = parts.getOrNull(4).orEmpty()
            ("stremio|$addon#$type#$id#$extra") to name
        }

    override val mainPage: List<MainPageData>
        get() {
            val stremio = stremioRows().map { (data, name) ->
                MainPageData(name = name, data = data)
            }
            // Rangée « pont » : elle interroge les addons EN DIRECT au moment de
            // l'affichage. Sans elle, un catalogue ajouté n'apparaissait qu'après
            // « Détecter » PUIS un redémarrage complet de CloudStream, car
            // `mainPage` n'est lu qu'au chargement du plugin.
            val live = if (FrSettings.useStremioCatalog && stremio.isEmpty())
                listOf(MainPageData(name = "📺 Catalogues Stremio", data = "stremiolive|"))
            else emptyList()

            val ordered = if (FrSettings.stremioCatalogFirst) live + stremio + BASE_PAGE
            else BASE_PAGE + live + stremio
            return ordered.filter { FrSettings.isRowEnabled(it.data) }
                .ifEmpty { ordered }
        }


    /** Exécute un bloc en avalant toute erreur de liaison (API absente selon la version). */
    private inline fun safe(block: () -> Unit) {
        runCatching { block() }
    }

    // ---------------------------------------------------------------- accueil

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (catalog, target) = request.data.split("|", limit = 2)
            .let { it[0] to it.getOrElse(1) { "" } }

        val items = when (catalog) {
            "stremiolive" -> stremioLiveRow(page)
            "stremio" -> stremioRow(target, page)
            "anime" -> animeRowUnified(target, page)
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

    /**
     * Fiche issue directement d'un addon Stremio (entrée absente de TMDB).
     *
     * On interroge l'endpoint `/meta/<type>/<id>.json` de l'addon, comme le
     * fait StremioC. Si l'addon ne répond pas, on tente une dernière fois de
     * rattacher le titre à TMDB avant d'abandonner.
     */
    private suspend fun loadStremioMeta(id: CatalogId): LoadResponse? {
        val addons = (FrSettings.stremioCatalogUrls + FrSettings.stremioUrls).distinct()
        val meta = addons.firstNotNullOfOrNull { addon ->
            runCatching { StremioClient.meta(addon, id.kind, id.id) }.getOrNull()
        }

        if (meta == null) {
            // Repli : peut-être que TMDB connaît ce titre finalement.
            val fallback = runCatching { TmdbCatalog.searchBest(id.id, null) }.getOrNull()
            if (fallback != null) return loadTmdb(fallback.id)
            return errorResponse(
                "Fiche Stremio introuvable : l'addon n'a pas répondu pour « ${id.id} ». " +
                    "Vérifiez que l'addon de catalogue est toujours configuré dans ⚙️.",
                id.serialize()
            )
        }

        val payload = PlayPayload(
            kind = if (meta.isSeries) "tv" else "movie",
            titles = listOf(meta.name),
            year = meta.year,
            imdbId = meta.imdbId
        )

        if (!meta.isSeries) {
            return newMovieLoadResponse(meta.name, id.serialize(), TvType.Movie, payload.serialize()) {
                this.posterUrl = meta.poster
                this.plot = meta.description
                this.year = meta.year
                safe { addImdbId(meta.imdbId) }
            }
        }

        val episodes = meta.videos.map { v ->
            newEpisode(
                payload.copy(season = v.season, episode = v.episode).serialize()
            ) {
                this.name = v.title
                this.season = v.season
                this.episode = v.episode
            }
        }
        return newTvSeriesLoadResponse(meta.name, id.serialize(), TvType.TvSeries, episodes) {
            this.posterUrl = meta.poster
            this.plot = meta.description
            this.year = meta.year
            safe { addImdbId(meta.imdbId) }
        }
    }

    /**
     * Rangée d'animés AniList/MAL ramenée à la structure TMDB.
     *
     * AniList publie une fiche PAR SAISON (« … Season 3 »). Affichées telles
     * quelles, ces entrées cassaient la cohérence de l'accueil : on voyait
     * « Mushoku Tensei Season 3 » à côté de fiches TMDB regroupant toutes les
     * saisons. On retraduit donc chaque entrée vers sa fiche TMDB (titre
     * débarrassé du marqueur de saison), et on ne conserve l'entrée AniList
     * d'origine que si TMDB ne connaît pas la série.
     */
    private suspend fun animeRowUnified(target: String, page: Int): List<CatalogItem> = coroutineScope {
        val raw = AnimeCatalog.row(target, page)
        if (raw.isEmpty()) return@coroutineScope emptyList()

        val resolved = raw.map { item ->
            async {
                val baseTitle = TitleMatch.stripSeason(item.title)
                val viaTmdb = runCatching { TmdbCatalog.searchBest(baseTitle, null) }.getOrNull()
                viaTmdb ?: item
            }
        }.awaitAll()

        // Une même série découpée en 3 saisons converge vers la même fiche TMDB :
        // on ne la garde qu'une fois.
        resolved.distinctBy { it.id.serialize() }
    }

    /**
     * Découvre les catalogues des addons et renvoie leurs entrées, EN DIRECT.
     *
     * C'est l'approche de StremioC / SectionProvider : le manifeste est lu au
     * moment d'afficher l'accueil, pas mémorisé à l'avance. Un addon ajouté
     * fonctionne donc immédiatement, sans bouton « Détecter » ni redémarrage.
     */
    private suspend fun stremioLiveRow(page: Int): List<CatalogItem> = coroutineScope {
        val addons = (FrSettings.stremioCatalogUrls + FrSettings.stremioUrls).distinct()
        if (addons.isEmpty()) return@coroutineScope emptyList()

        val rows = addons.map { addon ->
            async { runCatching { StremioClient.catalogs(addon) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
        if (rows.isEmpty()) return@coroutineScope emptyList()

        // On agrège les premières rangées : l'accueil doit rester rapide.
        rows.take(4).map { row ->
            async { runCatching { StremioClient.catalogItems(row, page) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten().map { meta ->
            CatalogItem(
                id = CatalogId("stremio", meta.type, meta.id),
                title = meta.name,
                originalTitle = null,
                year = meta.year,
                posterUrl = meta.poster,
                backdropUrl = null,
                overview = null,
                rating10 = null
            )
        }.distinctBy { it.id.serialize() }
    }

    /**
     * Convertit une rangée d'addon Stremio en fiches du catalogue habituel.
     *
     * Les entrées Stremio portent un identifiant IMDb : on le retraduit en
     * fiche TMDB pour conserver UNE fiche par série (saisons à l'intérieur) et
     * garder le même comportement de lecture que le reste de l'accueil.
     * Si la correspondance échoue, l'entrée est ignorée plutôt que d'afficher
     * une fiche qui ne s'ouvrirait pas.
     */
    private suspend fun stremioRow(target: String, page: Int): List<CatalogItem> = coroutineScope {
        val parts = target.split("#")
        if (parts.size < 3) return@coroutineScope emptyList()
        val extra = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        val row = StremioClient.CatalogRow(parts[0], parts[1], parts[2], "", extra)
        val metas = StremioClient.catalogItems(row, page)
        if (metas.isEmpty()) return@coroutineScope emptyList()

        metas.map { meta ->
            async {
                // Les addons n'utilisent pas tous IMDb : AIO Metadata et les
                // addons TMDB publient « tmdb:1399 », les addons d'animés
                // « kitsu:… ». On traite chaque cas plutôt que de tout jeter.
                val tmdbId = meta.tmdbId
                val imdb = meta.imdbId
                val viaTmdb = runCatching {
                    when {
                        tmdbId != null -> TmdbCatalog.byTmdbId(tmdbId, meta.type)
                        imdb != null -> TmdbCatalog.byImdb(imdb, meta.type)
                        else -> TmdbCatalog.searchBest(
                            TitleMatch.stripSeason(meta.name), meta.year
                        )
                    }
                }.getOrNull()

                // NE JAMAIS jeter une entrée que TMDB ne connaît pas : c'est ce
                // qui vidait entièrement les rangées (AIO Metadata publie des
                // identifiants absents de TMDB, et sans clé TMDB valide TOUTES
                // les entrées disparaissaient). On retombe sur la fiche fournie
                // par l'addon lui-même, exactement comme le fait StremioC.
                viaTmdb ?: CatalogItem(
                    id = CatalogId("stremio", meta.type, meta.id),
                    title = meta.name,
                    originalTitle = null,
                    year = meta.year,
                    posterUrl = meta.poster,
                    backdropUrl = null,
                    overview = null,
                    rating10 = null
                )
            }
        }.awaitAll().filterNotNull().distinctBy { it.id.serialize() }
    }

    // -------------------------------------------------------------- recherche

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val tmdb = async {
            if (FrSettings.useTmdbCatalog) runCatching { TmdbCatalog.search(query) }.getOrDefault(emptyList())
            else emptyList()
        }
        val anime = async {
            if (FrSettings.useAnimeCatalog) runCatching { AnimeCatalog.search(query) }.getOrDefault(emptyList())
            else emptyList()
        }

        val tmdbItems = tmdb.await()

        // TMDB fait autorité : il expose UNE fiche par série, saisons à
        // l'intérieur. AniList/MAL découpent au contraire chaque saison en une
        // fiche distincte (« One Piece », « One Piece Saison 2 »…), ce qui
        // produisait trois entrées pour une même série.
        //
        // `TitleMatch.normalize` retire déjà les marqueurs de saison : deux
        // fiches AniList d'une même série se réduisent donc au même titre, et
        // seule la première survit. Si TMDB connaît déjà la série, aucune
        // fiche AniList n'est ajoutée.
        val seen = tmdbItems.map { TitleMatch.normalize(it.title) }.toMutableSet()
        val animeItems = anime.await().filter { item ->
            val key = TitleMatch.normalize(item.title)
            key.isNotBlank() && seen.add(key)
        }

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
                "stremio" -> loadStremioMeta(id)
                else -> loadTmdb(id)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            errorResponse(
                "Erreur interne FR Hub : ${t::class.simpleName ?: t.javaClass.simpleName} — " +
                    (t.message?.take(240) ?: "détail inconnu"),
                url
            )
        }
    }

    private suspend fun errorResponse(message: String, url: String): LoadResponse? = runCatching {
        newMovieLoadResponse("FR Hub — diagnostic", url, TvType.Movie, "{}") {
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

        // Les animés en cours de diffusion (One Piece…) renvoient `episodes: null`
        // sur AniList : sans ce repli, on n'affichait que 24 épisodes et les
        // épisodes récents étaient tout simplement absents de la fiche.
        val airing = media.optJSONObject("nextAiringEpisode")
            ?.optInt("episode")?.takeIf { it > 1 }?.minus(1)
        val count = media.optInt("episodes").takeIf { it > 0 }
            ?: airing
            ?: malId?.let { JikanCatalog.airedEpisodeCount(it.toString()) }
            ?: 24
        val dubbed = animeEpisodes(titles, item.year, count, anilistId = id.id.toIntOrNull(), malId = malId, dub = "dub")
        val subbed = animeEpisodes(titles, item.year, count, anilistId = id.id.toIntOrNull(), malId = malId, dub = "sub")

        return newAnimeLoadResponse(item.title, id.serialize(), TvType.Anime) {
            this.posterUrl = item.posterUrl
            this.backgroundPosterUrl = item.backdropUrl
            this.year = item.year
            this.plot = plot
            this.tags = genres
            safe { this.score = Score.from10(item.rating10) }
            safe {
                this.episodes = mutableMapOf(
                    DubStatus.Dubbed to dubbed,
                    DubStatus.Subbed to subbed
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

        // Même piège que sur AniList : un animé encore diffusé ("Currently
        // Airing") a `episodes: null` chez Jikan. On garde alors une fiche
        // large plutôt que de tronquer à 24 épisodes.
        val count = data.optInt("episodes").takeIf { it > 0 }
            ?: JikanCatalog.airedEpisodeCount(id.id)
            ?: 24
        val dubbed = animeEpisodes(titles, item.year, count, anilistId = null, malId = malId, dub = "dub")
        val subbed = animeEpisodes(titles, item.year, count, anilistId = null, malId = malId, dub = "sub")

        return newAnimeLoadResponse(item.title, id.serialize(), TvType.Anime) {
            this.posterUrl = item.posterUrl
            this.year = item.year
            this.plot = plot
            this.tags = genres
            safe { this.score = Score.from10(item.rating10) }
            safe {
                this.episodes = mutableMapOf(
                    DubStatus.Dubbed to dubbed,
                    DubStatus.Subbed to subbed
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
        malId: Int?,
        dub: String? = null
    ): List<Episode> = (1..count).map { number ->
        val payload = PlayPayload(
            kind = "anime",
            titles = titles,
            year = year,
            season = 1,
            episode = number,
            absoluteEpisode = number,
            anilistId = anilistId,
            malId = malId,
            dub = dub
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
            FrSettings.activeStreamAddons.forEach { addon ->
                linkJobs += async {
                    runCatching {
                        withTimeoutOrNull(45_000L) { StremioClient.streams(addon, payload, callback) } ?: false
                    }.getOrDefault(false)
                }
            }
        }

        // 3. Sous-titres externes (n'entrent pas dans le décompte des liens)
        if (FrSettings.useSubtitles) {
            (FrSettings.activeStreamAddons + FrSettings.DEFAULT_SUBTITLE_ADDON).distinct().forEach { addon ->
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
        const val PROVIDER_NAME = "FR Hub"

        /** Rangées du catalogue d'origine, exposées pour l'écran ⚙️. */
        val BASE_PAGE = mainPageOf(
        "tmdb|trending/all/week" to "🔥 Tendances de la semaine",
        "tmdb|movie/popular" to "🎬 Films populaires",
        "tmdb|movie/now_playing" to "🍿 Films récents",
        "tmdb|discover/movie?with_original_language=fr&sort_by=popularity.desc" to "🇫🇷 Films français",
        "tmdb|tv/popular" to "📺 Séries populaires",
        "tmdb|tv/on_the_air" to "🆕 Séries en cours",
        "tmdb|discover/tv?with_original_language=fr&sort_by=popularity.desc" to "🇫🇷 Séries françaises",
        // Sections animés servies par TMDB : une SEULE fiche par série, avec les
        // saisons à l'intérieur (genre 16 = Animation, origine JP).
        "tmdb|discover/tv?with_genres=16&with_origin_country=JP&sort_by=popularity.desc" to "🌸 Animés populaires",
        "tmdb|discover/tv?with_genres=16&with_origin_country=JP&sort_by=first_air_date.desc&vote_count.gte=20" to "🆕 Animés récents",
        "tmdb|discover/tv?with_genres=16&with_origin_country=JP&sort_by=vote_average.desc&vote_count.gte=200" to "⭐ Animés les mieux notés",
        "tmdb|discover/movie?with_genres=16&with_origin_country=JP&sort_by=popularity.desc" to "🎞️ Films d'animation japonais",
        // Repli AniList/MAL : découpage par saison, utile pour ce que TMDB ignore.
        "anime|trending" to "🌸 Animés de la saison (AniList)",
        "tmdb|discover/movie?with_genres=16&sort_by=popularity.desc" to "🧸 Animation / jeunesse",
        "tmdb|movie/top_rated" to "⭐ Films les mieux notés",
        "tmdb|tv/top_rated" to "⭐ Séries les mieux notées"
        )
    }
}
