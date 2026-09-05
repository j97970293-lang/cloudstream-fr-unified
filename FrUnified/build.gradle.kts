version = 20

cloudstream {
    language = "fr"
    authors = listOf("FR-Unified")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1

    description = "Catalogue unique FR (TMDB + AniList) : une seule extension qui agrège la recherche " +
        "et les liens de toutes les extensions françaises installées (French-Stream, Movix, Wiflix, " +
        "FrenchAnime, Frembed, FSTV, Karma…). Addons Stremio (Torrentio, Comet, debrid), " +
        "sous-titres et bandes-annonces."

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "Cartoon")

    iconUrl = "https://raw.githubusercontent.com/j97970293-lang/cloudstream-fr-unified/master/icon.png"

    requiresResources = false
}

android {
    buildFeatures {
        buildConfig = true
    }
}
