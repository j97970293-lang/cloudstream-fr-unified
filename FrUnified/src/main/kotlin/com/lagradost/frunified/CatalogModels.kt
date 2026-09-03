package com.lagradost.frunified

import org.json.JSONObject

/**
 * Identifiant universel du catalogue unique.
 *
 * Toutes les fiches affichées par l'extension viennent d'UN SEUL catalogue
 * (TMDB pour films/séries, AniList pour l'animé). Un [CatalogId] encode la
 * provenance du catalogue, jamais la source de streaming : les sources sont
 * résolues à la volée au moment de la lecture.
 *
 * IMPORTANT : [serialize] produit une vraie URL http. CloudStream applique
 * `fixUrl()` aux URLs des fiches ; un identifiant « tmdb:movie:123 » était
 * transformé en « NONE/tmdb:movie:123 » et `load()` ne le reconnaissait pas
 * (→ « Error loading, try again later. »). Une URL http n'est jamais modifiée.
 */
data class CatalogId(
    val catalog: String, // "tmdb" | "anilist" | "mal"
    val kind: String,    // "movie" | "tv" | "anime"
    val id: String
) {
    fun serialize(): String = "https://frunified.fr/$catalog/$kind/$id"

    companion object {
        private val CATALOGS = setOf("tmdb", "anilist", "mal")

        private fun normalizeKind(kind: String): String = when (kind.lowercase()) {
            "film", "movie", "movies" -> "movie"
            "series", "serie", "serie", "show", "shows", "tvseries" -> "tv"
            "anime", "animes" -> "anime"
            else -> kind.lowercase()
        }

        /**
         * Comprend tous les formats :
         *  - https://frunified.fr/tmdb/movie/860508        (format actuel)
         *  - tmdb:movie:860508                             (v1/v2, cassé par fixUrl)
         *  - frunified://tmdb:movie:860508
         *  - /tmdb:movie:860508, NONE/tmdb:movie:860508    (versions « fixées »)
         */
        fun parse(raw: String?): CatalogId? {
            if (raw.isNullOrBlank()) return null
            var path = raw.trim()

            // Enlève le schéma (https://, frunified://, stremio://…)
            val schemeIdx = path.indexOf("://")
            if (schemeIdx >= 0) {
                path = path.substring(schemeIdx + 3)
                // Enlève l'hôte s'il y en a un (https://frunified.fr/tmdb/movie/1)
                val slash = path.indexOf('/')
                path = if (slash >= 0) path.substring(slash + 1) else path
            }

            // Enlève les préfixes parasites (« NONE/ », « / », « tmdb.org/ »…)
            path = path.trimStart('/')
            path = path.substringAfterLast("frunified/", path)
            path = path.substringAfterLast("themoviedb.org/", path)

            val parts = if (path.contains(":")) path.split(":") else path.split("/")
            if (parts.size < 3) return null

            var catalog = parts[0].trim().lowercase()
            if (catalog !in CATALOGS) {
                // Dernier recours : un préfixe inconnu (« NONE/ », « / »…) devant « tmdb:… »
                catalog = catalog.substringAfterLast('/').trim()
                if (catalog !in CATALOGS) {
                    val known = parts.firstOrNull { it.substringAfterLast('/').trim().lowercase() in CATALOGS }
                        ?: return null
                    catalog = known.substringAfterLast('/').trim().lowercase()
                }
            }
            val kind = normalizeKind(parts[1])
            val id = parts.drop(2).joinToString(":").trim()
            if (id.isBlank()) return null
            return CatalogId(catalog, kind, id)
        }
    }
}

/**
 * Charge utile passée à `loadLinks`. Elle contient tout ce dont le
 * résolveur multi-sources a besoin pour retrouver le même contenu sur
 * n'importe quelle extension française installée.
 */
data class PlayPayload(
    val kind: String,
    val titles: List<String>,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val absoluteEpisode: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val anilistId: Int? = null,
    val malId: Int? = null
) {
    fun serialize(): String = JSONObject().apply {
        put("kind", kind)
        put("titles", titles.joinToString("|"))
        year?.let { put("year", it) }
        season?.let { put("season", it) }
        episode?.let { put("episode", it) }
        absoluteEpisode?.let { put("abs", it) }
        tmdbId?.let { put("tmdb", it) }
        imdbId?.let { put("imdb", it) }
        anilistId?.let { put("anilist", it) }
        malId?.let { put("mal", it) }
    }.toString()

    val isSeries: Boolean get() = kind != "movie"
    val primaryTitle: String get() = titles.firstOrNull().orEmpty()

    companion object {
        fun parse(raw: String): PlayPayload? = runCatching {
            val json = JSONObject(raw)
            PlayPayload(
                kind = json.optString("kind", "movie"),
                titles = json.optString("titles").split("|").filter { it.isNotBlank() },
                year = json.optInt("year").takeIf { it > 0 },
                season = if (json.has("season")) json.optInt("season") else null,
                episode = if (json.has("episode")) json.optInt("episode") else null,
                absoluteEpisode = if (json.has("abs")) json.optInt("abs") else null,
                tmdbId = json.optInt("tmdb").takeIf { it > 0 },
                imdbId = json.optString("imdb").takeIf { it.isNotBlank() },
                anilistId = json.optInt("anilist").takeIf { it > 0 },
                malId = json.optInt("mal").takeIf { it > 0 }
            )
        }.getOrNull()
    }
}
