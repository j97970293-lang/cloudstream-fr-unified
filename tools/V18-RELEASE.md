# v18 — Réglages retrouvés, purge des sources HS, épisodes récents

## 1. « Je ne trouve plus les paramètres »
Les réglages n'avaient pas disparu : **toutes les sections de l'écran ⚙️ étaient
repliées par défaut**. Le compteur « X / Y activées » était donc invisible tant
qu'on n'avait pas tapé sur l'en-tête.

- Les sections **« Réglages de scraping »** et **« Sources CloudStream »**
  s'ouvrent maintenant d'emblée.
- Le résumé affiche `X / Y activées · N hors service`.

## 2. « Je ne trouve plus FrenchHub »
Cause : la détection ne gardait que les extensions dont `lang` commence par
`fr`. Une extension française qui se déclare en `en`, `universal` ou sans langue
disparaissait de la liste.

- Détection élargie : repli sur le nom et l'URL (`french`, `vostfr`, `wiflix`,
  `movix`, `frembed`…) quand la langue n'est pas renseignée.
- Une extension explicitement rattachée à une autre langue reste exclue.
- Nouvelle case **« Afficher toutes les extensions installées (même non FR) »**
  en dépannage : plus rien ne peut rester invisible.

## 3. « Supprimer les sources CloudStream qui ne marchent pas »
Nouveau bouton **« 🧹 Tester tout et désactiver ce qui ne marche pas »** :
teste chaque extension une par une, affiche la progression, puis **décoche
automatiquement** celles qui ne renvoient rien d'exploitable.

Les extensions ne sont pas désinstallées (leur code ne nous appartient pas),
elles sont simplement écartées pour ne plus ralentir les lectures.

## 4. Épisodes récents de One Piece absents
Vrai bug, corrigé. AniList renvoie `episodes: null` pour un animé **encore en
diffusion**. Le code retombait alors sur une valeur par défaut de **24
épisodes** : au-delà, les épisodes n'existaient tout simplement pas dans la
fiche — donc aucun serveur, puisqu'il n'y avait rien à interroger.

Désormais, dans l'ordre :
1. `episodes` s'il est renseigné ;
2. sinon `nextAiringEpisode - 1` (dernier épisode paru) ;
3. sinon le vrai décompte via la liste d'épisodes Jikan/MAL ;
4. 24 en tout dernier recours.

Le même défaut existait sur la fiche MAL/Jikan : corrigé aussi.

## Vérification
`FrUnified.cs3` — 125 201 o, plugins.json version 18, hash conforme.
