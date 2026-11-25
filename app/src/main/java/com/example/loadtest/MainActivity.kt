package com.example.loadtest

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function as JsFunction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val handler = CoroutineExceptionHandler { _, e ->
        runOnUiThread {
            resultView.text = "صار خطأ:\n$e"
            startBtn.isEnabled = true
            stopBtn.isEnabled = false
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + handler)
    private var job: Job? = null
    private var client: OkHttpClient? = null

    private lateinit var modeCheck: CheckBox
    private lateinit var urlLabel: TextView
    private lateinit var urlInput: EditText
    private lateinit var scriptInput: EditText
    private lateinit var usersInput: EditText
    private lateinit var secInput: EditText
    private lateinit var ownerCheck: CheckBox
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var resultView: TextView

    private val sample = """import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 10,
  duration: '10s',
};

export default function () {
  const res = http.get('https://example.com');
  check(res, { 'status 200': (r) => r.status === 200 });
  sleep(1);
}
"""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 15f }
        root.addView(TextView(this).apply { text = "اختبار الحمل"; textSize = 24f })

        modeCheck = CheckBox(this).apply {
            text = "وضع سكربت K6 (الصق سكربت K6 وشغله)"
            setOnCheckedChangeListener { _, on -> updateMode(on) }
        }
        root.addView(modeCheck)

        urlLabel = label("رابط الموقع أو الـ API")
        root.addView(urlLabel)
        urlInput = EditText(this).apply {
            hint = "https://example.com"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        root.addView(urlInput)

        scriptInput = EditText(this).apply {
            setText(sample)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            minLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            visibility = View.GONE
        }
        root.addView(scriptInput)

        root.addView(label("عدد المستخدمين الوهميين (1 - 200)"))
        usersInput = EditText(this).apply { setText("10"); inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(usersInput)

        root.addView(label("المدة بالثواني (1 - 120)"))
        secInput = EditText(this).apply { setText("10"); inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(secInput)
        root.addView(label("(بوضع السكربت، إذا السكربت فيه vus و duration تنأخذ منه)").apply { textSize = 12f })

        ownerCheck = CheckBox(this).apply { text = "أؤكد إني أملك هذا الموقع أو عندي إذن لاختباره" }
        root.addView(ownerCheck)

        startBtn = Button(this).apply { text = "ابدأ الاختبار"; setOnClickListener { start() } }
        stopBtn = Button(this).apply { text = "إيقاف"; isEnabled = false; setOnClickListener { stop() } }
        root.addView(startBtn)
        root.addView(stopBtn)

        resultView = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad, 0, 0)
            setTextIsSelectable(true)
        }
        root.addView(resultView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun updateMode(script: Boolean) {
        urlLabel.visibility = if (script) View.GONE else View.VISIBLE
        urlInput.visibility = if (script) View.GONE else View.VISIBLE
        scriptInput.visibility = if (script) View.VISIBLE else View.GONE
    }

    private fun stop() {
        client?.dispatcher?.cancelAll()
        job?.cancel()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun start() {
        val scriptMode = modeCheck.isChecked
        val url = urlInput.text.toString().trim()
        val code = scriptInput.text.toString()
        if (!scriptMode && url.toHttpUrlOrNull() == null) {
            toast("الرابط غير صحيح، لازم يبدأ بـ http أو https"); return
        }
        if (!ownerCheck.isChecked) {
            toast("لازم تأكد إنك تملك الموقع"); return
        }
        var users = (usersInput.text.toString().toIntOrNull() ?: 10).coerceIn(1, 200)
        var secs = (secInput.text.toString().toIntOrNull() ?: 10).coerceIn(1, 120)

        val stats = Stats()
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        client = http
        val bridge = K6Bridge(http, stats)

        startBtn.isEnabled = false
        stopBtn.isEnabled = true
        resultView.text = "جاري التحضير..."

        job = scope.launch {
            if (scriptMode) {
                val p = withContext(Dispatchers.Default) { K6Engine.probe(code, bridge) }
                if (p.error != null) {
                    resultView.text = "خطأ بالسكربت:\n${p.error}"
                    startBtn.isEnabled = true
                    stopBtn.isEnabled = false
                    return@launch
                }
                p.vus?.let { users = it.coerceIn(1, 200) }
                p.secs?.let { secs = it.coerceIn(1, 120) }
            }

            val pool = Executors.newFixedThreadPool(users)
            val dispatcher = pool.asCoroutineDispatcher()
            val begin = System.nanoTime()
            val endAt = begin + secs * 1_000_000_000L

            fun show(final: Boolean) {
                val total = stats.ok.get() + stats.fail.get()
                val elapsed = ((System.nanoTime() - begin) / 1e9).coerceAtLeast(0.001)
                val avg = if (total > 0) stats.sumMs.get() / total else 0
                val sb = StringBuilder()
                sb.append(if (final) "النتيجة النهائية\n\n" else "جاري الاختبار...\n\n")
                sb.append("المستخدمين: $users | المدة: $secs ثانية\n")
                sb.append("إجمالي الطلبات: $total\n")
                sb.append("الناجحة: ${stats.ok.get()}\n")
                sb.append("الفاشلة: ${stats.fail.get()}\n")
                sb.append("طلب بالثانية: ${"%.1f".format(total / elapsed)}\n")
                sb.append("متوسط الاستجابة: $avg ms\n")
                if (total > 0) {
                    sb.append("أسرع طلب: ${stats.minMs.get()} ms\n")
                    sb.append("أبطأ طلب: ${stats.maxMs.get()} ms\n")
                }
                if (final && stats.lat.isNotEmpty()) {
                    val s = stats.lat.toList().sorted()
                    sb.append("P95: ${s[((s.size * 0.95).toInt()).coerceAtMost(s.size - 1)]} ms\n")
                }
                if (scriptMode) {
                    sb.append("التكرارات (iterations): ${stats.iterations.get()}\n")
                    if (stats.scriptErrors.get() > 0) sb.append("أخطاء السكربت: ${stats.scriptErrors.get()}\n")
                    if (stats.checks.isNotEmpty()) {
                        sb.append("\nالـ checks:\n")
                        for ((name, a) in stats.checks) {
                            synchronized(a) { sb.append("• $name  ✓${a[0]}  ✗${a[1]}\n") }
                        }
                    }
                    stats.lastError.get()?.let { sb.append("\nآخر خطأ: $it\n") }
                }
                resultView.text = sb.toString()
            }

            val workers = List(users) { idx ->
                launch(dispatcher) {
                    if (scriptMode) {
                        runScriptVu(code, bridge, stats, idx + 1, endAt)
                    } else {
                        while (isActive && System.nanoTime() < endAt) bridge.get(url)
                    }
                }
            }
            val ui = launch {
                while (isActive) { show(false); delay(500) }
            }
            try {
                workers.joinAll()
            } finally {
                ui.cancel()
                pool.shutdownNow()
                show(true)
                startBtn.isEnabled = true
                stopBtn.isEnabled = false
            }
        }
    }

    private fun CoroutineScope.runScriptVu(
        code: String, bridge: K6Bridge, stats: Stats, vu: Int, endAt: Long
    ) {
        val cx = Context.enter()
        try {
            val sc = K6Engine.newScope(cx, code, bridge, vu)
            val main = sc.get("__main", sc) as? JsFunction ?: return
            while (isActive && System.nanoTime() < endAt) {
                stats.iterations.incrementAndGet()
                try {
                    main.call(cx, sc, sc, arrayOf<Any>())
                } catch (e: Throwable) {
                    stats.scriptErrors.incrementAndGet()
                    stats.lastError.set(e.toString())
                    Thread.sleep(100)
                }
            }
        } catch (e: Throwable) {
            stats.lastError.set(e.toString())
        } finally {
            Context.exit()
        }
    }

    override fun onDestroy() {
        stop()
        scope.cancel()
        super.onDestroy()
    }
}
