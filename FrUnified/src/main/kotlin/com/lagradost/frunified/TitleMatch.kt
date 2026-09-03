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
        "streaming", "complet", "complete", "gratuit", "en ligne", "hd", "sd", "integrale",
        "saison", "season", "episode", "serie", "series", "film", "movie", "anime",
        "the", "and", "of"
    )

    private val ROMAN = mapOf(
        "i" to "1", "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5",
        "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10"
    )

    fun normalize(input: String): String {
        var value = Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("&", " and ")
            .replace(Regex("[’'`]"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

        // Retire une éventuelle année
        value = value.replace(Regex("\\b(19|20)\\d{2}\\b"), " ")

        // Retire les marqueurs de saison "s3", "saison 3", "season 3", "part 2"…
        value = value.replace(Regex("\\b(?:s|sa|saison|season|part|partie|p)\\s*[0-9]{1,2}\\b"), " ")
        value = value.replace(Regex("\\b(?:s|sa|saison|season|part|partie)\\s*[ivx]+\\.?\\b"), " ")

        NOISE.forEach { noise ->
            value = value.replace(Regex("\\b${Regex.escape(noise)}\\b"), " ")
        }

        // Chiffres romains finaux (« Part III » déjà nettoyé, « One Piece II »)
        val tokens = value.split(" ").toMutableList()
        for (i in tokens.indices) {
            val roman = ROMAN[tokens[i].trim('.')]
            if (roman != null && i > 0 && tokens[i - 1].isNotBlank()) {
                tokens[i] = roman
            }
        }

        return tokens.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    private fun tokens(value: String): Set<String> =
        normalize(value).split(" ")
            .filter { it.length >= 3 || (it.length == 4 && it.all { c -> c.isDigit() }) }
            .toSet()

    /** Score 0.0 → 1.0 entre un titre du catalogue et un titre de source. */
    fun similarity(catalogTitle: String, sourceTitle: String): Double {
        val a = normalize(catalogTitle)
        val b = normalize(sourceTitle)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a.length < 4 || b.length < 4) return 0.0

        val ta = tokens(catalogTitle)
        val tb = tokens(sourceTitle)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0

        val intersection = ta.intersect(tb).size.toDouble()
        val jaccard = intersection / (ta.size + tb.size - intersection)
        val containment = intersection / minOf(ta.size, tb.size)
        val prefixBonus = if (b.startsWith(a) || a.startsWith(b)) 0.15 else 0.0

        return (0.55 * containment + 0.45 * jaccard + prefixBonus).coerceAtMost(1.0)
    }

    /** Détecte une saison annoncée dans un titre de source (« Saison 3 », « S2 », …). */
    fun detectSeason(name: String): Int? {
        val value = name.lowercase()
        val roman = Regex("\\b(?:saison|season|s|sa)?\\s*([ivx]+)\\b")
        val arabic = Regex("\\b(?:saison|season|s|sa)\\s*([0-9]{1,2})\\b")
        arabic.find(value)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 1..99) return it }
        roman.find(value)?.groupValues?.get(1)?.let { r ->
            ROMAN[r]?.toIntOrNull()?.let { if (it in 1..99) return it }
        }
        return null
    }

    /**
     * Score final d'un candidat, en prenant le meilleur des titres connus
     * (titre FR, titre original, titres alternatifs) et en pondérant l'année.
     * [wantedSeason] : si la source annonce une autre saison que celle cherchée,
     * le candidat est pénalisé (évite « One Piece Saison 12 » pour la saison 3).
     */
    fun score(
        catalogTitles: List<String>,
        candidateTitle: String,
        catalogYear: Int?,
        candidateYear: Int?,
        wantedSeason: Int? = null
    ): Double {
        val best = catalogTitles.maxOfOrNull { similarity(it, candidateTitle) } ?: 0.0
        if (best <= 0.0) return 0.0

        val yearFactor = when {
            catalogYear == null || candidateYear == null -> 1.0
            catalogYear == candidateYear -> 1.12
            abs(catalogYear - candidateYear) == 1 -> 1.0
            else -> 0.72
        }
        var result = (best * yearFactor).coerceAtMost(1.0)

        if (wantedSeason != null) {
            detectSeason(candidateTitle)?.let { found ->
                if (found != wantedSeason) result *= 0.35
            }
        }
        return result
    }

    /** Seuil en dessous duquel on refuse un appariement (évite les faux liens). */
    const val ACCEPT_THRESHOLD = 0.58
}
