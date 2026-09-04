# v14 — vérifier *quelle version tourne*, et repli de pile 256 Mo

Publiée le 2026-09-04. Version 14.

## Contexte

Après la v13 (exécution Rhino sur un thread de 32 Mo), l'utilisateur signale
« même problème qu'au précédent ».

C'est un point de bascule du diagnostic, car **le message v12 ne peut pas être
reproduit à l'identique par le code v13** :

> `StackOverflowError: stack size 1037KB`

ART rapporte ici la taille de pile **du thread courant**. Sur un thread créé
avec 32 Mo, ce nombre serait forcément différent. Donc :

| Ce qui s'affiche | Ce que ça signifie |
|---|---|
| exactement `stack size 1037KB` | **l'ancien code tourne encore** (mise à jour non appliquée) |
| `pile 256 Mo dépassée … [v14]` | le nouveau code tourne, et 256 Mo n'ont pas suffi |
| autre message (404, timeout…) | la pile est réglée, la cause est ailleurs |

La v13 était publiée et vérifiée sur `builds` (marqueurs présents dans le dex),
mais rien dans l'interface ne permettait de savoir quelle version était
réellement *installée* sur l'appareil. C'est ce que corrige la v14.

## Correctifs

1. **Bandeau « Moteur » autovérifiant.** Il affiche désormais le build et la
   profondeur de récursion JS **réellement mesurée** sur l'appareil :

   ```
   ✓ moteur Rhino v14 · pile JS : 148 522 niveaux
   ```

   * quelques **milliers** de niveaux → pile de 1 Mo → ancien code ;
   * > 100 000 → thread à grande pile actif → v14 en place.

   Plus besoin de deviner si la mise à jour a été prise en compte.

2. **Le `StackOverflowError` n'est plus avalé.** Défaut réel de la v13 : trois
   `catch (t: Throwable)` situés *à l'intérieur* du thread JS interceptaient le
   débordement (évaluation du bundle, appel de `getStreams`, garde-fou général).
   Le repli n'aurait donc jamais pu se déclencher. Ils relancent maintenant
   explicitement `StackOverflowError`.

3. **Repli automatique 32 Mo → 256 Mo.** `withBigStack` réessaie sur une pile de
   256 Mo si 32 Mo débordent. La mémoire est *virtuelle* (réservation
   d'adresses) : les pages ne sont engagées qu'à l'usage réel.

4. **Message final non ambigu** : `✗ interne: pile 256 Mo dépassée (bundle trop
   imbriqué) [v14]` — impossible à confondre avec l'ancien « 1037KB ».

5. L'auto-test du moteur tourne lui aussi sur le thread à grande pile, comme
   les scrapeurs (il testait auparavant des conditions différentes des leurs).

## À faire sur téléphone — important

La mise à jour doit être **effective**, c'est le point à vérifier en premier :

1. Paramètres → Extensions → dépôt FR Unifié → **rafraîchir**, puis mettre à
   jour jusqu'à voir **v14**.
2. Si la version ne bouge pas : **désinstaller** l'extension, puis la
   réinstaller (CloudStream met parfois en cache le `.cs3` téléchargé).
3. Ouvrir ⚙️ et lire le bandeau **Moteur** en haut de « Scrapeurs Nuvio » :
   * `v14 · pile JS : > 100 000 niveaux` → le correctif est actif ;
   * `v13`/`v12` ou une petite valeur → l'ancien code tourne toujours, inutile
     de tester les scrapeurs, il faut d'abord régler l'installation.
4. Alors seulement : tester Anime-Sama, et me renvoyer le bandeau **et** le
   verdict du scrapeur.
