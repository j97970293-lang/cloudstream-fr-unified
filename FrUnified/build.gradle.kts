version = 3

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
        "FrenchAnime, Frembed, FSTV, Karma…). Supports : réglages ⚙️ (activation par serveur), addons " +
        "Stremio (Torrentio, Comet, debrid), scrapeurs Nuvio (Gowaru, Phisher…), sous-titres et bandes-annonces."

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "Cartoon")

    iconUrl = "https://www.google.com/s2/favicons?domain=themoviedb.org&sz=256"

    requiresResources = false
}

android {
    buildFeatures {
        buildConfig = true
    }
}
