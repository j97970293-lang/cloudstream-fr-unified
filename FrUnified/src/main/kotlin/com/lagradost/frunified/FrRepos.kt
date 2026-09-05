package com.lagradost.frunified

/**
 * Dépôts CloudStream français et addons Stremio francophones connus.
 *
 * FR Hub n'héberge AUCUN scraper : il agrège les extensions installées.
 * Cette liste sert de raccourci — chaque entrée a été vérifiée comme active
 * (dépôt existant, `repo.json` valide) au moment de la publication.
 */
object FrRepos {

    data class Repo(
        val name: String,
        val url: String,
        val detail: String
    )

    /** Dépôts d'extensions CloudStream à coller dans CloudStream. */
    val CLOUDSTREAM: List<Repo> = listOf(
        Repo(
            "AMSC French",
            "https://raw.githubusercontent.com/mouradchaouche/cloudstream-frenchrepo/main/repo.json",
            "Wiflix-Flemmix, French Anime, FsMirrorLol-Frenchstream, Coflix, Frembed FR"
        ),
        Repo(
            "CloudStream FR",
            "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/repo.json",
            "French-Stream, Movix, FrenchManga, FSTV"
        ),
        Repo(
            "Movix",
            "https://raw.githubusercontent.com/blizzx4644/Movix-cloudstream/main/repo.json",
            "Movix seul : films, séries et animés FR, 45+ extracteurs"
        ),
        Repo(
            "Cs-Karma",
            "https://raw.githubusercontent.com/Kraptor123/Cs-Karma/master/repo.json",
            "Dépôt multi-langues, contient Movix et Yablom"
        )
    )

    /** Addons Stremio francophones (flux et/ou catalogue). */
    val STREMIO: List<Repo> = listOf(
        Repo(
            "TMDB (catalogue FR)",
            "https://tmdb.elfhosted.com",
            "CATALOGUE — affiches, titres et synopsis en français"
        ),
        Repo(
            "StremioFR — Comet",
            "https://comet.stremiofr.com",
            "FLUX — indexeur français, compatible debrid"
        ),
        Repo(
            "StremioFR — Jackettio",
            "https://jackettio.stremiofr.com",
            "FLUX — indexeur français, compatible debrid"
        ),
        Repo(
            "OpenSubtitles v3",
            "https://opensubtitles-v3.strem.io",
            "SOUS-TITRES — gratuit, sans clé"
        )
    )
}
