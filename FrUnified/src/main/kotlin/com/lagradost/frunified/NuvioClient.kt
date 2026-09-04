package com.lagradost.frunified

import android.content.Context
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import com.frunified.rhino.BaseFunction
import com.frunified.rhino.Context as RhinoContext
import com.frunified.rhino.ScriptRuntime
import com.frunified.rhino.NativeArray
import com.frunified.rhino.Scriptable
import com.frunified.rhino.ScriptableObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Moteur « Nuvio » : exécute les scrapeurs JavaScript locaux du projet Nuvio
 * (dépôts Gowaru, Phisher, Michat88…) directement dans l'extension.
 *
 * Chaque scrapeur expose `getStreams(tmdbId, mediaType, seasonNum, episodeNum)`
 * et retourne une liste de flux. On fournit un petit environnement JS
 * (fetch, Promise, URL, Base64, setTimeout…) branché sur le réseau Android,
 * puis on convertit les flux en [ExtractorLink] pour le lecteur CloudStream.
 */
object NuvioClient {

    /** v12 : boucle d'événements JS (micro-tâches + timers datés) — cf. tools/V12-RELEASE.md */

    private const val SCRIPT_TTL_MS = 12 * 60 * 60 * 1000L   // 12 h
    private const val MANIFEST_TTL_MS = 6 * 60 * 60 * 1000L  // 6 h
    private const val SCRAPER_TIMEOUT_MS = 90_000L
    private const val NUVIO_CONCURRENCY = 6

    /**
     * Pile des threads d'exécution JS.
     *
     * Un thread Android a **1 Mo** de pile (le message d'erreur le disait :
     * « StackOverflowError: stack size 1037KB »). Or les bundles Nuvio sont
     * minifiés par esbuild : une seule expression peut imbriquer des centaines
     * de niveaux, et le parseur/interpréteur Rhino consomme ~1 cadre de pile
     * JVM par nœud d'AST. `evaluateString` sur le bundle débordait donc AVANT
     * même d'exécuter `getStreams`.
     *
     * Rhino ne sait pas fonctionner autrement qu'en récursif : la seule
     * solution est de lui donner une vraie pile. 32 Mo de pile *virtuelle*
     * (réservation d'adresses, pages allouées à l'usage : le coût réel reste
     * de quelques dizaines de Ko pour un bundle normal).
     */
    private const val JS_STACK_BYTES = 32L * 1024 * 1024

    /**
     * Exécute [block] sur un thread dédié à grande pile et rend son résultat.
     * Toute exception (y compris [StackOverflowError]) est propagée à l'appelant.
     */
    private fun <T> withBigStack(name: String, block: () -> T): T {
        val result = java.util.concurrent.atomic.AtomicReference<Any?>(null)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val thread = Thread(null, {
            try {
                result.set(block())
            } catch (t: Throwable) {
                // StackOverflowError inclus : on le remonte au lieu de tuer le thread
                error.set(t)
            }
        }, "nuvio-js-$name", JS_STACK_BYTES)
        thread.isDaemon = true
        thread.start()
        // Borne dure : un bundle parti en boucle infinie ne doit pas retenir
        // l'appelant. Le thread est daemon, il n'empêchera pas l'arrêt du process.
        thread.join(SCRAPER_TIMEOUT_MS)
        if (thread.isAlive) {
            runCatching { @Suppress("DEPRECATION") thread.interrupt() }
            throw java.util.concurrent.TimeoutException("délai dépassé")
        }
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    private const val NETWORK_TIMEOUT_MS = 30_000

    data class NuvioScraper(
        val id: String,
        val name: String,
        val filename: String,
        val repoBase: String,
        val supportedTypes: List<String>,
        val contentLanguage: List<String>,
        val description: String,
        val manifestEnabled: Boolean = true
    ) {
        val isFrench: Boolean get() = contentLanguage.any { it.startsWith("fr") }
        val scriptUrl: String
            get() = ("$repoBase/$filename")
                .replace(Regex("https://+"), "https://")
                .replace(Regex("http://+"), "http://")
    }

    private val manifestCache = ConcurrentHashMap<String, Pair<Long, List<NuvioScraper>>>()
    private val tmdbCache = ConcurrentHashMap<String, Pair<Long, Int?>>()
    @Volatile private var semaphore = Semaphore(NUVIO_CONCURRENCY)

    /** Sémaphore des seuls TESTS (2 max) : ne bloque jamais la lecture réelle. */
    private val testSemaphore = Semaphore(2)

    /** Re-crée le sémaphore si l'utilisateur a changé la concurrence. */
    private fun syncSemaphore() {
        val wanted = FrSettings.nuvioConcurrency
        if (semaphore.availablePermits != wanted) semaphore = Semaphore(wanted)
    }

    /** Résultats du dernier passage (id scrapeur -> « ✓ 12 liens » ou « ✗ raison »). */
    private val lastResults = ConcurrentHashMap<String, String>()
    fun diagnostics(): Map<String, String> = lastResults.toMap()

    /** Télémétrie par scrapeur : dernières requêtes HTTP et lignes console JS. */
    private val fetchLog = ConcurrentHashMap<String, MutableList<String>>()
    private val consoleLog = ConcurrentHashMap<String, List<String>>()
    private val currentScraper = ThreadLocal<String?>()

    @Volatile
    private var cacheDir: File? = null

    fun init(context: Context) {
        if (cacheDir == null) {
            cacheDir = runCatching { File(context.filesDir, "nuvio-v7").apply { mkdirs() } }.getOrNull()
        }
    }

    // ------------------------------------------------------------ dépôts

    /** Tous les scrapeurs des dépôts configurés (filtrés : activés + langues). */
    suspend fun scrapers(): List<NuvioScraper> = coroutineScope {
        val repos = FrSettings.nuvioRepos.ifEmpty { FrSettings.DEFAULT_NUVIO_REPOS }
            .filter { FrSettings.isNuvioRepoEnabled(it) }
        repos.map { repo ->
            async { withTimeoutOrNull(20_000L) { manifest(repo) }.orEmpty() }
        }.awaitAll().flatten()
            .filter { FrSettings.isNuvioEnabled(it.id) }
            .filter { it.manifestEnabled }
            .filter { it.supportedTypes.any { t ->
                val tt = t.lowercase()
                tt == "movie" || tt == "tv" || tt == "series" || tt == "anime" ||
                    tt == "cartoon" || tt == "animation" || tt == "anime_movie"
            } }
            .filter { FrSettings.nuvioAllLangs || it.isFrench || it.contentLanguage.isEmpty() }
            .distinctBy { it.id }
            .sortedWith { a, b ->
                val oa = FrSettings.nuvioOrder.indexOf(a.id).let { if (it < 0) Int.MAX_VALUE else it }
                val ob = FrSettings.nuvioOrder.indexOf(b.id).let { if (it < 0) Int.MAX_VALUE else it }
                if (oa != ob) oa.compareTo(ob)
                else {
                    val fa = if (a.isFrench) 0 else 1
                    val fb = if (b.isFrench) 0 else 1
                    if (fa != fb) fa.compareTo(fb) else a.name.lowercase().compareTo(b.name.lowercase())
                }
            }
    }

    private suspend fun manifest(repo: String): List<NuvioScraper> {
        manifestCache[repo]?.let { (expiry, list) -> if (expiry > System.currentTimeMillis()) return list }
        val cached = manifestCache[repo]?.second ?: emptyList()

        val json = runCatching {
            withContext(Dispatchers.IO) { JSONObject(httpGet(repo.trim(), emptyMap())) }
        }.getOrNull() ?: return cached
        val array = json.optJSONArray("scrapers") ?: return cached

        val base = repo.trim().removeSuffix("/").removeSuffix("manifest.json").removeSuffix("/")

        val list = (0 until array.length()).mapNotNull { i ->
            val entry = array.optJSONObject(i) ?: return@mapNotNull null
            val filename = entry.optString("filename").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NuvioScraper(
                id = entry.optString("id").ifBlank { filename },
                name = entry.optString("name").ifBlank { filename },
                filename = filename,
                repoBase = base,
                supportedTypes = stringArray(entry.optJSONArray("supportedTypes")),
                contentLanguage = stringArray(entry.optJSONArray("contentLanguage")),
                description = entry.optString("description"),
                manifestEnabled = entry.optBoolean("enabled", true)
            )
        }

        manifestCache[repo] = (System.currentTimeMillis() + MANIFEST_TTL_MS) to list
        return list
    }

    private fun stringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() } }
    }

    // ----------------------------------------------------- exécution JS

    private fun scriptFile(id: String): File? =
        cacheDir?.let { File(it, "${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.js") }

    private suspend fun script(scraper: NuvioScraper): String? {
        val file = scriptFile(scraper.id)
        val fresh = file?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < SCRIPT_TTL_MS }
        if (fresh != null) {
            val cached = runCatching { fresh.readText() }.getOrNull()
            if (!cached.isNullOrBlank()) return cached
        }
        val code = runCatching {
            withContext(Dispatchers.IO) { httpGet(scraper.scriptUrl, emptyMap()) }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (code != null) runCatching { file?.writeText(code) }
        return code
    }

    /** Exécute tous les scrapeurs activés et relaie leurs flux. */
    suspend fun streams(payload: PlayPayload, callback: (ExtractorLink) -> Unit): Boolean {
        if (!FrSettings.useNuvio) return false
        lastResults.clear()
        syncSemaphore()

        val tmdbId = tmdbId(payload) ?: return false
        val all = scrapers()
        if (all.isEmpty()) return false

        val mediaType = if (payload.isSeries) "tv" else "movie"
        val season = if (payload.isSeries) (payload.season ?: 1) else 0
        val episode = if (payload.isSeries) (payload.episode ?: 1) else 0

        return coroutineScope {
            all.map { scraper ->
                async {
                    runCatching {
                        semaphore.withPermit {
                            withTimeoutOrNull(SCRAPER_TIMEOUT_MS) {
                                runScraper(scraper, tmdbId, mediaType, season, episode, payload, callback)
                            } ?: false
                        }
                    }.getOrDefault(false)
                }
            }.awaitAll().any { it }
        }
    }

    // ------------------------------------------- compatibilité Android (dex)

    /** Dernier incident d'initialisation du moteur (visible dans les réglages). */
    @Volatile
    private var engineError: String? = null

    /** Bandeau de diagnostic : « OK » ou la raison exacte du dernier échec. */
    fun engineStatus(): String {
        val err = engineError
        if (err != null) return "✗ moteur : $err"
        return runCatching {
            val cx = RhinoContext.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = RhinoContext.VERSION_ES6
                val scope = cx.initStandardObjects(com.frunified.rhino.TopLevel())
                installRuntime(cx, scope)
                val probe = cx.evaluateString(
                    scope,
                    "(typeof RegExp === 'function') && /^a(b+)c$/.test('abbbc') && " +
                        "(function(){ try { null.x; return false; } catch (e) { return String(e).length > 0; } })()",
                    "selftest", 1, null
                )
                val ok = RhinoContext.toBoolean(probe)
                if (ok) "✓ moteur Rhino opérationnel (RegExp + messages)"
                else "✗ auto-test JS négatif"
            } finally {
                RhinoContext.exit()
            }
        }.getOrElse { t -> "✗ moteur : " + (t.message?.take(120) ?: t::class.simpleName.orEmpty()) }
    }

    /**
     * Rhino récupère deux choses par `ServiceLoader` / `ResourceBundle`, c'est-à-dire
     * par des **fichiers de ressources** (`META-INF/services/com.frunified.rhino.RegExpLoader`
     * et `com/frunified/rhino/resources/Messages.properties`). Un plugin CloudStream
     * ne contient qu'un `classes.dex` : ces fichiers n'existent pas sur l'appareil.
     *
     * Conséquences avant ce correctif (invisibles sur JVM où le jar complet est au
     * classpath, donc jamais détectées par les bancs d'essai) :
     *  • aucun `RegExpLoader` → `RegExp` absent et **chaque littéral `/…/` fait
     *    échouer la compilation** du script (`checkRegExpProxy`) ;
     *  • aucun bundle de messages → l'erreur elle-même devient une
     *    `MissingResourceException` (« ✗ interne: can't find bundle for base name … »),
     *    ce qui masquait la vraie cause et tuait **tous** les scrapeurs.
     *
     * On rétablit les deux à la main : proxy + constructeur `RegExp` enregistrés
     * dans le contexte, et bundle de messages fourni par une classe compilée
     * (`com.frunified.rhino.resources.Messages`, déxée avec le plugin).
     */
    private fun installRuntime(cx: RhinoContext, scope: Scriptable) {
        val scopeObj = scope as? com.frunified.rhino.ScriptableObject
        if (scopeObj == null) {
            engineError = "scope Rhino inattendu"
            return
        }
        val proxyResult = runCatching {
            com.frunified.rhino.ScriptRuntime.setRegExpProxy(
                cx,
                com.frunified.rhino.regexp.RegExpLoaderImpl().newProxy()
            )
        }
        val registerResult = runCatching {
            // registerRegExp(Context, ScriptableObject, boolean) est privé : on le
            // retrouve par son nom (plus robuste qu'une signature exacte).
            val register = com.frunified.rhino.ScriptRuntime::class.java.declaredMethods
                .firstOrNull { it.name == "registerRegExp" }
                ?: error("registerRegExp absent")
            register.isAccessible = true
            register.invoke(null, cx, scopeObj, false)
        }
        val messagesResult = runCatching {
            java.util.ResourceBundle.getBundle(
                "com.frunified.rhino.resources.Messages",
                java.util.Locale.getDefault(),
                com.frunified.rhino.ScriptRuntime::class.java.classLoader
            ).getString("msg.dup.parms")
        }
        val failure = proxyResult.exceptionOrNull() ?: registerResult.exceptionOrNull()
            ?: messagesResult.exceptionOrNull()
        engineError = when {
            failure == null -> null
            proxyResult.isFailure -> "proxy RegExp : " + (failure.message?.take(90) ?: failure::class.simpleName.orEmpty())
            registerResult.isFailure -> "constructeur RegExp : " + (failure.message?.take(90) ?: failure::class.simpleName.orEmpty())
            else -> "messages Rhino : " + (failure.message?.take(90) ?: failure::class.simpleName.orEmpty())
        }
    }

    /** Exécute UN scrapeur dans un interpréteur Rhino isolé. */
    private suspend fun runScraper(
        scraper: NuvioScraper,
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int,
        payload: PlayPayload,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        currentScraper.set(scraper.id)
        fetchLog[scraper.id] = mutableListOf()
        consoleLog[scraper.id] = emptyList()
        val code = try {
            script(scraper)
        } catch (t: Throwable) {
            lastResults[scraper.id] = "✗ script: " + (t.message?.take(80) ?: t::class.simpleName.orEmpty())
            currentScraper.remove()
            return@withContext false
        }
        if (code == null) {
            lastResults[scraper.id] = "✗ script introuvable"
            currentScraper.remove()
            return@withContext false
        }

        // Rhino est récursif : parser un bundle esbuild minifié dépasse la pile
        // de 1 Mo d'un thread Android. On lui donne 32 Mo (cf. withBigStack).
        val raw: List<RawStream>? = try {
            withBigStack(scraper.id) {
            currentScraper.set(scraper.id)
            val cx = try {
                RhinoContext.enter()
            } catch (t: Throwable) {
                lastResults[scraper.id] = "✗ moteur: " + (t.message?.take(80) ?: t::class.simpleName.orEmpty())
                currentScraper.remove()
                return@withBigStack null
            }
            var scopeRef: Scriptable? = null
            try {
                cx.optimizationLevel = -1   // interprété : compatible ART/Android
                cx.languageVersion = RhinoContext.VERSION_ES6

                // IMPORTANT : TopLevel() active le cache des builtins (cacheBuiltins) :
                // sans lui, le prototype des fonctions génératrices n'est pas initialisé
                // et les bundles transpilés (babel) échouent en « Cannot find function apply ».
                val scope = cx.initStandardObjects(com.frunified.rhino.TopLevel())
                scopeRef = scope
                // Le dex Android ne transporte ni META-INF/services ni .properties :
                // sans cela RegExp et les messages Rhino sont introuvables (cf. installRuntime).
                installRuntime(cx, scope)
                cx.evaluateString(scope, JS_ENV, "prelude", 1, null)
                injectEnv(scope)

                // module.exports / global.getStreams : les deux formats de sortie
                val module = cx.newObject(scope)
                val exports = cx.newObject(scope)
                module.put("exports", module, exports)
                scope.put("module", scope, module)
                scope.put("exports", scope, exports)

                // fetch, b64 et URL sont fournis par Kotlin
                scope.put("fetch", scope, FetchFunction())
                scope.put("__b64Encode", scope, B64Function(encode = true))
                scope.put("__b64Decode", scope, B64Function(encode = false))
                // Permet aux timers du bundle (limiteurs de débit, retry…) d'attendre
                // réellement au lieu de tourner à vide dans la boucle d'événements.
                scope.put("__sleep", scope, SleepFunction())

                try {
                    cx.evaluateString(scope, code, scraper.id, 1, null)
                } catch (t: Throwable) {
                    lastResults[scraper.id] = "✗ erreur JS : " + jsError(code, t) + diagSuffix(scraper.id)
                    return@withBigStack null
                }

                // Récupère getStreams : module.exports.getStreams OU global.getStreams
                var fn: Any? = null
                runCatching {
                    val exported = (module.get("exports", module) as? Scriptable) ?: scope
                    fn = exported.get("getStreams", exported)
                }
                if (fn == null || fn == Scriptable.NOT_FOUND) {
                    runCatching { fn = scope.get("getStreams", scope) }
                }
                if (fn == null || fn == Scriptable.NOT_FOUND || fn !is com.frunified.rhino.Callable) {
                    lastResults[scraper.id] = "✗ getStreams introuvable"
                    return@withBigStack null
                }

                val args = arrayOf<Any?>(
                    cx.evaluateString(scope, tmdbId.toString(), "n", 1, null),
                    cx.evaluateString(scope, JSONObject.quote(mediaType), "s", 1, null),
                    cx.evaluateString(scope, season.toString(), "n", 1, null),
                    cx.evaluateString(scope, episode.toString(), "n", 1, null)
                )

                var result: Any? = try {
                    (fn as com.frunified.rhino.Callable).call(cx, scope, scope, args)
                } catch (t: Throwable) {
                    lastResults[scraper.id] = "✗ appel : " + jsError(code, t) + diagSuffix(scraper.id)
                    return@withBigStack null
                }

                // Fait tourner la boucle d'événements JS (micro-tâches + timers)
                // jusqu'à épuisement, dans la limite du budget du scrapeur.
                val budget = (SCRAPER_TIMEOUT_MS - 5_000L).coerceAtLeast(15_000L)
                val deadline = System.currentTimeMillis() + budget
                var guard = 0
                while (timerCount(cx, scope) > 0 && guard++ < 200 &&
                    System.currentTimeMillis() < deadline
                ) {
                    drain(cx, scope, deadline - System.currentTimeMillis())
                }

                // Si le résultat est notre promesse, on lit sa valeur
                val resultObj = result as? Scriptable
                if (resultObj != null && runCatching {
                        ScriptableObject.hasProperty(resultObj, "__settled")
                    }.getOrDefault(false)) {
                    if (ScriptableObject.getProperty(resultObj, "__rejected") == java.lang.Boolean.TRUE) {
                        val rej = ScriptableObject.getProperty(resultObj, "__value")
                        lastResults[scraper.id] =
                            "✗ rejet : " + (rej?.toString()?.take(80) ?: "inconnu") + diagSuffix(scraper.id)
                        return@withBigStack null
                    }
                    result = ScriptableObject.getProperty(resultObj, "__value")
                }

                val streams = asArray(cx, scope, result)
                if (streams == null) {
                    lastResults[scraper.id] = "✗ résultat non reconnu" + diagSuffix(scraper.id)
                    return@withBigStack null
                }
                val maxHere = FrSettings.nuvioMaxPerScraper
                val out = mutableListOf<RawStream>()

                for (i in 0 until runCatching { RhinoContext.toNumber(ScriptableObject.getProperty(streams, "length")).toInt() }.getOrDefault(0)) {
                    if (maxHere > 0 && out.size >= maxHere) break
                    val obj = ScriptableObject.getProperty(streams, i) as? Scriptable ?: continue
                    out += readStream(obj) ?: continue
                }
                out
            } catch (t: Throwable) {
                // un scrapeur qui plante ne doit jamais faire planter la lecture
                lastResults[scraper.id] = "✗ interne: " + (t.message?.take(80) ?: t::class.simpleName.orEmpty()) + diagSuffix(scraper.id)
                null
            } finally {
                captureConsole(scopeRef, scraper.id)
                RhinoContext.exit()
                currentScraper.remove()
            }
            }
        } catch (t: Throwable) {
            val why = if (t is StackOverflowError) {
                "pile insuffisante (bundle trop imbriqué)"
            } else (t.message?.take(80) ?: t::class.simpleName.orEmpty())
            lastResults[scraper.id] = "✗ interne: " + why + diagSuffix(scraper.id)
            currentScraper.remove()
            return@withContext false
        }

        if (raw == null) return@withContext false

        // Construction des ExtractorLink HORS du thread JS (fonctions suspend).
        var count = 0
        raw.forEach { item ->
            toLink(scraper.name, payload, item)?.let { count++; callback(it) }
        }
        lastResults[scraper.id] = if (count > 0) "✓ $count lien(s)" else "✓ 0 lien"
        currentScraper.remove()
        count > 0
    }

    /** Succinct : dernières requêtes + dernière ligne console JS (pour autopsier un échec). */
    private fun diagSuffix(id: String): String {
        val fetches = fetchLog[id].orEmpty().takeLast(6)
        val console = consoleLog[id].orEmpty().takeLast(2)
        val parts = mutableListOf<String>()
        if (fetches.isNotEmpty()) parts += fetches.joinToString("; ")
        if (console.isNotEmpty()) parts += "js: " + console.joinToString(" | ").take(110)
        return if (parts.isEmpty()) "" else " | " + parts.joinToString(" | ").take(260)
    }

    private fun captureConsole(scope: Scriptable?, id: String) {
        if (scope == null) return
        runCatching {
            val arr = scope.get("__console_lines", scope) as? Scriptable ?: return
            val n = runCatching { RhinoContext.toNumber(ScriptableObject.getProperty(arr, "length")).toInt() }.getOrDefault(0)
            if (n > 0) consoleLog[id] = (0 until n).map { RhinoContext.toString(ScriptableObject.getProperty(arr, it)) }
        }
    }

    private fun callJs(cx: RhinoContext, scope: Scriptable, name: String, args: Array<Any?>): Any? =
        runCatching {
            (scope.get(name, scope) as? com.frunified.rhino.Callable)?.call(cx, scope, scope, args)
        }.getOrNull()

    private fun drain(cx: RhinoContext, scope: Scriptable, budgetMs: Long = 30_000L) {
        callJs(cx, scope, "__drain", arrayOf<Any?>(budgetMs.coerceAtLeast(0L).toDouble()))
    }

    private fun timerCount(cx: RhinoContext, scope: Scriptable): Int =
        runCatching { RhinoContext.toNumber(callJs(cx, scope, "__timerCount", emptyArray())).toInt() }
            .getOrDefault(0)

    private fun asArray(cx: RhinoContext, scope: Scriptable, value: Any?): Scriptable? {
        if (value is NativeArray) return value
        if (value is com.frunified.rhino.NativeJavaObject) {
            val unwrapped = runCatching { value.unwrap() }.getOrNull()
            if (unwrapped is List<*>) {
                val arr = cx.newArray(scope, unwrapped.size)
                unwrapped.forEachIndexed { i, item -> arr.put(i, arr, RhinoContext.javaToJS(item, scope)) }
                return arr
            }
        }
        return null
    }

    // --------------------------------------------------- conversion flux

    /**
     * Flux lu depuis le JS, en données **purement Kotlin**.
     *
     * Indispensable : les objets Rhino ne doivent pas franchir la frontière du
     * thread JS (le Context est lié au thread, et `newExtractorLink` est une
     * fonction `suspend` qu'on ne peut pas appeler dedans). On extrait donc
     * tout ce dont on a besoin pendant que le scope est vivant.
     */
    private data class RawStream(
        val url: String?,
        val infoHash: String?,
        val label: String,
        val language: String?,
        val headers: Map<String, String>?
    )

    /** Lit un objet flux JS (appelé DANS le thread Rhino). */
    private fun readStream(obj: Scriptable): RawStream? {
        fun prop(vararg names: String): String? {
            for (name in names) {
                val v = runCatching { ScriptableObject.getProperty(obj, name) }.getOrNull() ?: continue
                if (v == Scriptable.NOT_FOUND) continue
                val s = when (v) {
                    is String -> v
                    is Double -> if (v.isNaN()) null else v.toLong().toString()
                    is Number -> v.toString()
                    else -> v.toString()
                } ?: continue
                if (s.isNotBlank() && s != "null" && s != "undefined") return s
            }
            return null
        }

        val url = prop("url", "file", "videoUrl", "src")?.takeIf { it.startsWith("http") }
        val infoHash = prop("infoHash")?.takeIf { Regex("^[a-fA-F0-9]{40}$").matches(it) }
        if (url == null && infoHash == null) return null

        val headers = runCatching {
            val h = ScriptableObject.getProperty(obj, "headers")
            if (h !is Scriptable) null else {
                val map = linkedMapOf<String, String>()
                for (id in h.ids) {
                    if (id is String) {
                        val value = ScriptableObject.getProperty(h, id)?.toString()
                        if (!value.isNullOrBlank()) map[id] = value
                    }
                }
                map
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }

        return RawStream(
            url = url,
            infoHash = infoHash,
            label = prop("name", "title", "fileName", "label", "description").orEmpty(),
            language = prop("language", "lang")?.uppercase(),
            headers = headers
        )
    }

    /** Construit l'ExtractorLink (hors thread JS : `newExtractorLink` est suspend). */
    private suspend fun toLink(
        scraperName: String,
        payload: PlayPayload,
        raw: RawStream
    ): ExtractorLink? {
        val label = raw.label.ifBlank { scraperName }
        val quality = qualityOf("$label ${raw.language.orEmpty()}")

        return when {
            raw.url != null -> runCatching {
                newExtractorLink("Nuvio · $scraperName", "$scraperName • $label", raw.url) {
                    this.quality = quality
                    raw.headers?.let { this.headers = it }
                }
            }.getOrNull()

            raw.infoHash != null -> runCatching {
                val magnet = "magnet:?xt=urn:btih:${raw.infoHash}" +
                    "&dn=${payload.primaryTitle.replace(" ", "+")}" +
                    TRACKERS.joinToString("") { "&tr=$it" }
                newExtractorLink(
                    "Nuvio · $scraperName",
                    "Torrent • $label",
                    magnet,
                    ExtractorLinkType.MAGNET
                ) {
                    this.quality = quality
                }
            }.getOrNull()

            else -> null
        }
    }

    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce"
    )

    private fun qualityOf(text: String): Int = when {
        text.contains("2160", true) || text.contains("4k", true) -> Qualities.P2160.value
        text.contains("1440", true) -> Qualities.P1440.value
        text.contains("1080", true) -> Qualities.P1080.value
        text.contains("720", true) -> Qualities.P720.value
        text.contains("480", true) -> Qualities.P480.value
        text.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    // ------------------------------------------------- ID TMDB (animé)

    /**
     * TMDB est nécessaire pour les scrapeurs Nuvio (métadonnées/alias des titres).
     * Retrouvé par recherche si absent — on essaie TOUS les titres connus de la
     * fiche (titres FR, originaux, romaji, alternatifs) car les fiches
     * AniList/MAL (non-TMDB) n'ont pas d'id TMDB : c'est ce qui permet aux
     * scrapeurs anime de recevoir un id exploitable.
     */
    private suspend fun tmdbId(payload: PlayPayload): Int? {
        payload.tmdbId?.let { return it }
        val now = System.currentTimeMillis()
        var best: Pair<Double, Int>? = null
        for (title in payload.titles.take(6)) {
            val key = "$title|${payload.year}"
            val cachedHit = tmdbCache[key]
            if (cachedHit != null && cachedHit.first > now) {
                cachedHit.second?.let { return it }
                continue
            }
            val found = runCatching {
                TmdbCatalog.searchBest(title, payload.year)?.let { item ->
                    val id = item.id.id.toIntOrNull()
                    if (id != null) {
                        val score = TitleMatch.score(payload.titles, item.title, payload.year, item.year)
                        score to id
                    } else null
                }
            }.getOrNull()
            tmdbCache[key] = (now + SCRIPT_TTL_MS) to found?.second
            if (found != null && (best == null || found.first > best!!.first)) best = found
        }
        return best?.second
    }

    /** Format d'erreur autoportant : classe + message + ligne fautive + extrait du code. */
    private fun jsError(code: String, t: Throwable): String {
        val cls = t::class.simpleName.orEmpty()
        val msg = (t.message ?: "").replace("\n", " ").take(90)
        val line = (t as? com.frunified.rhino.RhinoException)?.lineNumber() ?: -1
        val snippet = if (line > 0) {
            code.lineSequence().elementAtOrNull(line - 1)?.trim()?.take(110)
                ?.let { " | l${line}: $it" }.orEmpty()
        } else ""
        return "$cls: $msg$snippet"
    }

    // ------------------------------------------------------- réseau

    private fun httpGet(url: String, extraHeaders: Map<String, String>): String =
        doHttp(url, "GET", extraHeaders, null).second

    private fun doHttp(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?
    ): Pair<Int, String> {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            return 0 to ""
        }
        try {
            conn.requestMethod = method.uppercase()
            conn.connectTimeout = NETWORK_TIMEOUT_MS
            conn.readTimeout = 90_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            conn.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> runCatching { conn.setRequestProperty(k, v) } }

            if (body != null) {
                conn.doOutput = true
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } catch (t: Throwable) {
            return 0 to ""
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ------------------------------------------------------ fonctions JS

    /** Injecte les clés API de l'utilisateur dans process.env (lu par les bundles). */
    private fun injectEnv(scope: Scriptable) {
        runCatching {
            val process = scope.get("process", scope) as? Scriptable ?: return
            val env = ScriptableObject.getProperty(process, "env") as? Scriptable ?: return
            FrSettings.apiTokens.forEach { (k, v) ->
                env.put(k, env, v)
            }
        }
    }

    /**
     * Test rapide d'UN scrapeur (bouton « Tester » des réglages).
     * Le contenu de test est adapté au type du scrapeur : les sites d'ANIMÉS
     * (Anime-Sama, Neko-Sama, VostFree…) ne connaissent pas « Fight Club » —
     * il faut un titre d'anime (One Piece, TMDB 37854) pour tester leur chemin
     * réel. Les sites movies/tv sont testés sur Fight Club (TMDB 550).
     */
    suspend fun testProvider(id: String): String {
        val scraper = runCatching { scrapers().firstOrNull { it.id == id } }.getOrNull()
            ?: return "✗ source introuvable"
        val types = scraper.supportedTypes.map { it.lowercase() }
        val animeLike = types.any { it.contains("anime") || it.contains("cartoon") } ||
            types.isEmpty() || scraper.name.lowercase().contains("anime") ||
            scraper.name.lowercase().contains("sama") || scraper.name.lowercase().contains("vost") ||
            scraper.name.lowercase().contains("manga")
        val wantBoth = types.any { it.contains("anime") } && types.any { it == "movie" || it == "tv" || it == "series" }

        val cases = buildList {
            if (!animeLike || wantBoth) {
                add(TestCase("film", 550, "movie", 0, 0,
                    PlayPayload(kind = "movie", titles = listOf("Fight Club"), year = 1999, tmdbId = 550)))
            }
            if (animeLike) {
                add(TestCase("anime", 37854, "tv", 1, 1,
                    PlayPayload(kind = "tv", titles = listOf("One Piece"), year = 1999,
                        tmdbId = 37854, malId = 21, anilistId = 21, season = 1, episode = 1)))
            }
        }
        val out = StringBuilder()
        var anyOk = false
        for (tc in cases) {
            val links = java.util.concurrent.CopyOnWriteArrayList<ExtractorLink>()
            // Les tests partent TOUS en parallèle depuis l'écran de réglages ;
            // sur un réseau mobile, 26 moteurs Rhino simultanés saturaient tout
            // et chaque scrapeur dépassait son timeout. Sémaphore dédié (2 max) :
            // tests doux ET jamais bloquants pour la lecture réelle.
            val ok = runCatching {
                testSemaphore.withPermit {
                    withTimeoutOrNull(150_000L) {
                        runScraper(scraper, tc.tmdbId, tc.type, tc.season, tc.episode, tc.payload) { links += it }
                    } ?: false
                }
            }.getOrDefault(false)
            if (ok) {
                anyOk = true
                out.append("${tc.label}: ${links.size} lien(s)")
            } else {
                val diag = diagnostics()[scraper.id] ?: "aucun résultat (timeout ?)"
                out.append(if (diag.startsWith("✓")) "${tc.label}: 0 lien" else "${tc.label}: ✗ $diag")
            }
        }
        val txt = out.toString()
        return if (anyOk) "✓ " + txt.replace(Regex(": ✓ |: ✗ "), " · ") else txt
    }

    private data class TestCase(
        val label: String,
        val tmdbId: Int,
        val type: String,
        val season: Int,
        val episode: Int,
        val payload: PlayPayload
    )

    private class FetchFunction : BaseFunction() {
        @Suppress("DEPRECATION")
        override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<Any>): Any? {
            val url = args.getOrNull(0)?.toString()?.takeIf { it.startsWith("http") }
            if (url == null) return makeResponse(cx, scope, 0, "Invalid URL")

            val opts = args.getOrNull(1) as? Scriptable
            val method = (runCatching {
                ScriptableObject.getProperty(opts, "method") as? String
            }.getOrNull())?.uppercase() ?: "GET"

            val headerMap = linkedMapOf<String, String>()
            // En-têtes « contournement Cloudflare » par défaut (réglages ⚙️),
            // les en-têtes fournis par le bundle priment.
            runCatching {
                if (FrSettings.nuvioUserAgent.isNotBlank()) headerMap["User-Agent"] = FrSettings.nuvioUserAgent
                if (FrSettings.nuvioReferer.isNotBlank()) headerMap["Referer"] = FrSettings.nuvioReferer
                if (FrSettings.nuvioCookies.isNotBlank()) headerMap["Cookie"] = FrSettings.nuvioCookies
            }
            runCatching {
                val headersObj = ScriptableObject.getProperty(opts, "headers")
                if (headersObj is Scriptable) {
                    for (id in headersObj.ids) {
                        if (id is String) {
                            val value = ScriptableObject.getProperty(headersObj, id)?.toString()
                            if (!value.isNullOrBlank()) headerMap[id] = value
                        }
                    }
                }
            }

            val body = runCatching {
                ScriptableObject.getProperty(opts, "body")?.toString()?.takeIf { it.isNotEmpty() }
            }.getOrNull()

            val t0 = System.currentTimeMillis()
            val (status, text) = doHttp(url, method, headerMap, body)
            val ms = System.currentTimeMillis() - t0
            currentScraper.get()?.let { id ->
                runCatching {
                    val host = runCatching { URL(url).host }.getOrDefault("?")
                    val list = fetchLog.computeIfAbsent(id) { mutableListOf() }
                    list.add("$method $host → $status (${ms}ms)")
                    while (list.size > 40) list.removeAt(0)
                }
            }
            return makeResponse(cx, scope, status, text)
        }

        private fun makeResponse(cx: RhinoContext, scope: Scriptable, status: Int, text: String): Any? =
            runCatching {
                ScriptableObject.callMethod(scope, "__makeResponse", arrayOf<Any?>(status, text))
            }.getOrNull()
                // Undefined (et non Scriptable.NOT_FOUND, objet Java qui déclenche
                // l'avertissement « missed Context.javaToJS() » à chaque test de vérité).
                ?: com.frunified.rhino.Undefined.instance
    }

    /** `__sleep(ms)` : pause bornée, utilisée par la boucle d'événements JS. */
    private class SleepFunction : BaseFunction() {
        @Suppress("DEPRECATION")
        override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<Any>): Any? {
            val ms = runCatching { RhinoContext.toNumber(args.getOrNull(0)).toLong() }.getOrDefault(0L)
            if (ms > 0) runCatching { Thread.sleep(ms.coerceAtMost(500L)) }
            return com.frunified.rhino.Undefined.instance
        }
    }

    private class B64Function(private val encode: Boolean) : BaseFunction() {
        @Suppress("DEPRECATION")
        override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<Any>): Any? {
            val input = args.getOrNull(0)?.toString() ?: return ""
            return if (encode) {
                android.util.Base64.encodeToString(input.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            } else {
                runCatching {
                    String(android.util.Base64.decode(input, android.util.Base64.DEFAULT), Charsets.UTF_8)
                }.getOrDefault("")
            }
        }
    }

    // ------------------------------------------------------- préambule JS

    private val JS_ENV = """
var __console_lines = [];
var console = {
  log:function(x){ try { __console_lines.push(String(x)); if (__console_lines.length > 25) __console_lines.shift(); } catch (e) {} },
  warn:function(x){ try { __console_lines.push('[warn] ' + String(x)); if (__console_lines.length > 25) __console_lines.shift(); } catch (e) {} },
  error:function(x){ try { __console_lines.push('[error] ' + String(x)); if (__console_lines.length > 25) __console_lines.shift(); } catch (e) {} },
  debug:function(x){}
};
var global = this;
var window = this;
var self = this;
var globalThis = this;
var process = { env: {} };
var navigator = { userAgent: 'Mozilla/5.0 (Android)' };
var location = { href: 'https://frunified.fr/', protocol: 'https:', hostname: 'frunified.fr', origin: 'https://frunified.fr' };
// ----- Boucle d'événements : file de micro-tâches (promesses) + timers datés.
// Les bundles Nuvio « attendent » en enchaînant Promise.resolve().then(self)
// jusqu'à ce que Date.now() dépasse l'échéance (limiteur de débit `delay()`).
// Avec des promesses purement synchrones, cet enchaînement ré-entrait dans
// lui-même → récursion de plusieurs millions de niveaux → StackOverflowError
// sur la pile (courte) d'Android, donc « 0 lien » pour TOUS les scrapeurs.
// Ici les callbacks sont mis en file et exécutés par une BOUCLE (trampoline).
var __micro = [];
var __timers = [];
var __timerSeq = 0;
function __enqueue(fn) { __micro.push(fn); }
function setTimeout(fn, ms) {
  var id = ++__timerSeq;
  __timers.push({ id: id, fn: fn, due: Date.now() + (Number(ms) || 0) });
  return id;
}
function clearTimeout(id) {
  for (var i = 0; i < __timers.length; i++) {
    if (__timers[i].id === id) { __timers.splice(i, 1); return; }
  }
}
// setInterval déclenché une seule fois : un vrai intervalle ne finirait jamais.
function setInterval(fn, ms) { return setTimeout(fn, ms); }
function clearInterval(id) { clearTimeout(id); }

function __runMicro() {
  var n = 0;
  while (__micro.length && n++ < 5000000) {
    var f = __micro.shift();
    try { f(); } catch (e) {}
  }
}

/**
 * Fait tourner la boucle jusqu'à épuisement, dans la limite d'un budget (ms).
 * Les timers dont l'échéance dépasse le budget (timeouts de 45 s des bundles,
 * AbortSignal.timeout…) sont abandonnés au lieu de bloquer.
 */
function __drain(budget) {
  var end = Date.now() + (Number(budget) || 30000);
  var guard = 0;
  while (guard++ < 1000000) {
    __runMicro();
    if (!__timers.length) return;
    if (Date.now() > end) return;
    var idx = 0;
    for (var i = 1; i < __timers.length; i++) {
      if (__timers[i].due < __timers[idx].due) idx = i;
    }
    var t = __timers[idx];
    var wait = t.due - Date.now();
    if (wait > 0) {
      if (t.due > end) { __timers.splice(idx, 1); continue; }
      if (typeof __sleep === 'function') __sleep(wait > 200 ? 200 : wait);
      if (Date.now() < t.due) continue;
    }
    __timers.splice(idx, 1);
    try { t.fn(); } catch (e) {}
  }
}
function __timerCount() { return __timers.length + __micro.length; }

// ----- Polyfill du patch spread du moteur Rhino embarqué :
// f(...a) -> f.apply(this, __spread(a)) ; [a, ...b] -> __spread([a], b)
function __spread() {
  var a = [];
  for (var i = 0; i < arguments.length; i++) {
    var v = arguments[i];
    if (Array.isArray(v)) { for (var j = 0; j < v.length; j++) a.push(v[j]); }
    else a.push(v);
  }
  return a;
}

// ----- Promise minimale (chaînes .then synchrones + helpers babel asyncToGenerator)
function Promise(executor) {
  var self = this;
  self.__callbacks = []; self.__value = undefined; self.__settled = false; self.__rejected = false;
  function resolve(v) {
    if (self.__settled) return;
    if (v && typeof v.then === 'function') { try { v.then(resolve, reject); } catch (e) { reject(e); } return; }
    self.__settled = true; self.__value = v; self.__run();
  }
  function reject(e) { if (self.__settled) return; self.__settled = true; self.__rejected = true; self.__value = e; self.__run(); }
  self.then = function (onF, onR) {
    return new Promise(function (res, rej) {
      self.__callbacks.push({ f: onF, r: onR, res: res, rej: rej });
      self.__run();
    });
  };
  self.catch = function (onR) { return self.then(undefined, onR); };
  self.finally = function (onF) {
    return self.then(function (v) { if (onF) onF(); return v; }, function (e) { if (onF) onF(); throw e; });
  };
  // Les callbacks sont mis en file de micro-tâches (et NON exécutés en place) :
  // c'est ce qui empêche `Promise.resolve().then(boucle)` de récurser sur la
  // pile jusqu'au StackOverflowError.
  self.__run = function () {
    if (!self.__settled) return;
    var cbs = self.__callbacks; self.__callbacks = [];
    for (var i = 0; i < cbs.length; i++) {
      (function (cb) {
        __enqueue(function () {
          try {
            if (self.__rejected) {
              if (cb.r) { cb.res(cb.r(self.__value)); } else { cb.rej(self.__value); }
            } else {
              if (cb.f) { cb.res(cb.f(self.__value)); } else { cb.res(self.__value); }
            }
          } catch (e) { cb.rej(e); }
        });
      })(cbs[i]);
    }
  };
  try { executor(resolve, reject); } catch (e) { reject(e); }
}
Promise.resolve = function (v) { return new Promise(function (res) { res(v); }); };
Promise.reject = function (e) { return new Promise(function (_, rej) { rej(e); }); };
Promise.all = function (arr) {
  return new Promise(function (res, rej) {
    var out = [], n = (arr && arr.length) || 0, c = 0;
    if (!n) { res(out); return; }
    for (var i = 0; i < n; i++) {
      (function (i) { try { Promise.resolve(arr[i]).then(function (v) { out[i] = v; if (++c === n) res(out); }, rej); } catch (e) { rej(e); } })(i);
    }
  });
};
Promise.race = function (arr) {
  return new Promise(function (res, rej) {
    for (var i = 0; i < ((arr && arr.length) || 0); i++) { Promise.resolve(arr[i]).then(res, rej); }
  });
};
Promise.allSettled = function (arr) {
  arr = arr || [];
  return new Promise(function (res) {
    var out = [], n = arr.length, c = 0;
    if (!n) { res(out); return; }
    function done(i, v, settled) {
      out[i] = settled ? { status: 'fulfilled', value: v } : { status: 'rejected', reason: v };
      if (++c === n) res(out);
    }
    for (var i = 0; i < n; i++) {
      (function (i) {
        try { Promise.resolve(arr[i]).then(function (v) { done(i, v, true); }, function (e) { done(i, e, false); }); }
        catch (e) { done(i, e, false); }
      })(i);
    }
  });
};
Promise.any = function (arr) {
  arr = arr || [];
  return new Promise(function (res, rej) {
    var errors = [], c = 0;
    if (!arr.length) { rej(new Error('All promises were rejected')); return; }
    for (var i = 0; i < arr.length; i++) {
      (function (i) {
        try { Promise.resolve(arr[i]).then(res, function (e) { errors[i] = e; if (++c === arr.length) rej(new Error('All promises were rejected')); }); }
        catch (e) { errors[i] = e; if (++c === arr.length) rej(new Error('All promises were rejected')); }
      })(i);
    }
  });
};

// ----- String.prototype.matchAll (retourne un vrai tableau : spreadable et .map/.concat utilisables)
if (!String.prototype.matchAll) {
  String.prototype.matchAll = function (re) {
    var str = String(this);
    var flags = '';
    if (re) {
      if (re.ignoreCase) flags += 'i';
      if (re.multiline) flags += 'm';
      if (re.dotAll) flags += 's';
      if (re.unicode) flags += 'u';
      if (re.sticky) flags += 'y';
    }
    var rx = (re && re.global) ? re : new RegExp(re ? re.source : String(re), 'g' + flags);
    if (!rx.global && !rx.sticky) rx = new RegExp(rx.source, flags + 'g');
    rx.lastIndex = 0;
    var out = [];
    var m;
    while ((m = rx.exec(str)) !== null) {
      out.push(m);
      if (m.index === rx.lastIndex) rx.lastIndex++;
    }
    // supporte aussi l'usage en itérateur : it.next()
    var idx = 0;
    out.next = function () {
      if (idx < out.length) return { value: out[idx++], done: false };
      return { value: undefined, done: true };
    };
    return out;
  };
}

// ----- Array.prototype.flatMap / flat (niveau 1, usage bundlé courant)
if (!Array.prototype.flatMap) {
  Array.prototype.flatMap = function (fn, thisArg) {
    var out = [];
    for (var i = 0; i < this.length; i++) {
      if (!(i in this)) continue;
      var v = fn.call(thisArg, this[i], i, this);
      if (Array.isArray(v)) { for (var j = 0; j < v.length; j++) out.push(v[j]); }
      else out.push(v);
    }
    return out;
  };
}
if (!Array.prototype.flat) {
  Array.prototype.flat = function (depth) {
    var d = depth == null ? 1 : depth;
    var out = [];
    for (var i = 0; i < this.length; i++) {
      var v = this[i];
      if (Array.isArray(v) && d > 0) {
        var sub = d === Infinity ? v.flat(Infinity) : v.flat(d - 1);
        for (var j = 0; j < sub.length; j++) out.push(sub[j]);
      } else out.push(v);
    }
    return out;
  };
}

// ----- Réponse fetch
function __makeResponse(status, text) {
  return {
    ok: status >= 200 && status < 300,
    status: status,
    statusText: '',
    url: '',
    headers: { get: function () { return null; } },
    text: function () { return Promise.resolve(String(text == null ? '' : text)); },
    json: function () { return Promise.resolve(JSON.parse(String(text == null ? '' : text))); },
    arrayBuffer: function () { return Promise.resolve({}); }
  };
}

// ----- Base64
var __B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function __b64EncodeLocal(input) {
  var output = '';
  for (var i = 0; i < input.length;) {
    var c1 = input.charCodeAt(i++) & 0xff;
    var c2 = i < input.length ? input.charCodeAt(i++) & 0xff : NaN;
    var c3 = i < input.length ? input.charCodeAt(i++) & 0xff : NaN;
    var e1 = c1 >> 2;
    var e2 = ((c1 & 3) << 4) | (isNaN(c2) ? 0 : (c2 >> 4));
    var e3 = isNaN(c2) ? 64 : (((c2 & 15) << 2) | (isNaN(c3) ? 0 : (c3 >> 6)));
    var e4 = isNaN(c3) ? 64 : (c3 & 63);
    output += __B64.charAt(e1) + __B64.charAt(e2) + (e3 === 64 ? '=' : __B64.charAt(e3)) + (e4 === 64 ? '=' : __B64.charAt(e4));
  }
  return output;
}
function atob(s) { return __b64Decode(String(s)); }
function btoa(s) { return __b64Encode(String(s)); }

// ----- Buffer utilitaire
function Buffer(data, enc) { this._d = data; this._enc = enc; }
Buffer.from = function (data, enc) {
  var b = { _d: data, _enc: enc };
  b.toString = function (e) {
    var s = String(this._d);
    if (this._enc === 'base64' || e === 'utf8') return __b64Decode(s);
    return s;
  };
  return b;
};
Buffer.isBuffer = function () { return false; };
Buffer.alloc = function (n) { return { toString: function () { return ''; } }; };

// ----- URL
function URL(input, base) {
  var parts = __parseUrl(String(input), base);
  this.href = parts[0]; this.protocol = parts[1]; this.host = parts[2];
  this.hostname = parts[2].split(':')[0]; this.port = parts[2].split(':')[1] || '';
  this.origin = parts[1] + '//' + parts[2]; this.pathname = parts[3];
  this.search = parts[4]; this.hash = parts[5];
  this.toString = function () { return this.href; };
  this.resolve = function (rel) { return new URL(rel, this.href); };
}
function __parseUrl(input, base) {
  var url = String(input);
  if (url.indexOf('//') === 0) url = 'https:' + url;
  if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(url) && base) {
    var m = /^([a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^\/?#]*)/.exec(String(base));
    if (m) {
      var host = m[1];
      if (url.charAt(0) === '/') { url = host + url; }
      else {
        var rest = String(base).substring(m[1].length);
        var dir = rest.substring(0, rest.lastIndexOf('/') + 1);
        url = host + dir + url;
      }
    }
  }
  var mm = /^([a-zA-Z][a-zA-Z0-9+.-]*):\/\/([^\/?#]*)([/?#][^]*)?$/.exec(url);
  var protocol = mm ? mm[1] + ':' : '';
  var host = mm ? mm[2] : '';
  var rest = mm && mm[3] ? mm[3] : '';
  var path = rest.split('?')[0].split('#')[0];
  var search = rest.indexOf('?') >= 0 ? '?' + rest.substring(rest.indexOf('?') + 1).split('#')[0] : '';
  var hash = rest.indexOf('#') >= 0 ? rest.substring(rest.indexOf('#')) : '';
  return [url, protocol, host, path, search, hash];
}

// ----- TextEncoder / TextDecoder
function TextEncoder() {}
TextEncoder.prototype.encode = function (s) {
  var str = String(s); var out = [];
  for (var i = 0; i < str.length; i++) out.push(str.charCodeAt(i) & 0xff);
  out.toString = function () { return str; }; return out;
};
function TextDecoder() {}
TextDecoder.prototype.decode = function () { return ''; };

// ----- require ("util", "https", …)
function require(name) {
  if (name === 'util') {
    return {
      inspect: function (o) { try { return String(o); } catch (e) { return '[object]'; } },
      format: function (f) { return f; },
      isArray: Array.isArray,
      types: { isBuffer: function () { return false; } }
    };
  }
  throw new Error('Module not supported: ' + name);
}

// ----- AbortController (signaux d'annulation fetch)
function AbortSignal() {}
AbortSignal.prototype.addEventListener = function (t, fn) {
  if (t === 'abort') (this._listeners = this._listeners || []).push(fn);
};
AbortSignal.prototype.removeEventListener = function (t, fn) {
  if (t === 'abort' && this._listeners) {
    var i = this._listeners.indexOf(fn);
    if (i >= 0) this._listeners.splice(i, 1);
  }
};
AbortSignal.timeout = function (ms) {
  var s = new AbortSignal();
  s.aborted = false;
  if (typeof setTimeout === 'function') {
    setTimeout(function () { s.aborted = true; }, Number(ms) || 0);
  }
  return s;
};
AbortSignal.abort = function (reason) {
  var s = new AbortSignal();
  s.aborted = true;
  s.reason = reason;
  return s;
};
function AbortController() {
  this.signal = new AbortSignal();
  this.signal.aborted = false;
}
AbortController.prototype.abort = function (reason) {
  var s = this.signal;
  if (s.aborted) return;
  s.aborted = true; s.reason = reason;
  var ls = s._listeners || [];
  s._listeners = [];
  for (var i = 0; i < ls.length; i++) { try { ls[i](); } catch (e) {} }
};

// ----- URLSearchParams
function URLSearchParams(init) {
  this._m = {};
  if (typeof init === 'string') {
    var parts = init.replace(/^\?/, '').split('&');
    for (var i = 0; i < parts.length; i++) {
      if (!parts[i]) continue;
      var kv = parts[i].split('=');
      this._m[decodeURIComponent(kv[0].replace(/\+/g, ' '))] =
        decodeURIComponent((kv[1] || '').replace(/\+/g, ' '));
    }
  }
}
URLSearchParams.prototype.get = function (k) { return this._m.hasOwnProperty(k) ? this._m[k] : null; };
URLSearchParams.prototype.set = function (k, v) { this._m[k] = String(v); };
URLSearchParams.prototype.append = function (k, v) {
  var cur = this._m[k];
  this._m[k] = cur == null ? String(v) : cur + ',' + v;
};
URLSearchParams.prototype.has = function (k) { return this._m.hasOwnProperty(k); };
URLSearchParams.prototype.toString = function () {
  var out = [];
  for (var k in this._m) {
    if (this._m.hasOwnProperty(k)) out.push(encodeURIComponent(k) + '=' + encodeURIComponent(this._m[k]));
  }
  return out.join('&');
};

// ----- Headers (objet simple, accepte objets et tableaux)
function Headers(init) {
  this._h = {};
  if (init) {
    var self = this;
    if (typeof init.forEach === 'function') { init.forEach(function (v, k) { self._h[String(k).toLowerCase()] = String(v); }); }
    else if (Array.isArray(init)) { for (var i = 0; i < init.length; i++) this._h[String(init[i][0]).toLowerCase()] = String(init[i][1]); }
    else { for (var k in init) if (init.hasOwnProperty(k)) this._h[k.toLowerCase()] = String(init[k]); }
  }
}
Headers.prototype.get = function (k) { return this._h.hasOwnProperty(k.toLowerCase()) ? this._h[k.toLowerCase()] : null; };
Headers.prototype.set = function (k, v) { this._h[k.toLowerCase()] = String(v); };
Headers.prototype.has = function (k) { return this._h.hasOwnProperty(k.toLowerCase()); };
Headers.prototype.append = function (k, v) {
  var key = k.toLowerCase();
  this._h[key] = this._h.hasOwnProperty(key) ? this._h[key] + ', ' + v : String(v);
};
Headers.prototype.forEach = function (fn) { for (var k in this._h) if (this._h.hasOwnProperty(k)) fn(this._h[k], k); };

// ----- queueMicrotask (exécution immédiate suffit avec nos promesses synchrones)
function queueMicrotask(fn) { __enqueue(function () { try { fn(); } catch (e) {} }); }

// ----- divers
var crypto = { getRandomValues: function (arr) { return arr; } };
""".trimIndent()
}
