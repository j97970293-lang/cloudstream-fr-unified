# v22 — FR Hub : sources FR installables, filtres, nouveau nom

## 1. Nouveau nom : **FR Hub**

Vous aviez raison sur le fond : l'extension s'appelait « FR Unifié » alors
qu'elle n'unifie aucune source qui lui appartienne. Elle est un **hub** — elle
agrège les extensions installées et les addons Stremio. Le nom le dit
maintenant, et la description est explicite : *« N'héberge aucune source »*.

## 2. Sources françaises installables depuis l'extension

Nouvelle section ⚙️ **« 🇫🇷 Sources françaises »**. Chaque dépôt a été vérifié :
il existe, son `repo.json` est valide, et son contenu est listé ci-dessous.

### Dépôts CloudStream (bouton « Copier l'adresse »)

| Dépôt | Contenu vérifié |
|---|---|
| **AMSC French** | Wiflix-Flemmix, French Anime, FsMirrorLol-Frenchstream, Coflix, Frembed FR |
| **CloudStream FR** | French-Stream, Movix, FrenchManga, FSTV |
| **Movix** | Movix seul, 45+ extracteurs |
| **Cs-Karma** | 34 extensions, dont Movix et Yablom |

Vous retrouvez donc bien **Movix, FrenchHub/French-Stream, FrenchManga,
FsMirror** — ceux que vous citiez.

Une nuance importante : ces dépôts s'ajoutent dans **CloudStream**
(Paramètres → Extensions → Ajouter un dépôt), pas dans FR Hub. C'est une limite
d'Android, pas un choix : une extension ne peut pas en installer une autre.
D'où le bouton « Copier l'adresse » qui vous évite de la recopier à la main.

### Addons Stremio FR (bouton « Ajouter » — direct dans l'extension)

Ceux-là s'ajoutent **dans FR Hub**, sans passer par CloudStream :

- **TMDB (catalogue FR)** — `tmdb.elfhosted.com` — catalogue en français
- **StremioFR — Comet** — indexeur FR, compatible debrid
- **StremioFR — Jackettio** — indexeur FR, compatible debrid
- **OpenSubtitles v3** — sous-titres, gratuit

## 3. Filtres de liens — sur TOUTES les sources

Nouvelle section ⚙️ **« 🧪 Filtres de liens »**, appliquée aux extensions FR
**et** aux addons Stremio (nouveau fichier `LinkFilter.kt`, branché sur les
deux chemins) :

- **Qualités à exclure** : `360p, 480p, cam, ts`
- **Mots-clés à exclure** : `hdcam, telesync, x265, hevc, dvdscr`
- **Taille minimale / maximale** en Mo

Parsing de taille vérifié : `1.4 GB` → 1433 Mo, `2,5 Go` → 2560 Mo (virgule
française), `4.7GiB` → 4812 Mo.

Garde-fou : un lien **sans taille annoncée n'est jamais écarté** (seuls
certains addons l'indiquent). Le verdict par source indique désormais combien
de liens ont été filtrés : « ✓ 0 lien (7 écarté(s) par vos filtres) » — pour
que vous ne confondiez jamais « source morte » et « filtres trop stricts ».

## 4. Échec de compilation corrigé
Le premier build a échoué : continuation de chaîne `+` en début de ligne dans
`build.gradle.kts`, invalide en Kotlin. Corrigé, build vert en 1 min 22.

## Reste à faire
Le **réordonnancement** des rangées (monter/descendre) n'est pas dans cette
version — seules l'activation/désactivation existent. À livrer en v23.

## Vérification
`FrUnified.cs3` — 152 186 o, plugins.json version 22, hash conforme.
