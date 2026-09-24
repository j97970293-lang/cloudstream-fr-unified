# v12 — Accueil enrichi + seuil d'appariement réglable

Publiée le 2026-09-24. Version 12.

Build produit **localement dans la sandbox** (JDK 17.0.20.1 + SDK Android 35,
installés pour l'occasion) : `./gradlew make makePluginsJson` →
**843 184 o**, sha256 `9dbbbd266e965f95edb141b63416ea41de6e67f4a4d745b21571fa966fe925f0`.
`prebuilt/FrUnified.cs3` et `prebuilt/plugins.json` mis à jour (hash + taille
vérifiés cohérents).

## Correctif de build

- `build.gradle.kts` (racine) : le plugin Gradle CloudStream épinglé au commit
  `com.github.recloudstream.gradle:gradle:32895ae` n'était **plus servi par
  JitPack** (404 — artefact disparu) → bascule sur `-SNAPSHOT`, cohérent avec
  `library:-SNAPSHOT` déjà utilisé et avec le réveil JitPack prévu par le CI.

## Nouveautés

### 1. Rangées d'accueil supplémentaires (`FrUnifiedProvider.mainPage`)

- 🇯🇵 **Séries d'animation japonaise** — TMDB `discover/tv`,
  `with_genres=16&with_original_language=ja`
- 🇯🇵 **Films d'animation japonaise** — TMDB `discover/movie`, mêmes filtres
- 📡 **Netflix, Prime, Disney+ FR (films + séries)** — TMDB
  `with_watch_providers=8|337|341&watch_region=FR`
- ⭐ **Top animés** — `anime|top` (AniList `SCORE_DESC`, repli Jikan
  `top/anime`), déjà supporté par `AnimeCatalog.row`

17 rangées au total (contre 12 en v11).

### 2. Seuil d'appariement réglable ⚙️

- `FrSettings.titleMatchThreshold` (SharedPreferences
  `title_match_threshold`, borné 0.30 – 0.90, défaut 0.58).
- `TitleMatch.ACCEPT_THRESHOLD` (constante) → remplacé par
  `TitleMatch.acceptThreshold` (propriété qui lit les réglages, repli
  `DEFAULT_ACCEPT_THRESHOLD = 0.58` si les préférences sont indisponibles).
- `SourceHub.locateFull` utilise désormais la propriété réglable.
- Nouveau champ dans l'écran ⚙️ (section **Catalogues actifs**) :
  « Seuil d'appariement des titres (0.30 = permissif → 0.90 = strict) »,
  clavier décimal, sauvegardé avec « Enregistrer ».

Effet pratique : en dessous de ~0.55, des sources comme Wiflix/Cofilx (titres
très bruités) remontent davantage de liens, au prix de possibles faux
appariements ; au-dessus de ~0.70, seules les correspondances quasi exactes
passent.

## Non modifié

- `TmdbCatalog.searchBest` garde son seuil interne 0.55 : c'est la recherche
  d'un ID TMDB à partir d'un titre pour les bundles Nuvio (repli), pas un
  appariement de liens.

## À valider sur téléphone

1. Mettre à jour FR Unifié (v12) après le push.
2. Accueil : vérifier les 5 nouvelles rangées (notamment « Top animés »).
3. Réglages ⚙️ → Catalogues actifs : régler le seuil (ex. 0.50 si des
   serveurs ne remontent aucun lien, 0.70 si de faux titres apparaissent).
