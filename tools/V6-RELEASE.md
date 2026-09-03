# v6 — Moteur Nuvio : tous les bundles s'exécutent

Publiée le 2026-09-03 (master `65c1d19`, branche `builds` : FrUnified.cs3 817 182 o,
sha256 `96c940926460686f59199232fad812dd6812cb693a192412ae8202367f4d401b`).

## Cause racine du bug (CCE Node→Name, 16 bundles en CRASH)

`f(...a)` était réécrit par le patch Rhino en `f.apply(thiz, __spread(a))`.
Quand la cible était une propriété (`s.push(...x)`), le `thiz` (`s`) était
réutilisé **tel quel** : le même objet AST se retrouvait à la fois cible du
`PropertyGet` et 1er argument du `.apply`. Le `transform()` de l'IRFactory
retourne les nœuds `Name` tels quels (pas de copie) : le champ `next`
(chaînage des siblings) devenait partagé → le nœud CALL venait polluer le
GETPROP → `ClassCastException: Node → Name` dans `CodeGenerator.visitExpression`.

## Fix (Parser.java `spreadApplyCall`)

`thiz` est maintenant **cloné** (`cloneExpr()`, nouveaux nœuds frais pour
Name/This/String/Number/Null/TRUE/FALSE/RegExp/GetProp/GetElem/Call/Paren/
ArrayLit/Hook/Unary). `s.push(...x)` → `s.push.apply(clone(s), __spread(x))`.
Polyfill `__spread` ajouté au préambule JS (`NuvioClient.kt` JS_ENV).

## Validation

- AllParse (46 bundles) : **46 OK / 0 FAIL / 0 CRASH**.
- RunBundle (nouveau harnais d'EXÉCUTION, `optimizationLevel = -1` comme
  NuvioClient, fetch offline) : **46/46 : parse → compile → getStreams OK**.
- Comp (cas minimaux) : 5/5 OK (`s.push(...h.value)`, `Math.max(...i)`,
  `[1,...i,3]`, `f(...a)`, `[...new Set(h)]`).
- Tous les débogues temporaires retirés (vérifié par `strings` sur le jar).
- `tools/rhino-full-patches.patch` = diff complet (989 l., 19 fichiers).

## Reste à faire

1. Tester sur téléphone (installer la v6 depuis le dépôt — l'utilisateur confirmera).
2. Révoquer le token GitHub `ghp_tlObE…` (impossible par API : via github.com →
   Settings → Developer settings → Personal access tokens). Aucun fichier du
   dépôt ne le contient ; `git remote` local ne le contient plus.
3. Exigence encore ouverte : diagnostiquer les extensions CloudStream FR qui ne
   marchent pas (sauf frenchub / french-stream) — boutons Tester par source.
4. Préciser la « mise à jour automatique » (extension ou dépôts Nuvio).
