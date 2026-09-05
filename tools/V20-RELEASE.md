# v20 — Fin des fiches « Season 3 », et séparation catalogue / flux

## 1. Votre capture : « Mushoku Tensei … Season 3 »

Vous aviez raison, c'était toujours là. **Ma correction v19 était incomplète** :
je n'avais dédupliqué que la **recherche**, pas l'**accueil**. La rangée
« Animés de la saison (AniList) » servait donc toujours des fiches par saison.

Corrigé : cette rangée retraduit désormais chaque entrée AniList vers sa fiche
**TMDB** d'ensemble, via un nouveau `TitleMatch.stripSeason()`.

Vérifié sur les cas réels et les pièges :

| Titre AniList | Résultat |
|---|---|
| `Mushoku Tensei: Jobless Reincarnation Season 3` | `Mushoku Tensei: Jobless Reincarnation` |
| `Attack on Titan Season 2 Part 1` | `Attack on Titan` |
| `Kaguya-sama: Love is War 2nd Season` | `Kaguya-sama: Love is War` |
| `Vinland Saga Saison 2` | `Vinland Saga` |
| `Season of the Witch` | **inchangé** (ce n'est pas une saison) |
| `86 Eighty-Six` | **inchangé** |

Trois saisons convergeant vers la même fiche TMDB, une seule est conservée.
Si TMDB ne connaît pas la série, la fiche AniList est gardée telle quelle
plutôt que de disparaître.

## 2. Addon catalogue ≠ addon flux

Votre remarque était juste : ce sont deux choses différentes, l'interface les
confondait.

- **Addon de FLUX** (Torrentio, Comet, MediaFusion) : fournit de quoi *lire*.
- **Addon de CATALOGUE** : fournit des *listes à parcourir*.

Un même addon peut faire les deux, ou un seul. La section « Addons Stremio »
l'explique maintenant, et chaque addon a **sa case** pour être utilisé — ou non
— comme source de flux. Décocher ne l'efface pas : un addon purement catalogue
peut rester décoché côté flux.

## 3. Activer / désactiver chaque rangée

Nouvelle section ⚙️ **« 🗂️ Rangées de l'accueil »**, en deux groupes :

- **Catalogue d'origine (TMDB / AniList)** — les 15 rangées, décochables une par une
- **Catalogues Stremio détectés**

C'est bien ce que vous demandiez : le catalogue d'origine se désactive au même
titre que les rangées Stremio. Le résumé affiche « X / Y rangées affichées ».

Garde-fou : si vous décochez absolument tout, l'accueil réaffiche les rangées
plutôt que de rester vide.

## Vérification
`FrUnified.cs3` — 141 953 o, plugins.json version 20, hash conforme.
