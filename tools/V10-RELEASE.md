# v10 — Télémétrie + tests sérialisés (le « aucun résultat » va devenir explicite)

Publiée le 2026-09-03 ~21:45 (heure Antananarivo) — **version 10 FINALE,
830 866 o, sha256 `5b1d7f09bc6b0dc859430a098017ac4e42f102b3acdf602e34c812f854e6c513`**.
Vérifiée en ligne : raw md5 == prebuilt, manifest version 10, plugins.json v10.

## Historique de build (v10)

- v10a : échec Kotlin — `captureConsole(cx, scope, id)` (signature = `(scope, id)`)
  + `scope` déclaré dans le `try` mais utilisé dans le `finally`.
- v10b : échec Kotlin — `Unresolved reference 'scope'` → hoiste en `scopeRef`.
- v10c : **BUILD SUCCESSFUL** 49 s — 830 923 o, sha `14e35782…` ;
  **PUBLIÉE PUIS REMPLACÉE** (remarque : sémaphore de test partagé avec la lecture).
- v10d (FINALE) : **BUILD SUCCESSFUL** 40 s — 830 866 o, sha `5b1d7f09…` —
  sémaphore de test **dédié** (`testSemaphore = Semaphore(2)`) : les tests ne
  bloquent plus la lecture réelle (qui garde `NUVIO_CONCURRENCY = 6`).

Problème CDN rencontré : après le second push, `raw.githubusercontent.com`
servait l'ancien fichier ~2 min (cache Fastly). Vérification : API GitHub
(blob sha) puis raw sans query — cohérent au bout de ~3 min. Si l'utilisateur
télécharge la v10 de 830 923 o (intermédiaire, jamais testée), lui dire de
re-synchroniser le dépôt : le plugins.json en ligne pointe bien sur la finale.

## Diagnostic (capture 21:08 / 21:09)

Les bundles sont validés localement (Anime-Sama + One Piece → 4 liens,
Frenchstream + Fight Club → 2 liens, avec véritable réseau et JDK 17).
Pourtant sur le téléphone : « ✗ aucun résultat » pour TOUS les serveurs Nuvio.
Causes probables :

1. **Les tests partaient en parallèle** : l'écran « Tester » lançait un test
   par ligne simultanément — 26 moteurs Rhino + 26 flux de requêtes simultanés
   sur un réseau mobile lent → saturation → timeouts en masse.
2. Réseau lent : les timeouts courts transformaient la lenteur en « 0 résultat »
   pour Wiflix / French Anime / Cofilx.

## Changements v10

- **Tests sérialisés** : sémaphore dédié aux tests (2 en parallèle max), qui ne
  bloque jamais la lecture réelle ; timeout de test 150 s (extensions : 60 s).
- **Télémétrie dans les verdicts** :
  - chaque requête HTTP est journalisée (`GET anime-sama.to → 404 (4820ms)`) ;
  - la console JS des bundles est capturée (2 dernières lignes) ;
  - tout échec affiche : raison + dernières requêtes + dernière ligne console
    → on saura si c'est un 404, un timeout, un blocage réseau ou un bug du bundle.
- **Timeouts** : recherche 35 s, chargement 50 s, agrégation 100 s (SourceHub) ;
  scrapeur Nuvio 90 s (au lieu de 60 s).
- « Recherche cassée » : si la recherche d'une extension lève une exception,
  le verdict l'affiche au lieu d'un « 0 résultat » muet.
- Version 10.

## Sweep des 46 bundles (harnais réseau réel, JDK17, 2 passes, 2026-09-03)

`/home/user/work/sweep-v10.txt` — contenu : anime → One Piece (37854 tv 1 1),
film → Fight Club (550 movie). Résultat consolidé :

**Verts (≥1 lien) :** anime-sama (gowaru) 4 · anime-sama (z7kx) 4 ·
animesama-co 1 · animevostfr (gowaru) 2-3 · animevost-fr 1 · french-manga 1-2 ·
frenchstream (gowaru) 2 · movix 1 · fullanime 1 · voiranime (z7kx) 2 ·
voiranime-rip 1 · streamzo 1 (1 passe sur 2).

**0 lien (périmés/morts, cause identifiée) :** coflix → domaines du bundle
morts (coflix.to, coflix.fr) ; le site vit sous **cofilx.com** avec URLs
`/movie/<tmdbId>` (vérifié : /movie/550 = fiche + lecteur) → bundle Gowaru à
mettre à jour ; flemmix (wiflix renommé, domaines morts) ; vostfree (template) ;
les autres (anime-ultime, animesultra, animoflix, dulourd, voiranime-homes,
mugiwarastream, sekai, nakios, papadustream, wookafr, waveanime, neko-sama) à
re-vérifier au cas par cas.

**Instabilité** : entre les 2 passes, frenchstream 2→0, voiranime 0→2,
streamzo 1→0, french-manga 2→1 → rate-limit/anti-bot probable sur les sites FR ;
même les bons providers peuvent donner 0 selon l'heure — attendu aussi sur
téléphone.

## Sites vérifiés depuis le sandbox (2026-09-03)

- `cofilx.com` : **répond** ; recherche OK via **`/search?q=`** (48 occurrences
  « Fight Club ») ; `?s=` et `?search=` → 0 résultat. Si l'extension Cofilx
  installée utilise l'ancien chemin `?s=`, elle est périmée → la mettre à jour
  depuis son dépôt (le verdict v10 montrera l'URL exacte tentée).
- **Wiflix a changé de nom → FLÉMMIX** (2026) ; tous les domaines wiflix.*
  testés sont morts (000) ; les « adresses officielles » trouvées sur le web
  (flemmix.art → redirection publicitaire, flemmix.vip/best/prof → 000) ne sont
  **pas fiables** → ne pas hardcoder ; l'extension Wiflix est à mettre à jour
  ou à remplacer.
- `french-anime.net` / `french-anime.gg` : morts (000).
- VostFree : template changé (`.shortstory` au lieu de `.search-result`) —
  bundle périmé (signalé au dépôt, pas corrigeable ici).
- Anime-Sama : OK (4 liens VF 720p sibnet pour One Piece S1E1).

## À faire sur téléphone

1. Re-synchroniser le dépôt FR Unifié puis réinstaller l'extension (v10).
2. Fournisseurs Nuvio → Tester **2-3 providers** (pas les 46 !) : le verdict
   indique « ✓ anime: 4 lien(s) » ou « ✗ … | GET anime-sama.to → 404 (5000ms)
   | js: … » → renvoyer tel quel.
3. Sources CloudStream → Tester Wiflix, Cofilx, French Anime : les messages
   distinguent désormais site cassé / recherche lente / appariement insuffisant.
4. Ouvrir la fiche One Piece → attendre ; si aucun serveur, revenir aux
   réglages Fournisseurs Nuvio : les verdicts reflètent la dernière lecture.
