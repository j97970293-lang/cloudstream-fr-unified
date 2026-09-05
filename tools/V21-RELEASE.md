# v21 — La VF fantôme, et le support d'AIO Metadata

## 1. « FrenchHub ment » — non, c'était moi

Votre test (Mushoku Tensei S3 ép. 10, VF affichée alors qu'elle n'existe pas)
a mis au jour un bug **de mon code**, pas de FrenchHub.

Les fiches d'animés déclaraient ceci :

```kotlin
DubStatus.Dubbed to episodes,
DubStatus.Subbed to episodes   // ← exactement la MÊME liste
```

Les deux onglets pointaient sur les mêmes épisodes, avec le même identifiant de
lecture. Choisir « Dub » ou « Sub » ne changeait donc **rien** : on relançait la
même recherche et on affichait les mêmes liens VOSTFR sous l'étiquette VF.
FrenchHub renvoyait ses liens honnêtement — c'est nous qui les rangions
dans la mauvaise case.

**Corrigé sur deux plans :**

1. Les onglets VF et VOSTFR portent maintenant une piste distincte (`dub` /
   `sub`) transmise jusqu'à la recherche de liens.
2. Un filtre lit l'étiquette de langue de chaque lien et **écarte** ceux qui
   contredisent l'onglet choisi.

Vérifié, avec le piège principal — « VOSTFR » contient « VF » :

| Nom du lien | Classé |
|---|---|
| `Vidmoly VOSTFR` | VOSTFR |
| `Sibnet VF` | VF |
| `Mystream vostfr HD` | VOSTFR |
| `Server 1 TRUEFRENCH` | VF |
| `Uqload` (non étiqueté) | conservé, marqué **« VF ? »** |

Choix assumé : un lien **non étiqueté** est conservé plutôt que jeté, mais
affiché avec un « ? ». Mieux vaut un lien de langue incertaine, signalé comme
tel, que zéro lien. Si l'onglet VF n'affiche plus que des « VF ? », c'est que la
source n'a pas de vraie VF.

## 2. AIO Metadata ne marchait pas — deux causes

Vous aviez raison. Deux défauts distincts :

**a) Les identifiants.** Je ne lisais que les identifiants IMDb (`tt…`).
AIO Metadata publie `tmdb:1399`, `aio_tmdb:550`, ou des identifiants d'animés
`kitsu:` / `mal:`. Toutes ces entrées étaient **silencieusement jetées** → rangée
vide. Les identifiants TMDB sont maintenant lus directement (nouvelle méthode
`TmdbCatalog.byTmdbId`), et les identifiants d'animés résolus par titre.

**b) Les genres obligatoires.** Je rejetais tout catalogue déclarant un extra
`isRequired`. Or AIO Metadata exige un `genre` **tout en fournissant la liste
des valeurs possibles**. Je prends désormais la première option proposée et
l'affiche dans le nom de la rangée. Seul un extra obligatoire *sans* options
(une recherche libre) reste écarté — il ne peut pas alimenter une rangée.

## Reste à faire (non livré ici)
- Addons FR d'animés/films supplémentaires
- Classement réordonnable des rangées et des sources

## Vérification
`FrUnified.cs3` — 145 372 o, plugins.json version 21, hash conforme.
