# v19 — Cohérence des fiches animés + catalogues Stremio

## 1. Le vrai problème : 3 fiches au lieu d'une

Votre constat était le bon diagnostic :

| Catalogue | Structure |
|---|---|
| **TMDB** | **1 fiche** « One Piece » → saisons 1, 2, 3 à l'intérieur |
| **AniList / MAL** | **1 fiche par saison** → 3 entrées pour la même série |

Ce n'est pas un bug d'AniList : ces bases découpent les animés par arc.
Mélanger les deux dans une même liste donnait des doublons incohérents.

**Rien n'a été supprimé.** AniList et MAL restent en place — ils référencent des
animés que TMDB ignore. C'est la *priorité* qui change.

### Recherche
TMDB fait désormais autorité. `TitleMatch.normalize` retirant déjà les
marqueurs de saison (« Saison 2 », « Season II », « Part 3 »), deux fiches
AniList d'une même série se réduisent au même titre : **une seule survit**, et
si TMDB connaît déjà la série, **aucune** fiche AniList n'est ajoutée.

## 2. Sections animés sur TMDB (demande n°3)
Quatre nouvelles rangées, genre 16 + origine Japon, donc **fiche unique avec
saisons** :

- 🌸 Animés populaires
- 🆕 Animés récents
- ⭐ Animés les mieux notés
- 🎞️ Films d'animation japonais

L'ancienne rangée AniList est conservée sous le nom « Animés de la saison
(AniList) », clairement identifiée comme complément.

## 3. Catalogues Stremio, positionnables
`StremioClient` ne savait lire que les flux et les sous-titres. Il lit
maintenant aussi les **catalogues** publiés par les addons.

- Section ⚙️ « Catalogues actifs » → **Catalogues des addons Stremio**
- **Placer les catalogues Stremio en premier** : au-dessus ou en dessous de TMDB
- Bouton **🔎 Détecter les catalogues des addons**

Les rangées détectées sont mémorisées dans les réglages : l'accueil se construit
**sans appel réseau**.

Point important : les entrées Stremio portent un identifiant IMDb, retraduit en
fiche TMDB. Les fiches issues de Stremio se comportent donc exactement comme les
autres — **une seule fiche, saisons à l'intérieur**.

⚠️ **Torrentio et Comet ne publient aucun catalogue** (uniquement des flux). La
détection le dira explicitement. Il faut un addon de type catalogue.

## Vérification
`FrUnified.cs3` — 136 969 o, plugins.json version 19, hash conforme.
