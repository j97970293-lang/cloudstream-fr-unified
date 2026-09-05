package com.lagradost.frunified

import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Filtre commun à TOUTES les sources de liens — extensions FR installées
 * comme addons Stremio.
 *
 * Les réglages vivent dans [FrSettings] : qualités exclues, mots-clés exclus,
 * taille mini/maxi, et exigence d'une langue explicite.
 */
object LinkFilter {

    /** Retrouve une taille en Mo dans un nom de lien (« 1.4 GB », « 700 MB »). */
    fun sizeMb(text: String): Int? {
        val m = Regex("(?i)([0-9]+(?:[.,][0-9]+)?)\\s*(gb|go|gib|mb|mo|mib)")
            .find(text) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        val mb = if (unit.startsWith("g")) value * 1024 else value
        return mb.toInt()
    }

    /**
     * @return null si le lien passe, sinon la raison du rejet (pour le diagnostic).
     */
    fun reject(link: ExtractorLink, sourceName: String): String? {
        val haystack = (link.name + " " + sourceName).lowercase()

        FrSettings.excludedQualities.firstOrNull { q ->
            q.isNotBlank() && haystack.contains(q)
        }?.let { return "qualité exclue ($it)" }

        FrSettings.excludedKeywords.firstOrNull { k ->
            k.isNotBlank() && haystack.contains(k)
        }?.let { return "mot-clé exclu ($it)" }

        // La taille n'est annoncée que par certains addons : si elle est absente,
        // on laisse passer plutôt que de tout jeter.
        val size = sizeMb(link.name)
        if (size != null) {
            val min = FrSettings.minSizeMb
            val max = FrSettings.maxSizeMb
            if (min > 0 && size < min) return "trop petit (${size} Mo < ${min} Mo)"
            if (max > 0 && size > max) return "trop gros (${size} Mo > ${max} Mo)"
        }
        return null
    }
}
