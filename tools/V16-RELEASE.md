# v16 — Nuvio supprimé

Publiée le 2026-09-04. Version 16.

## Décision

Nuvio est retiré de l'extension. Motifs de l'utilisateur : ça ne fonctionne
pas, et **l'application rame** à cause de lui.

Le second point est fondé et n'était pas près de disparaître : chaque lecture
lançait jusqu'à 26 interpréteurs Rhino, chacun sur un thread réclamant une pile
de 32 Mo (256 Mo en repli), sur un téléphone. Même une fois les liens obtenus,
le coût mémoire et CPU restait dissuasif.

## Ce qui a été retiré

| Élément | Détail |
|---|---|
| `NuvioClient.kt` | 1 486 lignes : moteur JS, boucle d'événements, fetch, conversion des flux |
| `RhinoMessages.kt` | 406 lignes : les 334 messages Rhino embarqués pour le dex |
| `rhino-nuvio-1.9.1.jar` | 1,66 Mo — dépendance de compilation |
| `rhino-embed-1.9.1.jar` | 1,51 Mo — classes extraites puis déxées |
| Tâche Gradle `extractRhino` | + le branchement sur `CompileDexTask` |
| Réglages | 13 clés (dépôts, ordre, concurrence, max/scrapeur, priorités, UA, Referer, Cookies…) |
| Écran ⚙️ | sections « Cloudflare » et « Fournisseurs » (liste des 26 serveurs + boutons Tester) |

## Résultat mesuré

| | v15 | v16 |
|---|---|---|
| Taille du `.cs3` | 846 618 o | **117 553 o** |

**7,2× plus léger**, et plus aucune trace de `frunified/rhino`, `NuvioClient`
ni `nuvio-js-` dans le `classes.dex` (vérifié sur l'artefact publié).

Plus aucun interpréteur JavaScript n'est démarré : le lag lié aux threads à
grande pile disparaît, et l'ouverture d'une fiche n'attend plus 26 scrapeurs.

## Ce qui fournit les liens désormais

1. **Extensions FR installées** (`SourceHub`) — French-Stream, Movix, Wiflix,
   FrenchAnime, Frembed, FSTV, Karma… avec appariement TMDB/IMDb ou textuel.
2. **Addons Stremio** (`StremioClient`) — Torrentio, Comet, debrid perso.
3. **Sous-titres** OpenSubtitles.

Le catalogue unique (TMDB + AniList/Jikan), la recherche, les fiches, les
épisodes et le suivi AniList/MAL sont **inchangés**.

## Note pour l'avenir

Les bugs du moteur étaient réels et ont tous été corrigés (v11 → v15) ; le
dépôt Gowaru, lui, est toujours activement maintenu. Si le besoin revient,
l'historique Git contient un moteur Rhino fonctionnel sur Android — il suffit
de restaurer les fichiers de ce commit. Mais le coût en ressources sur
téléphone reste le vrai obstacle, indépendamment des bugs.

## À faire sur téléphone

1. Rafraîchir le dépôt et mettre à jour en **v16** (le téléchargement sera
   nettement plus rapide : 117 Ko).
2. ⚙️ ne contient plus ni « Scrapeurs Nuvio », ni « Cloudflare », ni la liste
   des serveurs — c'est normal.
3. Vérifier dans **Sources CloudStream** que vos extensions FR sont bien
   cochées : ce sont elles qui fournissent les liens maintenant.
