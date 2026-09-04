# v13 — `StackOverflowError: stack size 1037KB` : Rhino n'a que 1 Mo de pile

Publiée le 2026-09-04. Version 13.

## Symptôme (capture utilisateur, v12)

```
Anime-Sama   anime: ✗ erreur JS : StackOverflowError: stack size 1037KB
Anime-Ultime anime: 0 lien
AnimesUltra  anime: 0 lien
```

La v12 a rendu la panne **visible** (elle était avalée silencieusement avant,
d'où le « 0 lien » muet) — mais elle ne l'a pas guérie.

## Cause racine

Le préfixe du message est déterminant : `✗ erreur JS` est émis **à l'évaluation
du bundle**, avant même que `getStreams` soit appelé. Ce n'est donc pas la
boucle de promesses (corrigée en v12) mais le **parseur/interpréteur Rhino**.

* Un thread Android a **1 Mo de pile** — l'appareil l'écrit noir sur blanc :
  `stack size 1037KB`. Sur un JDK de bureau, c'est 8 Mo par défaut : d'où
  « ça marche sur le banc d'essai, pas sur le téléphone », encore une fois.
* Les bundles Nuvio sont produits par **esbuild en mode minifié** : une seule
  expression peut imbriquer des centaines de niveaux (`a?b:c?d:e…`, chaînes de
  `&&`/`||`, IIFE emboîtées). Rhino descend l'AST **récursivement**, à raison
  d'environ un cadre de pile JVM par nœud.

Résultat : la pile déborde pendant `evaluateString(bundle)`. Les scrapeurs dont
le bundle est plus petit passaient l'évaluation mais restaient à 0 lien pour
d'autres raisons (sites morts) — d'où le mélange des deux verdicts.

Rhino ne propose aucun mode non récursif. La seule issue : **lui donner une
vraie pile**.

## Correctifs

1. **`withBigStack()`** — toute l'exécution Rhino (évaluation du bundle, appel
   de `getStreams`, boucle d'événements) part sur un `Thread` créé avec une
   pile de **32 Mo** :

   ```kotlin
   Thread(null, { … }, "nuvio-js-$id", 32L * 1024 * 1024)
   ```

   La pile est *virtuelle* : les pages ne sont allouées qu'à l'usage, le coût
   réel reste de quelques dizaines de Ko pour un bundle normal.
   `StackOverflowError` est rattrapé et remonté à l'appelant (au lieu de tuer
   le thread en silence), avec un verdict explicite :
   `✗ interne: pile insuffisante (bundle trop imbriqué)`.

2. **Frontière de thread propre.** Le `Context` Rhino est lié à son thread et
   `newExtractorLink` est une fonction `suspend` : on ne peut donc pas
   construire les liens à l'intérieur. Nouveau type **`RawStream`** (données
   purement Kotlin) lu par `readStream()` pendant que le scope est vivant ;
   les `ExtractorLink` sont fabriqués **après**, hors du thread JS.
   `currentScraper` (ThreadLocal, utilisé par le journal des requêtes) est
   désormais posé *dans* le thread JS.

3. **Borne dure** : `thread.join(SCRAPER_TIMEOUT_MS)` — un bundle parti en
   boucle infinie ne retient plus l'appelant (thread daemon).

## Ce que la v12 a apporté malgré tout

Elle reste nécessaire : sans sa boucle d'événements (micro-tâches + timers
datés), les bundles qui s'évaluent correctement rendaient une promesse jamais
réglée. v12 = *après* l'évaluation, v13 = *pendant*. Les deux étaient requises.

## À faire sur téléphone

1. Re-synchroniser le dépôt, mettre à jour l'extension (**v13**).
2. ⚙️ → Fournisseurs Nuvio → **Tester** Anime-Sama sur One Piece.
3. Le `StackOverflowError` doit avoir disparu. Si un scrapeur affiche encore
   0 lien, le verdict contient maintenant la vraie cause (404, template du site
   modifié, timeout) : le renvoyer tel quel.
