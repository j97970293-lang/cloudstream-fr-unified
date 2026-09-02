package com.lagradost.frunified

import org.json.JSONObject

/**
 * Identifiant universel du catalogue unique.
 *
 * Toutes les fiches affichées par l'extension viennent d'UN SEUL catalogue
 * (TMDB pour films/séries, AniList pour l'animé). Un [CatalogId] encode la
 * provenance du catalogue, jamais la source de streaming : les sources sont
 * résolues à la volée au moment de la lecture.
 */
data class CatalogId(
    val catalog: String, // "tmdb" | "anilist"
    val kind: String,    // "movie" | "tv" | "anime"
    val id: String
) {
    fun serialize(): String = "$catalog:$kind:$id"

    companion object {
        fun parse(raw: String): CatalogId? {
            val parts = raw.substringAfter("frunified://").split(":")
            if (parts.size < 3) return null
            return CatalogId(parts[0], parts[1], parts.drop(2).joinToString(":"))
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
