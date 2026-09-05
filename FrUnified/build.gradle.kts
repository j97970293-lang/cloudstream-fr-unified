version = 26

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

    description = "FR Hub — catalogue unique TMDB + AniList qui AGRÈGE vos extensions " +
        "françaises installées et vos addons Stremio (flux et catalogues). " +
        "N'héberge aucune source : filtres de qualité/taille, VF-VOSTFR séparées, " +
        "rangées d'accueil réordonnables."

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "Cartoon")

    iconUrl = "https://raw.githubusercontent.com/j97970293-lang/cloudstream-fr-unified/master/icon.png"

    requiresResources = false
}

android {
    buildFeatures {
        buildConfig = true
    }
}
