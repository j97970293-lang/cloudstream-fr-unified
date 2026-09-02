package com.lagradost.frunified

import com.lagradost.cloudstream3.app
import org.json.JSONObject

/**
 * Volet "animé" du catalogue unique : AniList (compatible MAL via `idMal`).
 * Les fiches restent affichées dans le MÊME catalogue et le même flux de
 * recherche que TMDB — c'est simplement la base de métadonnées la mieux
 * adaptée aux animés (titres romaji / anglais / synonymes indispensables
 * pour retrouver le contenu sur les sources françaises).
 */
object AniListCatalog {
    private const val ENDPOINT = "https://graphql.anilist.co"

    private const val MEDIA_FIELDS = """
        id
        idMal
        title { romaji english native userPreferred }
        synonyms
        description(asHtml: false)
        startDate { year }
        seasonYear
        format
        episodes
        averageScore
        genres
        coverImage { extraLarge large }
        bannerImage
    """

    private suspend fun query(query: String, variables: Map<String, Any?>): JSONObject? {
        val body = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject(variables))
        }.toString()

        return runCatching {
            JSONObject(
                app.post(
                    ENDPOINT,
                    json = body,
                    headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                    timeout = 20
                ).text
            )
        }.getOrNull()
    }

    private fun toItem(media: JSONObject): CatalogItem? {
        val id = media.optInt("id").takeIf { it > 0 } ?: return null
        val titles = media.optJSONObject("title")
        val title = listOf("userPreferred", "romaji", "english", "native")
            .firstNotNullOfOrNull { titles?.optString(it)?.takeIf { t -> t.isNotBlank() && t != "null" } }
            ?: return null
        val english = titles?.optString("english")?.takeIf { it.isNotBlank() && it != "null" && !it.equals(title, true) }

        return CatalogItem(
            id = CatalogId("anilist", "anime", id.toString()),
            title = title,
            originalTitle = english,
            year = media.optInt("seasonYear").takeIf { it > 0 }
                ?: media.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 },
            posterUrl = media.optJSONObject("coverImage")?.let {
                it.optString("extraLarge").ifBlank { it.optString("large") }
            }?.takeIf { it.isNotBlank() && it != "null" },
            backdropUrl = media.optString("bannerImage").takeIf { it.isNotBlank() && it != "null" },
            overview = media.optString("description").takeIf { it.isNotBlank() && it != "null" }
                ?.replace(Regex("<[^>]+>"), ""),
            rating10 = media.optInt("averageScore").takeIf { it > 0 }?.div(10.0)
        )
    }

    suspend fun search(search: String, page: Int = 1, perPage: Int = 25): List<CatalogItem> {
        val gql = """
            query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) { $MEDIA_FIELDS }
              }
            }
        """.trimIndent()
        return mediaList(query(gql, mapOf("search" to search, "page" to page, "perPage" to perPage)))
    }

    suspend fun row(sort: String, page: Int = 1, perPage: Int = 25, extraFilter: String = ""): List<CatalogItem> {
        val gql = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, sort: $sort $extraFilter) { $MEDIA_FIELDS }
              }
            }
        """.trimIndent()
        return mediaList(query(gql, mapOf("page" to page, "perPage" to perPage)))
    }

    suspend fun details(id: String): JSONObject? {
        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                $MEDIA_FIELDS
                status
                duration
                studios(isMain: true) { nodes { name } }
                characters(sort: ROLE, perPage: 12) {
                  edges { role voiceActors(language: FRENCH) { name { full } image { large } } node { name { full } image { large } } }
                }
                relations { edges { relationType node { id type title { userPreferred } coverImage { large } format } } }
              }
            }
        """.trimIndent()
        return query(gql, mapOf("id" to id.toIntOrNull()))
            ?.optJSONObject("data")?.optJSONObject("Media")
    }

    fun item(media: JSONObject): CatalogItem? = toItem(media)

    fun allTitles(media: JSONObject): List<String> {
        val titles = media.optJSONObject("title")
        val base = listOf("romaji", "english", "userPreferred", "native").mapNotNull {
            titles?.optString(it)?.takeIf { t -> t.isNotBlank() && t != "null" }
        }
        val synonyms = media.optJSONArray("synonyms")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        return (base + synonyms).distinct()
    }

    private fun mediaList(root: JSONObject?): List<CatalogItem> {
        val media = root?.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
        return (0 until media.length()).mapNotNull { i -> media.optJSONObject(i)?.let { toItem(it) } }
    }
}
