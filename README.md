# FR Unifié — un seul catalogue, toutes les extensions françaises

Dépôt CloudStream contenant **une seule extension** (`FrUnified`) basée sur **un seul catalogue**
(TMDB pour les films/séries, AniList + MyAnimeList pour les animés).

Ce n'est **pas** une fusion de catalogues affichés côte à côte : il n'y a **qu'un seul provider**
visible dans l'application, une seule recherche, une seule fiche par film/série/animé.
Les extensions françaises que vous avez installées (French‑Stream, Movix, Wiflix, FrenchAnime,
Frembed, FSTV, JourFilm, DoTriv, FsMirrorLol, Karma…) ne servent plus qu'à **fournir les liens
de lecture** en arrière‑plan.

```
                ┌──────────────────────────────────────────┐
Recherche  ───► │  FR Unifié  (catalogue TMDB / AniList)    │
                └───────────────┬──────────────────────────┘
                                │ lecture d'une fiche
                                ▼
        ┌───────────────────────────────────────────────────────────┐
        │  SourceHub : interroge EN PARALLÈLE toutes les extensions │
        │  FR installées, apparie le titre, récupère leurs liens    │
        └───────┬───────────┬────────────┬────────────┬─────────────┘
         French‑Stream    Movix      Wiflix     FrenchAnime   …
```

## Ce que ça change concrètement

| Avant | Avec FR Unifié |
|---|---|
| 8 providers, 8 fiches pour le même film | 1 fiche unique |
| Résultats de recherche dupliqués et mal nommés | Recherche TMDB propre, en français, avec affiches HD |
| Titres bruités (`Film 2024 VF HDLight`) | Titres officiels + année + synopsis + casting + bandes‑annonces |
| Aucun suivi | Suivi AniList / MAL / Simkl fonctionnel (les IDs sont renseignés) |
| Un site tombe = plus de lien | Toutes les sources sont tentées en parallèle, la première qui répond gagne |

## Contenu du dépôt

```
FrUnified/src/main/kotlin/com/lagradost/frunified/
├── FrUnifiedPlugin.kt      → point d'entrée du plugin (1 seul MainAPI enregistré)
├── FrUnifiedProvider.kt    → le catalogue unique : accueil, recherche, fiches, épisodes
├── TmdbCatalog.kt          → catalogue films / séries (TMDB, en fr-FR)
├── AniListCatalog.kt       → catalogue animés (AniList, GraphQL)
├── JikanCatalog.kt         → repli MyAnimeList (Jikan) si AniList est indisponible
├── SourceHub.kt            → moteur d'agrégation des extensions FR installées
├── StremioClient.kt        → addons Stremio (liens + sous-titres)
├── FrSettings.kt           → réglages persistés (sources actives, addons, langues)
├── SettingsDialog.kt       → écran ⚙️ du plugin
├── TitleMatch.kt           → appariement tolérant des titres (VF/VOSTFR/HDLight/…)
└── CatalogModels.kt        → identifiants du catalogue + charge utile de lecture
```

## Installation (utilisateur)

1. Forkez / poussez ce dépôt sur GitHub, puis remplacez `USER` par votre pseudo :
   ```bash
   grep -rl "j97970293-lang/cloudstream-fr-unified" . | xargs sed -i "s|j97970293-lang/cloudstream-fr-unified|VOTREPSEUDO/cloudstream-fr-unified|g"
   ```
2. Poussez sur `master` : le workflow GitHub Actions compile et publie `FrUnified.cs3`
   et `plugins.json` sur la branche `builds`.
3. Dans CloudStream : **Paramètres → Extensions → Ajouter un dépôt** puis collez
   ```
   https://raw.githubusercontent.com/VOTREPSEUDO/cloudstream-fr-unified/master/repo.json
   ```
4. Installez **FR Unifié**.
5. **Important** : gardez vos extensions FR habituelles installées (elles alimentent les liens),
   mais vous pouvez les **désactiver de la recherche** dans les paramètres de CloudStream
   pour ne garder qu'un seul résultat par titre.

Un `.cs3` déjà compilé est disponible dans `prebuilt/FrUnified.cs3` si vous voulez tester
immédiatement (Extensions → installer depuis un fichier).

## Compilation locale

```bash
export ANDROID_SDK_ROOT=/chemin/vers/android-sdk   # platforms;android-35 requis
./gradlew make makePluginsJson
# → FrUnified/build/FrUnified.cs3   et   build/plugins.json
```
JDK 17 requis (AGP 8.7).

## Comment fonctionne l'agrégation

1. La fiche ouverte fournit une *charge utile* : titres connus (titre FR, titre original,
   titres alternatifs TMDB, synonymes AniList/MAL), année, saison, épisode, IDs TMDB/IMDb/MAL.
2. `SourceHub` liste les providers dont `lang == "fr"` **déjà installés dans l'application**
   (via `APIHolder`), en s'excluant lui‑même.
3. Chaque source est interrogée en parallèle : `quickSearch`/`search` → meilleur candidat via
   `TitleMatch` (score ≥ 0,62, bonus si l'année correspond) → `load()` → sélection de l'épisode
   (saison+épisode exacts, puis numérotation absolue, puis position) → `loadLinks()`.
4. Les liens remontés sont préfixés du nom de la source (`Movix • Uqload 1080p`), les
   sous‑titres sont relayés tels quels.
5. Délais de garde : 20 s pour la recherche, 25 s pour la fiche, 45 s pour les liens, par source.
   Un site mort ne bloque jamais la lecture.

Aucun scraping n'est dupliqué ici : quand French‑Stream change de miroir ou que Movix corrige
son extracteur, **FR Unifié en profite automatiquement** puisqu'il réutilise ces extensions.

## Réglages intégrés (⚙️ dans la liste des extensions)

* **Activer / désactiver chaque serveur** : cases à cocher, une par extension FR détectée.
* **Addons Stremio** : colle une URL par ligne (`https://torrentio.strem.fun/manifest.json`,
  Comet, MediaFusion, un debrid perso…). Leurs flux HTTP et torrents (magnet) sont ajoutés
  aux liens, à côté de ceux des extensions FR.
* **Sous-titres externes** : activés par défaut via l'addon public OpenSubtitles v3
  (aucune clé requise), filtrés par langue (`fre, fra, fr, eng` par défaut).
* **Bandes-annonces** : jusqu'à 3 trailers YouTube par fiche, VF prioritaire (TMDB),
  trailer MAL pour les animés.

### Extensions basées sur TMDB/IMDb

Les sources qui exposent `supportedSyncNames` (Frembed, Movix, et tout provider TMDB/IMDb)
sont interrogées **directement par identifiant** (`getLoadUrl(SyncIdName.Imdb, "tt…")`),
sans recherche textuelle : l'appariement est exact et instantané. Les autres passent par
la recherche titre + année.

## Personnalisation

* **Ajouter des lignes d'accueil** : `mainPage` dans `FrUnifiedProvider.kt`
  (`tmdb|<endpoint TMDB>` ou `anime|trending|popular|top`).
* **Exclure une source** : constante `BLACKLIST` dans `SourceHub.kt`.
* **Élargir aux sources non francophones** : constante `FRENCH_LANGS` dans `SourceHub.kt`.
* **Rendre l'appariement plus/moins strict** : `TitleMatch.ACCEPT_THRESHOLD`.
* **Clé TMDB** : `TmdbCatalog.API_KEY` (clé publique communautaire par défaut).

## Crédits

Inspiré et complémentaire des dépôts FR existants :
[mouradchaouche/cloudstream-frenchrepo](https://github.com/mouradchaouche/cloudstream-frenchrepo),
[Nikola17/cloudstream-frenchstream](https://github.com/Nikola17/cloudstream-frenchstream),
[blizzx4644/Movix-cloudstream](https://github.com/blizzx4644/Movix-cloudstream),
[Kraptor123/Cs-Karma](https://github.com/Kraptor123/Cs-Karma).

Ce dépôt n'héberge aucun contenu : il n'agrège que des métadonnées publiques (TMDB, AniList, MAL)
et les liens produits par les extensions déjà installées par l'utilisateur.
