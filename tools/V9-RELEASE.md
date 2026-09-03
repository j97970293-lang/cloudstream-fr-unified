# v9 — Tests adaptés au contenu + diagnostic des sources

Publiée le 2026-09-03.

## Ce que la capture 18:02 montrait (et ce qu'elle ne disait pas)

**Plus aucune erreur de syntaxe** — le Rhino relogé (com.frunified.rhino) est
validé sur téléphone. Les « ✗ aucun résultat » Nuvio ont été reproduits en
local avec un fetch RÉEL (harnais RunReal) :

- **Le moteur fonctionne parfaitement** : Anime-Sama + One Piece (TMDB 37854,
  tv, S1E1) → **4 liens** (VF/720p, sibnet, headers ok) ; Frenchstream +
  Fight Club → **2 liens** (FSVID VF).
- **Le test était inadapté** : il envoyait Fight Club (film) à des sites
  d'ANIMÉS (Anime-Sama, Neko-Sama, VostFree…) → `catalogue/fight-club/...`
  → 404 → 0 lien. Même chose côté app.
- **VostFree renvoie 0** même avec le bon titre : le template du site a changé
  (`.search-result` n'existe plus, remplacé par `.shortstory`) — bug du
  BUNDLE/site, pas du moteur (à signaler au dépôt / attendre leur MAJ).

## Changements v9

- **NuvioClient.testProvider adaptatif** : scraper « anime-like » → test sur
  One Piece (TMDB 37854, S1E1) ; movie/tv → Fight Club (550) ; les deux →
  les deux, verdict combiné « ✓ film: n · anime: m ».
- **SourceHub.testSource enrichi** :
  - double payload film + anime selon le type de l'extension,
  - diagnostics précis : « recherche: 0 résultat » (site/extension cassée) vs
    « N résultats, score max 0,42 » (appariement trop strict).
- Version 9.

## Reste ouvert (vient des bundles, pas du moteur)

- Scrapers dont le site a changé de template/anti-bot (VostFree…) : 0 lien
  tant que le dépôt Nuvio ne les met pas à jour.
- Wiflix / Cofilx / French Anime : les nouveaux diagnostics indiqueront si la
  recherche renvoie des résultats (à renvoyer à l'utilisateur pour décision).
