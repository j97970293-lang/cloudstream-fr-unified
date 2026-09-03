# v11 — LA cause racine du « Nuvio ne marche pas » : deux ressources absentes du dex

Publiée le 2026-09-04. Version 11.

## Symptôme (capture utilisateur)

```
film: ✗ interne: can't find bundle for base name com.frunified.rhino.resources.Messages, locale …
```

pour **tous** les scrapeurs Nuvio, sur le téléphone uniquement — alors que le
même code sortait 4 liens (Anime-Sama / One Piece) sur le banc d'essai JVM.

## Cause racine (reproduite en local, enfin)

Rhino 1.9.1 récupère deux briques par **fichiers de ressources** :

| Brique | Fichier attendu | Rôle |
|---|---|---|
| `RegExpLoader` | `META-INF/services/com.frunified.rhino.RegExpLoader` | ServiceLoader → installe `RegExp` + son proxy |
| Messages | `com/frunified/rhino/resources/Messages.properties` | `ResourceBundle` → texte des erreurs/avertissements |

Un plugin CloudStream ne contient **qu'un `classes.dex`** : ni `META-INF/services`,
ni `.properties` ne peuvent y être transportés. Sur le téléphone :

1. `Context` ne trouve aucun `RegExpLoader` → `RegExp` n'est pas enregistré →
   **compiler un littéral `/…/` échoue** (`ScriptRuntime.checkRegExpProxy` →
   `reportRuntimeErrorById("msg.no.regexp")`) — or le préambule JS comme les
   bundles Nuvio en sont truffés ;
2. pour *rédiger* cette erreur, Rhino appelle `ResourceBundle.getBundle(...)`
   → bundle introuvable → **`MissingResourceException`** — qui remonte telle
   quelle et masque la vraie cause : « ✗ interne: can't find bundle … ».

D'où l'illusion « ça marche partout sauf sur l'appareil » : sur JVM le jar
complet est au classpath, les deux fichiers existent, jamais de panne.

### Reproduction reproductible (sandbox, sans Android)

Classpath = **classes seules** (ni `META-INF/`, ni `resources/`) → simulation
exacte du dex :

```
mini.py → /tmp/dexlike/cls
EXC: java.util.MissingResourceException: Can't find bundle for base name
     com.frunified.rhino.resources.Messages, locale en_US
  at com.frunified.rhino.ScriptRuntime$DefaultMessageProvider.getMessage
  at com.frunified.rhino.ScriptRuntime.getMessageById
  at com.frunified.rhino.Context.reportRuntimeErrorById            ← (2)
  at com.frunified.rhino.ScriptRuntime.checkRegExpProxy            ← (1)
  at com.frunified.rhino.CodeGenerator.generateRegExpLiterals
```

Avec le jar complet (JVM) : `typeofRegExp=function`, `literal=true`, tout passe.

## Correctifs

1. **`NuvioClient.installRuntime()`** — enregistre à la main ce que le
   ServiceLoader aurait dû fournir :
   * `ScriptRuntime.setRegExpProxy(cx, RegExpLoaderImpl().newProxy())` ;
   * `ScriptRuntime.registerRegExp(cx, scope, false)` (méthode privée, appelée
     par réflexion) → le constructeur global `RegExp` redevient disponible.
   Appelé juste après `initStandardObjects()`, avant toute évaluation.
2. **`com.frunified.rhino.resources.RhinoMessages.kt`** (nouveau) — les 334
   messages Rhino embarqués dans une **classe compilée** (`Messages` + variante
   minuscule `message`) qui étend `ResourceBundle` : elle est déxée avec le
   plugin, donc `ResourceBundle.getBundle()` la trouve sur l'appareil.
   `handleGetObject` ne renvoie jamais `null` (sinon MissingResourceException) :
   à défaut, la clé est renvoyée telle quelle.
3. **`FetchFunction.makeResponse`** renvoie `Undefined.instance` au lieu de
   `Scriptable.NOT_FOUND` (objet Java qui déclenchait l'avertissement
   « missed Context.javaToJS() » à chaque test de vérité sur la réponse).
4. **Bandeau « Moteur Rhino »** dans les réglages : auto-test au démarrage de
   l'écran (`typeof RegExp`, littéral `/…/`, message d'erreur JS lisible).
   Affiche `✓ moteur Rhino opérationnel (RegExp + messages)` ou la raison
   exacte — plus jamais une panne muette.

Vérifié dans le harnais (classes seules + correctifs) : `typeofRegExp=function`,
`literal=true`, `groups={"y":"a"}`, `lookbehind=true`, `replaceRe=a+b+c`, et les
26 bundles Gowaru s'évaluent + `getStreams` répondent.

## Autres changements

- **Nouveau logo** du plugin (`icon.png`, 256×256, tricolore + lecture) —
  référencé par `iconUrl` dans `FrUnified/build.gradle.kts` et `repo.json`
  (plus de favicon TMDB).
- Version 11.

## À faire sur téléphone

1. Re-synchroniser le dépôt puis mettre à jour l'extension (v11).
2. Réglages → **⚙️** → le bandeau sous « Scrapeurs Nuvio » doit afficher
   `✓ moteur Rhino opérationnel (RegExp + messages)`.
3. Fournisseurs Nuvio → **Tester** 2-3 serveurs (Anime-Sama sur One Piece,
   Frenchstream sur Fight Club) : les verdicts doivent passer au vert.
4. Si un serveur reste à 0, le verdict contient désormais la vraie raison
   (404, timeout, template de site modifié…) au lieu du « can't find bundle ».
