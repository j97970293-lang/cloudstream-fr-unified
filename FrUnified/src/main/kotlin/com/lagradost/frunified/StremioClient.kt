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
