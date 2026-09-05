package com.lagradost.frunified

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * Client d'addons Stremio.
 *
 * Permet d'ajouter n'importe quel addon compatible Stremio (Torrentio, Comet,
 * MediaFusion, Orion, un debrid personnel, l'addon OpenSubtitles…) comme
 * source de liens ou de sous-titres, simplement en collant son URL dans les
 * réglages du plugin.
 */
object StremioClient {

    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce"
    )

    /** Normalise « …/manifest.json », « …/ » ou « … » vers l'URL de base de l'addon. */
    fun base(raw: String): String = raw.trim()
        .removeSuffix("/")
        .removeSuffix("/manifest.json")
        .removeSuffix("/")
        .replace("stremio://", "https://")

    // ------------------------------------------------------- catalogues

    /** Une rangée de catalogue exposée par un addon Stremio. */
    data class CatalogRow(
        val addon: String,
        val type: String,   // "movie" | "series" | "anime"…
        val id: String,
        val name: String,
        /** Extra obligatoire résolu, ex. « genre=Action ». */
        val extra: String? = null
    ) {
        /** Encodé dans `mainPage` : `stremio|<addon>#<type>#<id>#<extra>`. */
        fun encode(): String = "stremio|$addon#$type#$id"
    }

    /** Dernier diagnostic de détection, par addon (pour l'écran ⚙️). */
    val lastCatalogError = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Lit le manifeste d'un addon et retourne les catalogues qu'il publie.
     * Un catalogue exigeant une recherche libre est ignoré : il ne peut pas
     * alimenter une rangée d'accueil.
     */
    suspend fun catalogs(addon: String): List<CatalogRow> = runCatching {
        val base = base(addon)
        // AIO Metadata agrège des dizaines de sources en amont : sa première
        // réponse peut dépasser 30 s (surtout à froid). Un timeout de 20 s le
        // déclarait « injoignable » à tort. On laisse 60 s et on réessaie une
        // fois, car un addon endormi répond souvent au second appel.
        val raw = run {
            var last: Throwable? = null
            for (attempt in 1..2) {
                val r = runCatching { app.get("$base/manifest.json", timeout = 60).text }
                if (r.isSuccess) return@run r.getOrThrow()
                last = r.exceptionOrNull()
            }
            val msg = last?.message.orEmpty()
            lastCatalogError[base] = when {
                msg.contains("NXDOMAIN", true) || msg.contains("UnknownHost", true) ->
                    "ce domaine n'existe plus — supprimez cet addon"
                msg.contains("timeout", true) || msg.contains("timed out", true) ->
                    "trop lent, même après 60 s (addon surchargé ou en veille) — réessayez"
                else -> "injoignable (${msg.take(60)})"
            }
            return emptyList()
        }
        if (!raw.trimStart().startsWith("{")) {
            // Page HTML de configuration au lieu du manifeste : URL incomplète.
            lastCatalogError[base] =
                "ce n'est pas un manifeste JSON — l'URL doit finir par /manifest.json"
            return emptyList()
        }
        val manifest = JSONObject(raw)
        val addonName = manifest.optString("name").takeIf { it.isNotBlank() } ?: "Stremio"
        val array = manifest.optJSONArray("catalogs")
        if (array == null || array.length() == 0) {
            lastCatalogError[base] = "cet addon ne publie aucun catalogue (flux uniquement)"
            return emptyList()
        }

        (0 until array.length()).mapNotNull { i ->
            val cat = array.optJSONObject(i) ?: return@mapNotNull null
            val type = cat.optString("type").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val id = cat.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            // Un « extra » obligatoire ne condamne plus le catalogue : beaucoup
            // d'addons (AIO Metadata…) exigent un `genre` tout en fournissant la
            // liste de ses valeurs. On prend alors la première option proposée.
            // Seul un extra obligatoire SANS options (une recherche libre) reste
            // inutilisable pour une rangée d'accueil.
            var extraArg: String? = null
            var impossible = false

            // Format moderne : "extra": [ { name, isRequired, options } ]
            cat.optJSONArray("extra")?.let { extras ->
                for (j in 0 until extras.length()) {
                    val ex = extras.optJSONObject(j) ?: continue
                    if (!ex.optBoolean("isRequired", false)) continue
                    val exName = ex.optString("name")
                    val opts = ex.optJSONArray("options")
                    val first = (0 until (opts?.length() ?: 0))
                        .mapNotNull { k -> opts?.optString(k)?.takeIf { it.isNotBlank() } }
                        .firstOrNull()
                    if (exName.isNotBlank() && first != null) {
                        extraArg = "$exName=$first"
                    } else if (exName.equals("search", true)) {
                        impossible = true          // recherche libre : inutilisable en rangée
                    } else if (exName.isNotBlank()) {
                        // Obligatoire sans options : on tente les genres usuels
                        extraArg = "$exName=Action"
                    }
                }
            }

            // Format hérité : "extraRequired": ["genre"], "genres": [...]
            cat.optJSONArray("extraRequired")?.let { req ->
                for (j in 0 until req.length()) {
                    val exName = req.optString(j).takeIf { it.isNotBlank() } ?: continue
                    if (exName.equals("search", true)) { impossible = true; continue }
                    val opts = cat.optJSONArray("genres")
                        ?: cat.optJSONArray("options")
                    val first = (0 until (opts?.length() ?: 0))
                        .mapNotNull { k -> opts?.optString(k)?.takeIf { it.isNotBlank() } }
                        .firstOrNull()
                    extraArg = if (first != null) "$exName=$first" else "$exName=Action"
                }
            }
            if (impossible) return@mapNotNull null

            val label = cat.optString("name").takeIf { it.isNotBlank() } ?: id
            val suffix = extraArg?.let { " (" + it.substringAfter("=") + ")" }.orEmpty()
            CatalogRow(base, type, id, "$addonName · $label$suffix", extraArg)
        }.also {
            lastCatalogError[base] =
                if (it.isEmpty()) "${array.length()} catalogue(s) annoncé(s), aucun exploitable"
                else "${it.size} catalogue(s)"
        }
    }.getOrElse {
        lastCatalogError[base(addon)] = "erreur de lecture : ${it.message?.take(60)}"
        emptyList()
    }

    /**
     * Récupère les métadonnées d'une rangée de catalogue.
     *
     * Les entrées Stremio portent en général un identifiant IMDb (`tt…`). On le
     * conserve pour que la fiche reste rattachée au catalogue TMDB habituel :
     * une seule fiche par série, saisons à l'intérieur.
     */
    suspend fun catalogItems(row: CatalogRow, page: Int): List<StremioMeta> = runCatching {
        // Stremio pagine par tranches de 100 via l'extra « skip ».
        val skip = (page - 1) * 100
        val args = buildList {
            row.extra?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (skip > 0) add("skip=$skip")
        }
        val suffix = if (args.isEmpty()) ".json" else "/" + args.joinToString("&") + ".json"
        val url = "${row.addon}/catalog/${row.type}/${row.id}$suffix"
        val json = JSONObject(app.get(url, timeout = 25).text)
        val metas = json.optJSONArray("metas") ?: return emptyList()

        (0 until metas.length()).mapNotNull { i ->
            val meta = metas.optJSONObject(i) ?: return@mapNotNull null
            val id = meta.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = meta.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            StremioMeta(
                id = id,
                name = name,
                poster = meta.optString("poster").takeIf { it.startsWith("http") },
                type = meta.optString("type").takeIf { it.isNotBlank() } ?: row.type,
                year = Regex("(19|20)\\d{2}").find(meta.optString("releaseInfo"))
                    ?.value?.toIntOrNull()
            )
        }
    }.getOrDefault(emptyList())

    /** Fiche détaillée renvoyée par `/meta/<type>/<id>.json`. */
    data class MetaDetail(
        val name: String,
        val poster: String?,
        val description: String?,
        val year: Int?,
        val imdbId: String?,
        val videos: List<MetaVideo>
    ) {
        val isSeries: Boolean get() = videos.isNotEmpty()
    }

    data class MetaVideo(val title: String, val season: Int?, val episode: Int?)

    /**
     * Détail d'une fiche auprès de l'addon (comme le fait StremioC).
     * Permet d'afficher une entrée que TMDB ne connaît pas.
     */
    suspend fun meta(addon: String, type: String, id: String): MetaDetail? = runCatching {
        val base = base(addon)
        val encoded = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        val raw = app.get("$base/meta/$type/$encoded.json", timeout = 20).text
        if (!raw.trimStart().startsWith("{")) return null
        val m = JSONObject(raw).optJSONObject("meta") ?: return null

        val vids = m.optJSONArray("videos")
        val videos = (0 until (vids?.length() ?: 0)).mapNotNull { i ->
            val v = vids?.optJSONObject(i) ?: return@mapNotNull null
            val season = v.optInt("season", -1).takeIf { it >= 0 }
            val ep = v.optInt("episode", -1).takeIf { it >= 0 }
                ?: v.optInt("number", -1).takeIf { it >= 0 }
            MetaVideo(
                title = v.optString("title").takeIf { it.isNotBlank() }
                    ?: v.optString("name").takeIf { it.isNotBlank() }
                    ?: "Épisode ${ep ?: i + 1}",
                season = season,
                episode = ep
            )
        }

        MetaDetail(
            name = m.optString("name").takeIf { it.isNotBlank() } ?: return null,
            poster = m.optString("poster").takeIf { it.startsWith("http") },
            description = m.optString("description").takeIf { it.isNotBlank() },
            year = Regex("(19|20)\\d{2}").find(
                m.optString("year") + " " + m.optString("releaseInfo")
            )?.value?.toIntOrNull(),
            imdbId = Regex("tt\\d{6,}").find(m.optString("imdb_id") + " " + id)?.value,
            videos = videos
        )
    }.getOrNull()

    data class StremioMeta(
        val id: String,
        val name: String,
        val poster: String?,
        val type: String,
        val year: Int?
    ) {
        /** `tt123` éventuel (les addons dérivent souvent leur id d'IMDb). */
        val imdbId: String? get() = Regex("tt\\d{6,}").find(id)?.value

        /**
         * Identifiant TMDB direct : « tmdb:1399 », « tmdb-1399 ».
         * AIO Metadata et les addons TMDB n'exposent PAS d'identifiant IMDb —
         * sans cette lecture, toutes leurs entrées étaient jetées et la rangée
         * restait vide.
         */
        val tmdbId: Int? get() =
            Regex("(?i)(?:^|[^a-z])tmdb[:\\-_/]?(\\d+)").find(id)?.groupValues?.get(1)?.toIntOrNull()

        /** Identifiants d'animés (Kitsu, MAL, AniList) : résolus par titre. */
        val isAnimeId: Boolean get() =
            Regex("(?i)^(kitsu|mal|anilist|anidb)[:\\-_/]").containsMatchIn(id)
    }

    /** Identifiant Stremio : `tt123` pour un film, `tt123:1:2` pour un épisode. */
    private fun stremioId(payload: PlayPayload): Pair<String, String>? {
        val imdb = payload.imdbId?.takeIf { it.startsWith("tt") } ?: return null
        return if (payload.isSeries) {
            "series" to "$imdb:${payload.season ?: 1}:${payload.episode ?: 1}"
        } else {
            "movie" to imdb
        }
    }

    private fun qualityOf(text: String): Int = when {
        text.contains("2160", true) || text.contains("4k", true) -> Qualities.P2160.value
        text.contains("1440", true) -> Qualities.P1440.value
        text.contains("1080", true) -> Qualities.P1080.value
        text.contains("720", true) -> Qualities.P720.value
        text.contains("480", true) -> Qualities.P480.value
        text.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    /**
     * Interroge un addon et relaie ses flux.
     * @return true si au moins un lien a été émis.
     */
    suspend fun streams(addon: String, payload: PlayPayload, callback: (ExtractorLink) -> Unit): Boolean {
        val (type, id) = stremioId(payload) ?: return false
        val root = runCatching {
            JSONObject(app.get("${base(addon)}/stream/$type/$id.json", timeout = 25).text)
        }.getOrNull() ?: return false

        val streams = root.optJSONArray("streams") ?: return false
        val addonName = base(addon).substringAfter("://").substringBefore("/")
        var emitted = false

        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val label = listOfNotNull(
                stream.optString("name").takeIf { it.isNotBlank() && it != "null" },
                stream.optString("title").takeIf { it.isNotBlank() && it != "null" },
                stream.optString("description").takeIf { it.isNotBlank() && it != "null" }
            ).joinToString(" ").replace("\n", " ").trim().take(120)

            val url = stream.optString("url").takeIf { it.startsWith("http") }
            val infoHash = stream.optString("infoHash").takeIf { it.length == 40 }

            val link = when {
                url != null -> runCatching {
                    newExtractorLink("Stremio · $addonName", "Stremio • $label", url) {
                        this.quality = qualityOf(label)
                        this.referer = ""
                    }
                }.getOrNull()

                infoHash != null -> runCatching {
                    val magnet = "magnet:?xt=urn:btih:$infoHash" +
                        "&dn=${payload.primaryTitle.replace(" ", "+")}" +
                        TRACKERS.joinToString("") { "&tr=$it" }
                    newExtractorLink(
                        "Stremio · $addonName",
                        "Torrent • $label",
                        magnet,
                        ExtractorLinkType.MAGNET
                    ) {
                        this.quality = qualityOf(label)
                    }
                }.getOrNull()

                else -> null
            } ?: continue

            // Mêmes filtres que pour les extensions FR : les réglages de
            // qualité / mots-clés / taille s'appliquent à toutes les sources.
            if (LinkFilter.reject(link, "Stremio") != null) continue

            emitted = true
            callback(link)
        }
        return emitted
    }

    /** Récupère les sous-titres exposés par un addon Stremio. */
    suspend fun subtitles(addon: String, payload: PlayPayload, callback: (SubtitleFile) -> Unit): Boolean {
        val (type, id) = stremioId(payload) ?: return false
        val root = runCatching {
            JSONObject(app.get("${base(addon)}/subtitles/$type/$id.json", timeout = 20).text)
        }.getOrNull() ?: return false

        val subs = root.optJSONArray("subtitles") ?: return false
        val wanted = FrSettings.subtitleLangs
        var emitted = false

        for (i in 0 until subs.length()) {
            val sub = subs.optJSONObject(i) ?: continue
            val url = sub.optString("url").takeIf { it.startsWith("http") } ?: continue
            val lang = sub.optString("lang").ifBlank { "und" }.lowercase()
            if (wanted.isNotEmpty() && wanted.none { lang.startsWith(it) }) continue

            val label = when {
                lang.startsWith("fr") -> "Français"
                lang.startsWith("en") -> "English"
                else -> lang.uppercase()
            }
            runCatching { callback(newSubtitleFile(label, url)) }.onSuccess { emitted = true }
        }
        return emitted
    }
}
