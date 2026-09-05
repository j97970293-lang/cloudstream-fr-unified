# v25 — La vraie cause des catalogues vides

## Merci pour la piste StremioX / StremioC

J'ai lu `StremioC.kt` (hexated). La comparaison est sans appel — il fait ceci :

```kotlin
// StremioC : l'entrée de l'addon devient DIRECTEMENT une fiche
res?.metas?.forEach { entry ->
    entries.add(entry.toSearchResponse(provider))
}
```

Et moi je faisais ceci :

```kotlin
// FR Hub v24 : chaque entrée DEVAIT être retrouvée dans TMDB
when {
    tmdbId != null -> TmdbCatalog.byTmdbId(...)
    imdb   != null -> TmdbCatalog.byImdb(...)
    else           -> TmdbCatalog.searchBest(...)
}
}.awaitAll().filterNotNull()   // ← tout ce que TMDB ignore est JETÉ
```

**C'était ça, le bug.** Chaque entrée d'AIO Metadata devait exister dans TMDB
pour survivre. Dès que la correspondance échouait — identifiant inconnu de
TMDB, clé TMDB absente ou invalide, quota dépassé — `filterNotNull()` la
supprimait. Si toutes échouaient, la rangée était **vide**, et vous n'aviez
aucun moyen de le deviner.

Mes v22/v23/v24 traitaient la détection du manifeste. Le manifeste était
peut-être lu correctement depuis le début : c'est **l'affichage** qui jetait
tout ensuite.

## Ce qui change

1. **Plus aucune entrée n'est jetée.** Si TMDB ne connaît pas un titre, on
   garde la fiche telle que l'addon la fournit (nom, affiche, année) — sous un
   nouveau catalogue `stremio`.
2. **Ces fiches sont lisibles.** Nouvelle méthode `StremioClient.meta()` qui
   interroge `/meta/<type>/<id>.json`, exactement comme StremioC, pour obtenir
   le détail et la liste des épisodes.
3. TMDB reste **prioritaire** quand il connaît le titre : on conserve la fiche
   unique avec saisons groupées. Il n'est simplement plus obligatoire.

## Vérification
`FrUnified.cs3` — 162 645 o, plugins.json version 25, hash conforme.
Rappel : redémarrer CloudStream après avoir coché les rangées (`mainPage`
n'est lu qu'au chargement du plugin).
