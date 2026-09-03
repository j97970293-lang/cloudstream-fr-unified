# v10 — Télémétrie + tests sérialisés (le « aucun résultat » va devenir explicite)

Publiée le 2026-09-03 ~21:30 (heure Antananarivo) — **version 10, 830 923 o,
sha256 `14e35782d67aebf0b237164b6deecdd2887c25b9d82dd865236a3b423fd9c408`**.

Builds : v10a échec (Kotlin : `captureConsole(cx, scope, id)` → signature
`(scope, id)` ; `scope` non visible dans le `finally`) ; v10b échec
(`Unresolved reference 'scope'` → `scopeRef` hoisté) ; v10c **BUILD
SUCCESSFUL 49s** — publiée, vérifiée raw (md5 identique).

## Diagnostic (capture 21:08 / 21:09)

Les bundles sont validés localement (Anime-Sama + One Piece → 4 liens,
Frenchstream + Fight Club → 2 liens, avec véritable réseau). Pourtant sur le
téléphone : « ✗ aucun résultat » pour TOUS les serveurs Nuvio — même message
qu'avant la v9, donc runScraper ne posait **jamais** son diagnostic : soit
timeout, soit exception non couverte. Causes probables :

1. **Les tests partaient en parallèle** : l'écran « Tester » lançait un test
   par ligne simultanément — 26 moteurs Rhino + 26 flux de requêtes simultanés
   sur un réseau mobile (0,2 Ko/s sur la capture !) → saturation → timeout
   90 s dépassé → « aucun résultat » (et aucun message, car le test était
   annulé avant la fin du scrapeur).
2. Réseau lent : les timeouts (recherche 20 s, chargement 25 s) transformaient
   la lenteur en « 0 résultat » pour Wiflix / French Anime / Cofilx.

## Changements v10

- **Tests sérialisés** : les boutons « Tester » passent par le même sémaphore
  que la lecture réelle (6 en parallèle max) → plus de saturation ; timeout
  de test 150 s (extensions : 60 s).
- **Télémétrie dans les verdicts** :
  - chaque requête HTTP est journalisée (`GET anime-sama.to → 404 (4820ms)`) ;
  - la console JS des bundles est capturée (2 dernières lignes) ;
  - tout échec affiche maintenant : raison + dernières requêtes + dernière
    ligne console → on saura exactement si c'est un 404, un timeout, un
    blocage réseau ou un bug du bundle.
- **Timeouts** : recherche 35 s, chargement 50 s, agrégation 100 s (SourceHub) ;
  scrapeur Nuvio 90 s (au lieu de 60 s).
- « Recherche cassée » : si la recherche d'une extension lève une exception,
  le verdict l'affiche au lieu d'un « 0 résultat » muet.
- Version 10.

## À faire sur téléphone

1. Mettre à jour FR Unifié (v10) — réinstaller l'extension depuis le repo.
2. Fournisseurs Nuvio → Tester **un par un** (les tests sont maintenant
   sérialisés, ça prend plus longtemps mais chaque verdict est bon) :
   le message dira « ✓ anime: 4 lien(s) » ou « ✗ … | GET anime-sama.to → 404
   (5000ms) | js: [Anime-Sama] Total streams found: 0 » → renvoyer tel quel.
3. Sources CloudStream → Tester : les messages distinguent désormais site
   cassé / recherche lente / appariement insuffisant.
