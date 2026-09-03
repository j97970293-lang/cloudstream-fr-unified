# v7 — Moteur Nuvio : cause trouvée, regex supprimé, diagnostics autoportants

Publiée le 2026-09-03 (master `168be42`, builds/FrUnified.cs3 817 793 o,
sha256 `684e76249462d461b0cb5edd364425080bd662394de803d274618e3fc75d38d2`).

## Diagnostic (capture utilisateur 16:40 : « erreur de syntaxe (anime-sama#5) »)

Le message était en **français** → c'est `Messages_fr.properties` de Rhino →
**vraie erreur de parseur**. Or « erreur de syntaxe » n'existe dans aucun build
du dépôt en tant que chaîne : c'est la traduction Rhino de `msg.syntax`, émise
par le parseur **non patché** → le téléphone exécutait la **v5** (jar vérifié
dans git : 0 occurrence de `spreadApplyCall`/`cloneExpr`, alors que la v6 en a 3).

Vérifications :
- Les 46 bundles ont été **re-téléchargés aujourd'hui** (Gowaru 26 + z7kx 20,
  manifests officiels) et exécutés avec le moteur v6 (`optimizationLevel=-1`,
  préambule JS réel, module/exports, getStreams) : **46/46 OK**.
- La v6 publiée contient bien le parseur patché (strings `cloneExpr` dans le dex).
- **Bug réel trouvé et corrigé** : `normalizeBundle` (regex `SPREAD_CALL`) était
  encore appliqué sur le téléphone — seul écart avec le banc d'essai. Supprimé :
  le parseur patché gère le spread nativement (`__spread` dans le préambule).

## Changements v7

- `NuvioClient.kt` : regex + `normalizeBundle` retirés ; cache → `nuvio-v7`
  (re-téléchargement forcé) ; erreurs diagnostiques complètes
  (`Classe: message | ligne N: extrait du code`) ; `tmdbId()` essaie **tous les
  titres** de la fiche (fiches AniList/MAL sans id TMDB → id trouvé par recherche).
- `SourceHub.kt` : langues élargies (`startsWith("fr")`) ; `AnimeSearchResponse`
  scoré avec son année (structure anime, non-TMDB).
- Version 7 ; `plugins.json`/`prebuilt` mis à jour.

## À valider sur téléphone

1. CloudStream → mettre à jour **FR Unifié** (même dépôt, URL inchangée).
2. Réglages → Fournisseurs Nuvio → Tester. Attendu : vert partout (ou message
   ligne + extrait si un cas résiste — à renvoyer tel quel).
3. Révoquer le token GitHub `ghp_tlObE…` (Settings → Developer settings).
