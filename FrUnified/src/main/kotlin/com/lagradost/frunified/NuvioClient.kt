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
    private const val MAX_LINKS_PER_SCRAPER = 30
    private const val SCRAPER_TIMEOUT_MS = 90_000L
    private const val NETWORK_TIMEOUT_MS = 20_000

    data class NuvioScraper(
        val id: String,
        val name: String,
        val filename: String,
        val repoBase: String,
        val supportedTypes: List<String>,
        val contentLanguage: List<String>,
        val description: String
    ) {
        val isFrench: Boolean get() = contentLanguage.any { it.startsWith("fr") }
        val scriptUrl: String
            get() = ("$repoBase/$filename")
                .replace(Regex("https://+"), "https://")
                .replace(Regex("http://+"), "http://")
    }

    private val manifestCache = ConcurrentHashMap<String, Pair<Long, List<NuvioScraper>>>()
    private val tmdbCache = ConcurrentHashMap<String, Pair<Long, Int?>>()

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
        repos.map { repo ->
            async { withTimeoutOrNull(20_000L) { manifest(repo) }.orEmpty() }
        }.awaitAll().flatten()
            .filter { FrSettings.isNuvioEnabled(it.id) }
            .filter { it.supportedTypes.any { t -> t == "movie" || t == "tv" || t.equals("series", true) } }
            .filter { FrSettings.nuvioAllLangs || it.isFrench }
            .distinctBy { it.id }
            .sortedWith(compareBy({ if (it.isFrench) 0 else 1 }, { it.name.lowercase() }))
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
                description = entry.optString("description")
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
                        withTimeoutOrNull(SCRAPER_TIMEOUT_MS) {
                            runScraper(scraper, tmdbId, mediaType, season, episode, payload, callback)
                        } ?: false
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
        val code = script(scraper) ?: return@withContext false

        val cx = try {
            RhinoContext.enter()
        } catch (t: Throwable) {
            return@withContext false
        }
        try {
            cx.optimizationLevel = -1   // interprété : compatible ART/Android
            cx.languageVersion = RhinoContext.VERSION_ES6

            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, JS_ENV, "prelude", 1, null)

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
                    return@withContext false
                }
                result = ScriptableObject.getProperty(resultObj, "__value")
            }

            val streams = asArray(cx, scope, result) ?: return@withContext false
            var emitted = false
            var count = 0

            for (i in 0 until runCatching { RhinoContext.toNumber(ScriptableObject.getProperty(streams, "length")).toInt() }.getOrDefault(0)) {
                if (count >= MAX_LINKS_PER_SCRAPER) break
                val obj = ScriptableObject.getProperty(streams, i) as? Scriptable ?: continue
                val link = toLink(scope, scraper.name, payload, obj) ?: continue
                emitted = true
                count++
                callback(link)
            }
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
var navigator = { userAgent: 'Mozilla/5.0 (Android)' };
var location = { href: 'https://frunified.fr/', protocol: 'https:', hostname: 'frunified.fr', origin: 'https://frunified.fr' };
var __timers = [];
function setTimeout(fn, ms) { __timers.push(fn); return __timers.length; }
function clearTimeout(id) {}
function setInterval(fn, ms) { __timers.push(fn); return __timers.length; }
function clearInterval(id) {}
function __drain() { while (__timers.length) { var t = __timers.shift(); try { t(); } catch (e) {} } }
function __timerCount() { return __timers.length; }

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

// ----- divers
var crypto = { getRandomValues: function (arr) { return arr; } };
""".trimIndent()
}
