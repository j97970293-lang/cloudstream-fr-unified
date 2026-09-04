# v17 — n'utiliser que les extensions vivantes

Publiée le 2026-09-04. Version 17.

## Symptôme

```
✗ fiche introuvable (recherche cassée: Unexpected character …)
✗ fiche introuvable (recherche cassée: Expected a valid value …)
```

## Ce que ces messages signifient

`✗ fiche introuvable` est notre texte. Mais « Unexpected character » et
« Expected a valid value » ne le sont pas : ils sont **recopiés tels quels**
depuis l'exception levée par l'extension FR (`SourceHub`, recherche).

C'est la signature d'un **parseur JSON**. L'extension a interrogé son site et a
reçu autre chose que du JSON : page d'erreur HTML, redirection de domaine, ou
écran anti-bot Cloudflare. Autrement dit **le site a changé et l'extension n'a
pas suivi** — exactement ce que le rapport v10 avait relevé pour Wiflix (devenu
Flémmix), French-Anime et Coflix.

Ce n'est pas une régression de la v16 : ces extensions étaient déjà cassées,
Nuvio masquait partiellement le problème en fournissant des liens en parallèle.

Le correctif de fond appartient aux auteurs de ces extensions. Ce que **nous**
pouvons faire : ne plus les laisser pénaliser les sources saines.

## Correctifs

1. **Quarantaine automatique.** Une extension dont la recherche échoue est
   écartée temporairement :
   * erreur de parsing (site périmé) → **30 min** ;
   * simple timeout ou lenteur → **5 min** (c'est peut-être passager).

   Les lectures suivantes n'interrogent plus que les sources vivantes.

2. **Verdict explicite** au lieu du jargon technique :

   ```
   ✗ extension périmée (le site ne renvoie plus de données valides) — écartée 30 min
   ```

3. **Délais divisés par ~3** — une extension morte ne fait plus patienter :

   | | v16 | v17 |
   |---|---|---|
   | recherche | 35 s | **15 s** |
   | chargement fiche | 50 s | **20 s** |
   | liens | 100 s | **35 s** |

4. **Bug corrigé : les échecs de panne étaient mis en cache 30 min.** Le cache
   d'appariement mémorisait indistinctement « pas trouvé » et « site en
   panne ». Une extension réparée entre-temps serait donc restée ignorée. Seuls
   les échecs *propres* (le site a répondu, sans correspondance) sont désormais
   mémorisés.

5. **Réactivation garantie.** La quarantaine est levée : par le bouton
   « Tester » (qui vide aussi le cache de cette source), et à l'enregistrement
   des réglages — après une mise à jour de vos extensions, tout est réessayé.

6. **Bandeau d'information** dans ⚙️ → *Sources CloudStream* listant les
   extensions écartées et la marche à suivre.

## À faire sur téléphone

1. Mettre à jour en **v17**.
2. ⚙️ → *Sources CloudStream* : le bandeau rouge liste les extensions périmées.
3. Pour celles-là : les mettre à jour dans CloudStream (Extensions → leur
   dépôt) si une version corrigée existe, sinon **les décocher**.
4. Les extensions saines fonctionnent normalement et ne sont plus ralenties.
