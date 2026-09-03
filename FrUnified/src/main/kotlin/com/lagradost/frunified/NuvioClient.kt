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
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
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

    private const val SCRIPT_TTL_MS = 12 * 60 * 60 * 1000L   // 12 h
    private const val MANIFEST_TTL_MS = 6 * 60 * 60 * 1000L  // 6 h
    private const val SCRAPER_TIMEOUT_MS = 60_000L
    private const val NUVIO_CONCURRENCY = 6

    /** « f.push(...x) » → « f.push.apply(f, x) » (Rhino ne parse pas le spread d'appel). */
    private val SPREAD_CALL = Regex("([A-Za-z_$][\\w$]*)\\.push\\(\\.\\.\\.([A-Za-z_$][\\w$.\\[\\]]*)\\)")

    private const val NETWORK_TIMEOUT_MS = 20_000

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

    /** Re-crée le sémaphore si l'utilisateur a changé la concurrence. */
    private fun syncSemaphore() {
        val wanted = FrSettings.nuvioConcurrency
        if (semaphore.availablePermits != wanted) semaphore = Semaphore(wanted)
    }

    /** Résultats du dernier passage (id scrapeur -> « ✓ 12 liens » ou « ✗ raison »). */
    private val lastResults = ConcurrentHashMap<String, String>()
    fun diagnostics(): Map<String, String> = lastResults.toMap()

    @Volatile
    private var cacheDir: File? = null

    fun init(context: Context) {
        if (cacheDir == null) {
            cacheDir = runCatching { File(context.filesDir, "nuvio").apply { mkdirs() } }.getOrNull()
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
            if (!cached.isNullOrBlank()) return normalizeBundle(cached)
        }
        val code = runCatching {
            withContext(Dispatchers.IO) { httpGet(scraper.scriptUrl, emptyMap()) }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (code != null) runCatching { file?.writeText(code) }
        return code?.let { normalizeBundle(it) }
    }

    /**
     * Adapte un bundle webpack aux limitations du parseur Rhino :
     *  - « f.push(...liste) » (spread en argument d'appel) n'est pas supporté
     *    par Rhino → transformé en « f.push.apply(f, liste) ».
     * (Le reste — générateurs, for-of, spread de tableaux — est géré nativement
     * par le Rhino 1.9.1 de NuvioClient, avec TopLevel + patch yield-en-argument.)
     */
    private fun normalizeBundle(code: String): String {
        if (code.indexOf("...") < 0) return code
        return SPREAD_CALL.replace(code) { m ->
            "${m.groupValues[1]}.push.apply(${m.groupValues[1]}, ${m.groupValues[2]})"
        }
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
        val code = script(scraper)
        if (code == null) {
            lastResults[scraper.id] = "✗ script introuvable"
            return@withContext false
        }

        val cx = try {
            RhinoContext.enter()
        } catch (t: Throwable) {
            return@withContext false
        }
        try {
            cx.optimizationLevel = -1   // interprété : compatible ART/Android
            cx.languageVersion = RhinoContext.VERSION_ES6

            // IMPORTANT : TopLevel() active le cache des builtins (cacheBuiltins) :
            // sans lui, le prototype des fonctions génératrices n'est pas initialisé
            // et les bundles transpilés (babel) échouent en « Cannot find function apply ».
            val scope = cx.initStandardObjects(org.mozilla.javascript.TopLevel())
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

            try {
                cx.evaluateString(scope, code, scraper.id, 1, null)
            } catch (t: Throwable) {
                lastResults[scraper.id] = "✗ erreur JS : " + (t.message?.take(80) ?: t::class.simpleName.orEmpty())
                return@withContext false
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
            if (fn == null || fn == Scriptable.NOT_FOUND || fn !is org.mozilla.javascript.Callable) {
                lastResults[scraper.id] = "✗ getStreams introuvable"
                return@withContext false
            }

            val args = arrayOf<Any?>(
                cx.evaluateString(scope, tmdbId.toString(), "n", 1, null),
                cx.evaluateString(scope, JSONObject.quote(mediaType), "s", 1, null),
                cx.evaluateString(scope, season.toString(), "n", 1, null),
                cx.evaluateString(scope, episode.toString(), "n", 1, null)
            )

            var result: Any? = try {
                (fn as org.mozilla.javascript.Callable).call(cx, scope, scope, args)
            } catch (t: Throwable) {
                lastResults[scraper.id] = "✗ appel : " + (t.message?.take(80) ?: t::class.simpleName.orEmpty())
                return@withContext false
            }

            // Fait avancer les timers et les chaînes de promesses synchrones
            var guard = 0
            while (timerCount(cx, scope) > 0 && guard++ < 80) {
                drain(cx, scope)
            }
            drain(cx, scope)

            // Si le résultat est notre promesse, on lit sa valeur
            val resultObj = result as? Scriptable
            if (resultObj != null && runCatching {
                    ScriptableObject.hasProperty(resultObj, "__settled")
                }.getOrDefault(false)) {
                if (ScriptableObject.getProperty(resultObj, "__rejected") == java.lang.Boolean.TRUE) {
                    val rej = ScriptableObject.getProperty(resultObj, "__value")
                    lastResults[scraper.id] =
                        "✗ rejet : " + (rej?.toString()?.take(80) ?: "inconnu")
                    return@withContext false
                }
                result = ScriptableObject.getProperty(resultObj, "__value")
            }

            val streams = asArray(cx, scope, result)
            if (streams == null) {
                lastResults[scraper.id] = "✗ résultat non reconnu"
                return@withContext false
            }
            var emitted = false
            var count = 0
            val maxHere = FrSettings.nuvioMaxPerScraper

            for (i in 0 until runCatching { RhinoContext.toNumber(ScriptableObject.getProperty(streams, "length")).toInt() }.getOrDefault(0)) {
                if (maxHere > 0 && count >= maxHere) break
                val obj = ScriptableObject.getProperty(streams, i) as? Scriptable ?: continue
                val link = toLink(scope, scraper.name, payload, obj) ?: continue
                emitted = true
                count++
                callback(link)
            }
            lastResults[scraper.id] = if (emitted) "✓ $count lien(s)" else "✓ 0 lien"
            emitted
        } catch (t: Throwable) {
            false // un scrapeur qui plante ne doit jamais faire planter la lecture
        } finally {
            RhinoContext.exit()
        }
    }

    private fun callJs(cx: RhinoContext, scope: Scriptable, name: String, args: Array<Any?>): Any? =
        runCatching {
            (scope.get(name, scope) as? org.mozilla.javascript.Callable)?.call(cx, scope, scope, args)
        }.getOrNull()

    private fun drain(cx: RhinoContext, scope: Scriptable) {
        callJs(cx, scope, "__drain", emptyArray())
    }

    private fun timerCount(cx: RhinoContext, scope: Scriptable): Int =
        runCatching { RhinoContext.toNumber(callJs(cx, scope, "__timerCount", emptyArray())).toInt() }
            .getOrDefault(0)

    private fun asArray(cx: RhinoContext, scope: Scriptable, value: Any?): Scriptable? {
        if (value is NativeArray) return value
        if (value is org.mozilla.javascript.NativeJavaObject) {
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

    private suspend fun toLink(
        scope: Scriptable,
        scraperName: String,
        payload: PlayPayload,
        obj: Scriptable
    ): ExtractorLink? {
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
        val label = prop("name", "title", "fileName", "label", "description") ?: scraperName
        val language = prop("language", "lang")?.uppercase()

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

        return when {
            url != null -> runCatching {
                newExtractorLink("Nuvio · $scraperName", "$scraperName • $label", url) {
                    this.quality = qualityOf("$label $language")
                    headers?.let { this.headers = it }
                }
            }.getOrNull()

            infoHash != null -> runCatching {
                val magnet = "magnet:?xt=urn:btih:$infoHash" +
                    "&dn=${payload.primaryTitle.replace(" ", "+")}" +
                    TRACKERS.joinToString("") { "&tr=$it" }
                newExtractorLink(
                    "Nuvio · $scraperName",
                    "Torrent • $label",
                    magnet,
                    ExtractorLinkType.MAGNET
                ) {
                    this.quality = qualityOf("$label $language")
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

    /** TMDB est nécessaire pour les scrapeurs Nuvio ; retrouvé par recherche si absent. */
    private suspend fun tmdbId(payload: PlayPayload): Int? {
        payload.tmdbId?.let { return it }
        val key = "${payload.primaryTitle}|${payload.year}"
        val now = System.currentTimeMillis()
        tmdbCache[key]?.let { (expiry, id) -> if (expiry > now) return id }

        val found = runCatching {
            TmdbCatalog.searchBest(payload.primaryTitle, payload.year)?.id?.id?.toIntOrNull()
        }.getOrNull()

        tmdbCache[key] = (now + SCRIPT_TTL_MS) to found
        return found
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
            conn.readTimeout = NETWORK_TIMEOUT_MS * 2
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
     * Test rapide d'UN scrapeur (bouton « Tester » des réglages) :
     * exécute son getStreams sur Fight Club (TMDB 550) et retourne le verdict.
     */
    suspend fun testProvider(id: String): String {
        val scraper = runCatching { scrapers().firstOrNull { it.id == id } }.getOrNull()
            ?: return "✗ source introuvable"
        val payload = PlayPayload(
            kind = "movie",
            titles = listOf("Fight Club"),
            year = 1999,
            tmdbId = 550
        )
        val links = java.util.concurrent.CopyOnWriteArrayList<ExtractorLink>()
        val ok = runCatching {
            withTimeoutOrNull(90_000L) {
                runScraper(scraper, 550, "movie", 0, 0, payload) { links += it }
            } ?: false
        }.getOrDefault(false)
        return if (ok) "✓ ${links.size} serveur(s)" else "✗ " + (diagnostics()[scraper.id] ?: "aucun résultat")
    }

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

            val (status, text) = doHttp(url, method, headerMap, body)
            return makeResponse(cx, scope, status, text)
        }

        private fun makeResponse(cx: RhinoContext, scope: Scriptable, status: Int, text: String): Any? =
            runCatching {
                ScriptableObject.callMethod(scope, "__makeResponse", arrayOf<Any?>(status, text))
            }.getOrNull() ?: Scriptable.NOT_FOUND
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
var console = { log:function(){}, warn:function(){}, error:function(){}, debug:function(){} };
var global = this;
var window = this;
var self = this;
var globalThis = this;
var process = { env: {} };
var navigator = { userAgent: 'Mozilla/5.0 (Android)' };
var location = { href: 'https://frunified.fr/', protocol: 'https:', hostname: 'frunified.fr', origin: 'https://frunified.fr' };
var __timers = [];
function setTimeout(fn, ms) { __timers.push(fn); return __timers.length; }
function clearTimeout(id) {}
function setInterval(fn, ms) { __timers.push(fn); return __timers.length; }
function clearInterval(id) {}
function __drain() { while (__timers.length) { var t = __timers.shift(); try { t(); } catch (e) {} } }
function __timerCount() { return __timers.length; }

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
  self.__run = function () {
    if (!self.__settled) return;
    var cbs = self.__callbacks; self.__callbacks = [];
    for (var i = 0; i < cbs.length; i++) {
      (function (cb) {
        try {
          if (self.__rejected) {
            if (cb.r) { cb.res(cb.r(self.__value)); } else { cb.rej(self.__value); }
          } else {
            if (cb.f) { cb.res(cb.f(self.__value)); } else { cb.res(self.__value); }
          }
        } catch (e) { cb.rej(e); }
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
function queueMicrotask(fn) { try { fn(); } catch (e) {} }

// ----- divers
var crypto = { getRandomValues: function (arr) { return arr; } };
""".trimIndent()
}
