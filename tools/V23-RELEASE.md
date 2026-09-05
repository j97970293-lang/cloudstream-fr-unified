# v23 — Pourquoi AIO Metadata ne se voyait pas

## Le bug, trouvé grâce à votre capture

Votre message d'erreur (« Aucun catalogue exploitable trouvé ») était **exact** :
au moment où vous appuyiez sur « Détecter », votre URL n'existait nulle part.

```kotlin
// AVANT — ligne 339 : le bouton Détecter
FrSettings.stremioUrls.forEach { addon -> … }   // ← liste ENREGISTRÉE

// ligne 777 : l'enregistrement du champ
FrSettings.stremioUrls = stremioField.text…     // ← seulement sur ENREGISTRER
```

Vous colliez l'addon, vous appuyiez sur « Détecter » → il parcourait la liste
du dernier enregistrement, qui ne contenait pas encore votre URL. Il ne
trouvait donc rien, et disait vrai. **L'addon n'était jamais interrogé.**

Impossible à contourner de votre côté : même en enregistrant puis en rouvrant,
l'ordre des sections rendait la manœuvre non évidente.

**Corrigé** : « Détecter » lit maintenant le texte **saisi à l'écran** et
l'enregistre avant de lancer la détection.

## Configuration séparée pour les catalogues — vous aviez raison

C'était bien lié. Un seul champ servait aux addons de flux **et** de catalogue.

Désormais deux champs distincts :

| Champ | Section | Pour |
|---|---|---|
| **Addons de CATALOGUE** | 🚀 Catalogues actifs | AIO Metadata, TMDB Addon |
| **Addons de FLUX** | 📺 Addons Stremio | Torrentio, Comet, debrid |

Repli conservé : si le champ catalogue est vide, la détection tente quand même
les addons de flux (certains publient les deux).

## Erreurs de détection explicites

Fini le message générique. La détection dit maintenant **quel** addon n'a rien
renvoyé :

- *« Aucun addon saisi »* → le champ est vide
- *« Aucun catalogue trouvé sur : aiometadata.creepso.com… »* → l'addon a été
  interrogé mais ne publie rien d'exploitable
- *« 3 rangée(s) détectée(s) »* → suivi de la marche à suivre

## Rappel affiché dans l'app : redémarrer CloudStream

Le message de succès précise désormais qu'il faut **redémarrer CloudStream**.
`mainPage` n'est lu qu'au chargement du plugin : une rangée détectée ne peut
pas apparaître sans redémarrage. C'était probablement la seconde cause de vos
« ça ne marche pas ».

## Vérification
`FrUnified.cs3` — 153 858 o, plugins.json version 23, hash conforme.
