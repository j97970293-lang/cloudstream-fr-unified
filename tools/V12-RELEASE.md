# v12 — « anime : 0 lien » : la boucle d'événements JS ne finissait jamais

Publiée le 2026-09-04. Version 12.

## Symptôme

Après la v11 (moteur Rhino enfin opérationnel : plus de « can't find bundle »),
les tests Nuvio répondaient toujours :

```
anime: 0 lien
```

c'est-à-dire : le bundle s'évalue, `getStreams` existe, l'appel ne lève rien…
mais **le résultat n'arrive jamais**.

## Cause racine

Le préambule JS n'avait pas de vraie boucle d'événements :

1. **Promesses purement synchrones.** `then()` exécutait le callback *en place*.
   Or les bundles Nuvio (limiteur de débit `delay()` généré par esbuild) attendent
   ainsi :

   ```js
   let s = () => Date.now() >= n ? resolve() : Promise.resolve().then(s);
   s();
   ```

   Avec des `then` synchrones, chaque tour **ré-entre dans lui-même** : la pile
   grossit d'un cran par itération jusqu'au `StackOverflowError` (silencieux, il
   était avalé par le `try/catch` du drain). La promesse restait non résolue.

2. **Timers sans échéance.** `setTimeout(fn, ms)` empilait `fn` en ignorant `ms`,
   et `__drain()` les exécutait immédiatement, en boucle, limitée à 80 passes.
   Un `setTimeout` reprogrammé (retry, backoff) épuisait le quota avant la fin.

Résultat : `getStreams()` renvoyait une promesse jamais réglée → `__settled`
faux → aucun flux lu → « ✓ 0 lien » pour tous les scrapeurs.

### Reproduction (harnais Node, préambule extrait tel quel)

| préambule | verdict |
|---|---|
| v11 | `settled=false value=undefined out=` |
| v12 | `settled=true value=[{"url":"https://x/y.m3u8","name":"VF 1080p"}]` |

Même scénario : `delay(120)` en chaîne de promesses puis un `setTimeout(300ms)`
— exactement le schéma des bundles Gowaru.

## Correctifs

1. **File de micro-tâches.** `Promise.then` met désormais ses callbacks dans
   `__micro` ; `__runMicro()` les exécute dans une **boucle** (trampoline).
   Plus aucune récursion : la chaîne `Promise.resolve().then(s)` peut tourner
   des millions de fois à profondeur de pile constante.
2. **Timers datés.** `setTimeout` enregistre `{ id, fn, due: Date.now()+ms }`,
   `clearTimeout` fonctionne réellement, `setInterval` ne se déclenche qu'une
   fois (un vrai intervalle ne terminerait jamais).
3. **`__drain(budget)`** : boucle micro-tâches → timer le plus proche → attente
   réelle via `__sleep` (Kotlin, pas d'attente active), jusqu'à épuisement ou
   expiration du budget. Les timers dont l'échéance dépasse le budget (les
   timeouts internes de 45 s des bundles, `AbortSignal.timeout`) sont
   abandonnés au lieu de bloquer le scrapeur.
4. **Côté Kotlin** : le drain reçoit le budget restant du scrapeur
   (`SCRAPER_TIMEOUT_MS − 5 s`) et s'arrête à l'échéance ; nouvelle fonction
   native `__sleep(ms)` (pas bornés à 500 ms, annulables).
5. `queueMicrotask` passe par la même file.

## À faire sur téléphone

1. Re-synchroniser le dépôt, mettre à jour l'extension (v12).
2. Réglages → ⚙️ → Fournisseurs Nuvio → **Tester** Anime-Sama (One Piece) :
   le verdict doit indiquer `✓ anime: N lien(s)`.
3. Si un scrapeur reste à 0, le verdict contient la vraie raison (404, template
   du site modifié…) : le renvoyer tel quel.
