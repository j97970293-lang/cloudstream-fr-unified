package com.lagradost.frunified

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Une entrée du catalogue unique, indépendante de toute source de streaming. */
data class CatalogItem(
    val id: CatalogId,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String?,
    val rating10: Double?
) {
    val titles: List<String>
        get() = listOfNotNull(title, originalTitle).distinct()
}

/**
 * Catalogue TMDB (films + séries + dessins animés), en français.
 * C'est LE catalogue de l'extension : la recherche, l'accueil et les fiches
 * n'utilisent que cette source de métadonnées.
 */
object TmdbCatalog {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"

    /** Clé publique communautaire (v3), la même que celle utilisée par les addons FR existants. */
    private val API_KEY: String get() = FrSettings.tmdbApiKey

    private const val CACHE_TTL = 15 * 60 * 1000L
    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val all = linkedMapOf(
            "api_key" to API_KEY,
            "language" to "fr-FR",
            "region" to "FR",
            "include_adult" to "false"
        )
        all.putAll(params)
        return "$API/${path.trimStart('/')}?" + all.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val key = url(path, params)
        val now = System.currentTimeMillis()
        cache[key]?.let { (expiry, value) -> if (expiry > now) return value }

        val json = runCatching { JSONObject(app.get(key, timeout = 20).text) }.getOrNull() ?: return null
        cache[key] = (now + CACHE_TTL) to json
        return json
    }

    fun image(path: String?, size: String = "w500"): String? =
        path?.takeIf { it.isNotBlank() && it != "null" }?.let { "$IMG/$size$it" }

    fun toItem(json: JSONObject, forcedKind: String? = null): CatalogItem? {
        val id = json.optInt("id").takeIf { it > 0 } ?: return null
        val mediaType = forcedKind ?: json.optString("media_type").ifBlank {
            if (json.has("title") || json.has("release_date")) "movie" else "tv"
        }
        if (mediaType != "movie" && mediaType != "tv") return null

        val title = json.optString("title").ifBlank { json.optString("name") }.ifBlank { return null }
        val original = json.optString("original_title").ifBlank { json.optString("original_name") }
            .takeIf { it.isNotBlank() && !it.equals(title, true) }
        val date = json.optString("release_date").ifBlank { json.optString("first_air_date") }

        return CatalogItem(
            id = CatalogId("tmdb", mediaType, id.toString()),
            title = title,
            originalTitle = original,
            year = date.take(4).toIntOrNull(),
            posterUrl = image(json.optString("poster_path")),
            backdropUrl = image(json.optString("backdrop_path"), "w1280"),
            overview = json.optString("overview").takeIf { it.isNotBlank() },
            rating10 = json.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 }
        )
    }

    private fun list(json: JSONObject?, forcedKind: String? = null): List<CatalogItem> {
        val results: JSONArray = json?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            results.optJSONObject(i)?.let { toItem(it, forcedKind) }
        }
    }

    suspend fun search(query: String, page: Int = 1): List<CatalogItem> =
        list(get("search/multi", mapOf("query" to query, "page" to page.toString())))
            .filter { it.title.isNotBlank() }

    /** Meilleure correspondance de recherche (pour retrouver un ID TMDB à partir d'un titre). */
    suspend fun searchBest(query: String, year: Int?): CatalogItem? {
        val items = runCatching { search(query, 1) }.getOrDefault(emptyList())
        if (items.isEmpty()) return null
        if (items.size == 1) return items.first()
        val best = items.maxByOrNull { TitleMatch.score(listOf(query), it.title, year, it.year) } ?: return null
        return best.takeIf { TitleMatch.score(listOf(query), it.title, year, it.year) >= 0.55 }
    }

    suspend fun row(path: String, page: Int, params: Map<String, String> = emptyMap(), kind: String? = null): List<CatalogItem> =
        list(get(path, params + mapOf("page" to page.toString())), kind)

    suspend fun details(id: CatalogId): JSONObject? {
        val append = if (id.kind == "tv") {
            "credits,videos,external_ids,recommendations,content_ratings,aggregate_credits"
        } else {
            "credits,videos,external_ids,recommendations,release_dates"
        }
        return get("${id.kind}/${id.id}", mapOf("append_to_response" to append))
    }

    suspend fun alternativeTitles(id: CatalogId): List<String> {
        val json = get("${id.kind}/${id.id}/alternative_titles") ?: return emptyList()
        val array = json.optJSONArray("titles") ?: json.optJSONArray("results") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val entry = array.optJSONObject(i) ?: return@mapNotNull null
            val country = entry.optString("iso_3166_1")
            if (country == "FR" || country == "BE" || country == "CA" || country == "US" || country == "GB") {
                entry.optString("title").takeIf { it.isNotBlank() }
            } else null
        }.distinct()
    }

    suspend fun season(seriesId: String, season: Int): JSONObject? = get("tv/$seriesId/season/$season")
}
