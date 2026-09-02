package com.lagradost.frunified

import java.text.Normalizer
import kotlin.math.abs

/**
 * Comparaison de titres tolérante : c'est la pièce maîtresse qui permet de
 * relier une fiche du catalogue unique (TMDB/AniList) aux résultats bruts des
 * extensions françaises, dont les titres sont souvent bruités
 * ("Film (2024) VF HD", "Saison 3 VOSTFR", "titre.francais-multi", ...).
 */
object TitleMatch {

    private val NOISE = listOf(
        "vostfr", "vost", "vf", "vff", "vfq", "truefrench", "french", "multi",
        "hdlight", "hdrip", "webrip", "web-dl", "webdl", "bluray", "brrip", "dvdrip",
        "hdtv", "1080p", "720p", "480p", "2160p", "4k", "uhd", "x264", "x265", "hevc",
        "streaming", "complet", "gratuit", "en ligne", "hd", "sd", "integrale",
        "saison", "season", "episode", "serie", "series", "film", "movie", "anime"
    )

    private val ROMAN = mapOf(
        " i" to " 1", " ii" to " 2", " iii" to " 3", " iv" to " 4", " v" to " 5",
        " vi" to " 6", " vii" to " 7", " viii" to " 8", " ix" to " 9", " x" to " 10"
    )

    fun normalize(input: String): String {
        var value = Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("&", " and ")
            .replace(Regex("[’'`]"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        // Retire une éventuelle année entre parenthèses déjà aplatie
        value = value.replace(Regex("\\b(19|20)\\d{2}\\b"), " ")

        NOISE.forEach { noise ->
            value = value.replace(Regex("\\b${Regex.escape(noise)}\\b"), " ")
        }
        ROMAN.forEach { (roman, digit) -> if (value.endsWith(roman)) value = value.dropLast(roman.length) + digit }

        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun tokens(value: String): Set<String> =
        normalize(value).split(" ").filter { it.length > 1 || it.toIntOrNull() != null }.toSet()

    /** Score 0.0 → 1.0 entre un titre du catalogue et un titre de source. */
    fun similarity(catalogTitle: String, sourceTitle: String): Double {
        val a = normalize(catalogTitle)
        val b = normalize(sourceTitle)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0

        val ta = tokens(catalogTitle)
        val tb = tokens(sourceTitle)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0

        val intersection = ta.intersect(tb).size.toDouble()
        val jaccard = intersection / (ta.size + tb.size - intersection)
        val containment = intersection / minOf(ta.size, tb.size)
        val prefixBonus = if (b.startsWith(a) || a.startsWith(b)) 0.15 else 0.0

        return (0.55 * containment + 0.45 * jaccard + prefixBonus).coerceAtMost(1.0)
    }

    /**
     * Score final d'un candidat, en prenant le meilleur des titres connus
     * (titre FR, titre original, titres alternatifs) et en pondérant l'année.
     */
    fun score(
        catalogTitles: List<String>,
        candidateTitle: String,
        catalogYear: Int?,
        candidateYear: Int?
    ): Double {
        val best = catalogTitles.maxOfOrNull { similarity(it, candidateTitle) } ?: 0.0
        if (best <= 0.0) return 0.0

        val yearFactor = when {
            catalogYear == null || candidateYear == null -> 1.0
            catalogYear == candidateYear -> 1.12
            abs(catalogYear - candidateYear) == 1 -> 1.0
            else -> 0.72
        }
        return (best * yearFactor).coerceAtMost(1.0)
    }

    /** Seuil en dessous duquel on refuse un appariement (évite les faux liens). */
    const val ACCEPT_THRESHOLD = 0.62
}
