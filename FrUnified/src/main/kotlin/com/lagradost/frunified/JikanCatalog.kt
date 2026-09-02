package com.lagradost.frunified

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repli MyAnimeList (API publique Jikan) utilisé quand AniList est
 * indisponible — l'API AniList est régulièrement coupée. Le catalogue
 * reste unique côté utilisateur : seule la base de métadonnées change.
 */
object JikanCatalog {
    private const val API = "https://api.jikan.moe/v4"

    private fun toItem(json: JSONObject): CatalogItem? {
        val id = json.optInt("mal_id").takeIf { it > 0 } ?: return null
        val title = json.optString("title_english").takeIf { it.isNotBlank() && it != "null" }
            ?: json.optString("title").takeIf { it.isNotBlank() }
            ?: return null
        val original = json.optString("title").takeIf { it.isNotBlank() && !it.equals(title, true) }

        return CatalogItem(
            id = CatalogId("mal", "anime", id.toString()),
            title = title,
            originalTitle = original,
            year = json.optInt("year").takeIf { it > 0 }
                ?: json.optJSONObject("aired")?.optJSONObject("prop")?.optJSONObject("from")
                    ?.optInt("year")?.takeIf { it > 0 },
            posterUrl = json.optJSONObject("images")?.optJSONObject("jpg")
                ?.let { it.optString("large_image_url").ifBlank { it.optString("image_url") } }
                ?.takeIf { it.isNotBlank() },
            backdropUrl = null,
            overview = json.optString("synopsis").takeIf { it.isNotBlank() && it != "null" },
            rating10 = json.optDouble("score").takeIf { !it.isNaN() && it > 0 }
        )
    }

    private suspend fun fetch(path: String): JSONObject? =
        runCatching { JSONObject(app.get("$API/$path", timeout = 20).text) }.getOrNull()

    private fun list(root: JSONObject?): List<CatalogItem> {
        val data: JSONArray = root?.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.let { toItem(it) } }
    }

    suspend fun row(kind: String, page: Int): List<CatalogItem> = when (kind) {
        "trending" -> list(fetch("seasons/now?page=$page&sfw=true"))
        "top" -> list(fetch("top/anime?page=$page&sfw=true"))
        else -> list(fetch("top/anime?filter=bypopularity&page=$page&sfw=true"))
    }

    suspend fun search(query: String, page: Int): List<CatalogItem> =
        list(fetch("anime?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page&sfw=true&limit=20"))

    suspend fun details(id: String): JSONObject? = fetch("anime/$id/full")?.optJSONObject("data")

    fun item(data: JSONObject): CatalogItem? = toItem(data)

    fun allTitles(data: JSONObject): List<String> {
        val base = listOf("title", "title_english", "title_japanese").mapNotNull {
            data.optString(it).takeIf { t -> t.isNotBlank() && t != "null" }
        }
        val synonyms = data.optJSONArray("title_synonyms")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        val fromTitles = data.optJSONArray("titles")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotBlank() } }
        }.orEmpty()
        return (base + fromTitles + synonyms).distinct()
    }
}

/**
 * Façade « animés » du catalogue unique : AniList en priorité, MyAnimeList
 * (Jikan) en repli automatique.
 */
object AnimeCatalog {

    private fun aniListSort(kind: String) = when (kind) {
        "trending" -> "TRENDING_DESC"
        "top" -> "SCORE_DESC"
        else -> "POPULARITY_DESC"
    }

    suspend fun row(kind: String, page: Int): List<CatalogItem> {
        val aniList = runCatching { AniListCatalog.row(aniListSort(kind), page) }.getOrDefault(emptyList())
        if (aniList.isNotEmpty()) return aniList
        return runCatching { JikanCatalog.row(kind, page) }.getOrDefault(emptyList())
    }

    suspend fun search(query: String, page: Int = 1): List<CatalogItem> {
        val aniList = runCatching { AniListCatalog.search(query, page) }.getOrDefault(emptyList())
        if (aniList.isNotEmpty()) return aniList
        return runCatching { JikanCatalog.search(query, page) }.getOrDefault(emptyList())
    }
}
