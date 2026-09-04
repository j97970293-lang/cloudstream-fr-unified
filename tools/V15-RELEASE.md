# v15 — « 0 lien » enfin motivé, et fin de l'attente interminable

Publiée le 2026-09-04. Version 15.

## Où on en est

Le `StackOverflowError` a disparu : la pile est réglée (v13/v14). Restaient
deux griefs distincts, traités ici :

1. `anime: 0 lien` — sans la moindre indication de cause ;
2. « la recherche du serveur prend trop de temps alors qu'on sait qu'il n'y a
   rien ».

## 1. Le « 0 lien » était le seul verdict muet

Tous les chemins d'échec joignaient `diagSuffix()` (journal des requêtes HTTP +
dernière ligne console JS)… **sauf** le cas « le scrapeur a fini normalement
mais n'a rien rendu » — précisément celui qui restait. D'où un verdict
inexploitable.

Désormais :

```
✓ 0 lien · aucun flux renvoyé | GET anime-sama.fr → 0 (8003ms) | js: no results
✓ 0 lien · flux inexploitables (3) | GET …
```

On distingue enfin *site injoignable*, *site OK mais aucun résultat*, et *flux
renvoyés mais inutilisables* (URL non http, format inconnu).

## 2. L'attente : trois causes cumulées

### a) Les liens n'étaient affichés qu'à la toute fin

`loadLinks` **accumulait** les liens Nuvio dans une liste, les triait, puis les
envoyait au lecteur — après que les 26 scrapeurs aient terminé, soit jusqu'à
**3 minutes d'écran vide** même quand le premier serveur avait répondu en 4 s.

Les liens sont maintenant transmis **au fil de l'eau** (avec dédoublonnage par
URL : plusieurs scrapeurs partagent les mêmes hébergeurs). Les premiers
serveurs apparaissent en quelques secondes.

Conséquence assumée : le tri par motifs prioritaires (VF, FRENCH, 1080…) ne
peut plus se faire après coup. Il est réappliqué **à la source**, sous forme de
bonus de qualité (`priorityBonus`) — CloudStream classant déjà les serveurs par
qualité décroissante, le réglage ⚙️ continue de fonctionner.

### b) Des délais réseau calibrés pour un réseau fixe

| | avant | après |
|---|---|---|
| connexion | 30 s | **8 s** |
| lecture | 90 s | **20 s** |
| budget par scrapeur | 90 s | **45 s** |
| budget total Nuvio | 180 s | **75 s** |

Un site FR qui n'a pas répondu en 8 s ne répondra pas.

### c) Les domaines morts étaient retentés indéfiniment

Beaucoup de sites des bundles sont définitivement hors service
(french-anime.net, wiflix.*, coflix.to… — déjà constaté en v10). Chaque
scrapeur retentait chaque domaine mort, et payait le délai de connexion à
chaque requête.

Nouveau cache **`deadHosts`** : un hôte qui échoue sur `UnknownHostException`,
`ConnectException`, `SocketTimeoutException` ou `SSLException` est écarté
pendant **10 minutes**. Les requêtes suivantes échouent instantanément.

## À faire sur téléphone

1. Mettre à jour en **v15** (vérifier le bandeau : `✓ moteur Rhino v15 · pile
   JS : …`).
2. Ouvrir une fiche : les premiers serveurs doivent apparaître en quelques
   secondes au lieu d'attendre la fin.
3. Pour les scrapeurs encore à 0, le verdict indique maintenant la cause :
   me le renvoyer tel quel permettra de trier ceux qui sont réparables de ceux
   dont le site est mort (à retirer du dépôt Nuvio en amont).
