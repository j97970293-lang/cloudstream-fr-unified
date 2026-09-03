# v8 — LA cause racine : Rhino masqué par l'app CloudStream (relogé en com.frunified.rhino)

Publiée le 2026-09-03 (builds/FrUnified.cs3 821 249 o, sha256
`108f978a6b9121e18a91b36cc80f24fb7f3265bc9f03b40f513761c69975d044`).

## Le vrai bug (enfin trouvé — reproduit en local)

Sur le téléphone, la v7 renvoyait exactement la même erreur de syntaxe ligne 5
que la v5, alors que le même jar passait les 46 bundles sur JVM. La capture
montrait le format de diagnostic v7 → le téléphone exécutait bien la v7, avec
le parseur patché dans le dex… mais l'erreur persistait.

**Cause : l'app CloudStream embarque SON propre Rhino stock.** Vérifié : l'APK
CloudStream v4.8.0 contient 816 références `org/mozilla/javascript` dans
`classes8.dex`. Le classloader du plugin est parent-first : à chaque
`org.mozilla.javascript.*`, il chargeait le Rhino **stock de l'app** au lieu de
notre Rhino patché (celui-ci était pourtant dans le dex du plugin). Tout ce qui
reposait sur nos patchs parseur (spread d'appel, yield en argument, générateurs)
était donc inactif sur l'appareil — d'où les erreurs de syntaxe « uniquement sur
téléphone » depuis le début.

Reproduction JVM : classpath = Rhino stock 1.9.1 (Maven Central) puis jar patché
→ `PARSE-FAIL line=5` identique au téléphone ; jar patché seul → RUN-OK.

## Fix définitif : relocalisation du package

Tout Rhino (patché) est relogé de `org.mozilla.javascript` → `com.frunified.rhino`
(bytecode ASM : classes, descripteurs, string constants LDC/ConstantValue,
chemins de ressources, **fichier META-INF/services** — le ServiceLoader
`RegExpLoader` qu'on avait failli oublier, sans lui `typeof RegExp` échoue).
Plus aucun nom de classe commun avec l'app → masquage impossible.

- `tools/rhino-patched-base-1.9.1.jar` : jar patché d'origine (référence).
- `tools/relocate-rhino/` : `Relocate.java` (réécriture ASM) + `build-relocated.sh`
  (télécharge ASM, produit libs/rhino-nuvio + libs/rhino-embed relogés).
- `build.gradle.kts` : `extractRhino` Copy → **Sync** (sinon les vieilles classes
  org.mozilla d'un build précédent restaient déxées en double).
- `NuvioClient.kt` : imports `com.frunified.rhino.*`.
- `SettingsDialog.kt` : nouvelle section **« Sources CloudStream »** (liste des
  extensions FR détectées, cases à cocher, bouton Tester par source, verdict) —
  « je ne trouve plus les extensions de cloudstream » : la liste n'existait pas
  dans les réglages, seulement un interrupteur global.

## Validation

- 46/46 bundles (Gowaru 26 + z7kx 20, re-téléchargés) : parse → compile →
  getStreams OK avec le jar relogé (moteur interprété, comme le téléphone).
- Scénario « app qui masque » : classpath Rhino stock + relogé → RUN-OK (preuve).
- Dex final : 1435 réf. com/frunified/rhino ; 1 seule réf. orpheline
  `Lorg/mozilla/javascript/Callable` (string interne de PolicySecurityController,
  classe jamais chargée par les scrapeurs — sans impact).

## À faire

1. Mettre à jour FR Unifié (même dépôt) ; réglages → Fournisseurs → Tester :
   le vert est attendu (les 46 bundles tournent avec le VRAI parseur patché).
2. Nouvelle carte « Sources CloudStream » : tester chaque extension FR
   installée et renvoyer les verdicts (prochaine étape : réparer celles qui
   échouent — structures non-TMDB déjà gérées : AnimeSearchResponse.year,
   tmdbId multi-titres, langues startsWith("fr")).
3. Révoquer le token GitHub `ghp_tlObE…`.
