package com.example.loadtest

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class Stats {
    val ok = AtomicInteger()
    val fail = AtomicInteger()
    val sumMs = AtomicLong()
    val minMs = AtomicLong(Long.MAX_VALUE)
    val maxMs = AtomicLong(0)
    val lat = ConcurrentLinkedQueue<Long>()
    val iterations = AtomicInteger()
    val scriptErrors = AtomicInteger()
    val lastError = AtomicReference<String?>(null)
    val checks = ConcurrentHashMap<String, IntArray>()

    fun record(ms: Long, success: Boolean) {
        if (success) ok.incrementAndGet() else fail.incrementAndGet()
        lat.add(ms)
        sumMs.addAndGet(ms)
        minMs.getAndUpdate { minOf(it, ms) }
        maxMs.getAndUpdate { maxOf(it, ms) }
    }

    fun check(name: String, passed: Boolean) {
        val a = checks.computeIfAbsent(name) { IntArray(2) }
        synchronized(a) { a[if (passed) 0 else 1]++ }
    }
}

class Timings(val duration: Double)
class K6Response(val status: Int, val body: String, val timings: Timings)

class K6Bridge(private val client: OkHttpClient, private val stats: Stats) {
    fun get(url: String): K6Response = send(Request.Builder().url(url).build())

    fun post(url: String, body: String): K6Response = send(
        Request.Builder().url(url)
            .post(body.toRequestBody("application/json".toMediaType())).build()
    )

    fun sleep(sec: Double) {
        Thread.sleep((sec * 1000).toLong())
    }

    fun rec(name: String, passed: Boolean) = stats.check(name, passed)

    private fun send(req: Request): K6Response {
        val t0 = System.nanoTime()
        var status = 0
        var text = ""
        try {
            client.newCall(req).execute().use { r ->
                status = r.code
                text = r.body?.string() ?: ""
            }
        } catch (e: Throwable) {
            // status stays 0
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        stats.record(ms, status in 200..299)
        return K6Response(status, text, Timings(ms.toDouble()))
    }
}

data class Probe(val vus: Int?, val secs: Int?, val error: String?)

object K6Engine {
    private const val PRELUDE = """
var http = {
  get: function (u, p) { return __k.get(String(u)); },
  post: function (u, b, p) {
    var body = (b !== null && typeof b === 'object') ? JSON.stringify(b) : String(b === undefined ? '' : b);
    return __k.post(String(u), body);
  }
};
var check = function (val, checks) {
  var all = true;
  for (var name in checks) {
    var ok = false;
    try { ok = !!checks[name](val); } catch (e) { ok = false; }
    __k.rec(String(name), ok);
    if (!ok) all = false;
  }
  return all;
};
var sleep = function (s) { __k.sleep(Number(s)); };
var group = function (n, f) { return f(); };
var console = { log: function () {}, warn: function () {}, error: function () {}, info: function () {} };
"""

    fun convert(src: String): String = src.lines()
        .filterNot { it.trimStart().startsWith("import ") }
        .joinToString("\n")
        .replace(Regex("export\\s+(const|let|var)\\s+"), "var ")
        .replace(Regex("export\\s+default\\s+(async\\s+)?function\\s*\\w*\\s*\\("), "var __main = function(")
        .replace(Regex("export\\s+function\\s+"), "function ")

    fun newScope(cx: Context, code: String, bridge: K6Bridge, vu: Int): Scriptable {
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        cx.wrapFactory.isJavaPrimitiveWrap = false
        val scope = cx.initStandardObjects()
        ScriptableObject.putProperty(scope, "__k", Context.javaToJS(bridge, scope))
        ScriptableObject.putProperty(scope, "__VU", Context.javaToJS(vu, scope))
        cx.evaluateString(scope, PRELUDE + "\n" + convert(code), "script", 1, null)
        return scope
    }

    fun parseSeconds(s: String): Int? {
        val m = Regex("^(\\d+)\\s*(ms|s|m|h)$").find(s.trim()) ?: return null
        val n = m.groupValues[1].toInt()
        return when (m.groupValues[2]) {
            "ms" -> maxOf(1, n / 1000)
            "s" -> n
            "m" -> n * 60
            else -> n * 3600
        }
    }

    fun probe(code: String, bridge: K6Bridge): Probe {
        val cx = Context.enter()
        return try {
            val sc = newScope(cx, code, bridge, 0)
            if (sc.get("__main", sc) !is org.mozilla.javascript.Function) {
                return Probe(null, null, "ما لكيت export default function")
            }
            val opt = sc.get("options", sc) as? Scriptable
            val vus = (opt?.get("vus", opt) as? Number)?.toInt()
            val dur = (opt?.get("duration", opt) as? CharSequence)?.toString()
            Probe(vus, dur?.let { parseSeconds(it) }, null)
        } catch (e: Throwable) {
            Probe(null, null, e.toString())
        } finally {
            Context.exit()
        }
    }
}
