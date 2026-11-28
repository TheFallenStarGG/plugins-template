package com.github.yournamehere

import android.app.ActivityManager
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@AliucordPlugin
class LiveTranslate : Plugin() {

    private val ui = Handler(Looper.getMainLooper())
    private var uiCtx: WeakReference<Context>? = null
    private val rep = StringBuilder()

    private val cBg = 0xFF313338.toInt()
    private val cText = 0xFFDBDEE1.toInt()
    private val cBlurple = 0xFF5865F2.toInt()

    private val probeJs = """(function(){var r={};r.ua=navigator.userAgent;r.wasm=typeof WebAssembly!=='undefined';try{r.simd=WebAssembly.validate(new Uint8Array([0,97,115,109,1,0,0,0,1,5,1,96,0,1,123,3,2,1,0,10,10,1,8,0,65,0,253,15,253,98,11]))}catch(e){r.simd='err '+e}r.fetch=typeof fetch;r.sharedArrayBuffer=typeof SharedArrayBuffer;try{r.jsHeapLimit=performance.memory?performance.memory.jsHeapSizeLimit:null}catch(e){}return JSON.stringify(r)})()"""

    // ---------- helpers ----------
    private fun line(s: String) {
        synchronized(rep) { rep.append(s).append('\n') }
    }

    private fun short(s: String?, n: Int = 140): String {
        if (s == null) return "null"
        return if (s.length > n) s.substring(0, n) + "..." else s
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun httpGet(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, body)
        } finally {
            c.disconnect()
        }
    }

    // ---------- dark report box ----------
    private fun showInfo(ctx: Context, title: String, text: String) {
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(cBg)
        bg.cornerRadius = dp(ctx, 14).toFloat()
        root.background = bg
        val pad = dp(ctx, 16)
        root.setPadding(pad, pad, pad, pad)

        val t = TextView(ctx)
        t.text = title
        t.textSize = 18f
        t.setTextColor(cText)
        t.setTypeface(t.typeface, Typeface.BOLD)
        root.addView(t)

        val tv = TextView(ctx)
        tv.text = text
        tv.textSize = 10f
        tv.setTextColor(cText)
        tv.typeface = Typeface.MONOSPACE
        tv.setTextIsSelectable(true)
        val sv = ScrollView(ctx)
        sv.addView(tv)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val close = TextView(ctx)
        close.text = "Close"
        close.textSize = 14f
        close.setTextColor(Color.WHITE)
        close.setTypeface(close.typeface, Typeface.BOLD)
        close.gravity = Gravity.CENTER
        val cbg = GradientDrawable()
        cbg.setColor(cBlurple)
        cbg.cornerRadius = dp(ctx, 8).toFloat()
        close.background = cbg
        val cp = dp(ctx, 10)
        close.setPadding(cp, cp, cp, cp)
        close.setOnClickListener { dlg.dismiss() }
        val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clp.topMargin = dp(ctx, 8)
        root.addView(close, clp)

        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.show()
        dlg.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (ctx.resources.displayMetrics.heightPixels * 0.85f).toInt()
        )
    }

    // ---------- probes ----------
    private fun probeDevice(ctx: Context) {
        line("== DEVICE ==")
        line("model=${Build.MANUFACTURER} ${Build.MODEL}, android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        line("abis=" + Build.SUPPORTED_ABIS.joinToString(","))
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        line("ram total=${mi.totalMem / 1048576}MB avail=${mi.availMem / 1048576}MB lowMemory=${mi.lowMemory}")
        line("app heap class=${am.memoryClass}MB large=${am.largeMemoryClass}MB")
        line("free storage in app dir=${ctx.filesDir.usableSpace / 1048576}MB")
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val p = WebView.getCurrentWebViewPackage()
                line("webview package=${p?.packageName} version=${p?.versionName}")
            } catch (e: Throwable) {
                line("webview package lookup failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun probeTextClassifier(ctx: Context) {
        line("")
        line("== TEXT CLASSIFIER ==")
        if (Build.VERSION.SDK_INT < 29) {
            line("not available (needs Android 10+, this is SDK ${Build.VERSION.SDK_INT})")
            return
        }
        try {
            val tcm = ctx.getSystemService(TextClassificationManager::class.java)
            val tc = tcm.textClassifier
            val samples = listOf(
                "Hola, como estas? Esto es una prueba muy sencilla",
                "Das ist ein ganz einfacher Test, wie geht es dir heute",
                "This is just a simple test sentence in English"
            )
            for (s in samples) {
                val r = tc.detectLanguage(TextLanguage.Request.Builder(s).build())
                if (r.localeHypothesisCount > 0) {
                    val loc = r.getLocale(0)
                    line("  \"${short(s, 30)}\" -> ${loc.toLanguageTag()} score=${r.getConfidenceScore(loc)}")
                } else {
                    line("  \"${short(s, 30)}\" -> no result")
                }
            }
        } catch (e: Throwable) {
            line("failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun probeWebView(ctx: Context) {
        line("")
        line("== WEBVIEW / WEBASSEMBLY ==")
        val latch = CountDownLatch(1)
        var out = "timeout (no answer in 12s)"
        ui.post {
            try {
                val wv = WebView(ctx)
                wv.settings.javaScriptEnabled = true
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(probeJs) { res ->
                            out = res ?: "null"
                            try { view.destroy() } catch (e: Throwable) { }
                            latch.countDown()
                        }
                    }
                }
                wv.loadDataWithBaseURL("https://probe.local/", "<html><body>probe</body></html>", "text/html", "utf-8", null)
            } catch (e: Throwable) {
                out = "WebView failed: ${e.javaClass.simpleName}: ${e.message}"
                latch.countDown()
            }
        }
        latch.await(12, TimeUnit.SECONDS)
        line(out.trim('"').replace("\\\"", "\""))
    }

    private fun probeWasm() {
        line("")
        line("== MOZILLA ENGINE FILES (remote settings: translations-wasm) ==")
        val root = httpGet("https://firefox.settings.services.mozilla.com/v1/")
        line("server info HTTP ${root.first}")
        if (root.first == 200) {
            val base = JSONObject(root.second).optJSONObject("capabilities")
                ?.optJSONObject("attachments")?.optString("base_url")
            line("attachments base_url=$base")
        }
        val (code, body) = httpGet("https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-wasm/records")
        line("records HTTP $code")
        if (code != 200) {
            line(short(body, 300))
            return
        }
        val arr = JSONObject(body).getJSONArray("data")
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val a = r.optJSONObject("attachment")
            line("  name=${r.optString("name")} version=${r.optString("version")} file=${a?.optString("filename")} size=${a?.optLong("size")} loc=${a?.optString("location")}")
        }
    }

    private fun probeModels() {
        line("")
        line("== MOZILLA MODELS (translations-models) ==")
        val (code, body) = httpGet("https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records")
        line("HTTP $code, ${body.length / 1024} KB")
        if (code != 200) {
            line(short(body, 300))
            return
        }
        val arr = JSONObject(body).getJSONArray("data")
        line("records: ${arr.length()}")
        if (arr.length() == 0) return
        val first = arr.getJSONObject(0)
        val keys = ArrayList<String>()
        val it = first.keys()
        while (it.hasNext()) keys.add(it.next())
        line("record keys: " + keys.joinToString(","))
        line("first record: " + short(first.toString(), 700))
        val toEn = sortedSetOf<String>()
        val exts = sortedSetOf<String>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optString("toLang") == "en") toEn.add(r.optString("fromLang"))
            val fn = r.optJSONObject("attachment")?.optString("filename") ?: ""
            exts.add(fn.substringAfterLast('.', "none"))
        }
        line("languages that translate TO english (${toEn.size}): " + toEn.joinToString(" "))
        line("attachment file extensions: " + exts.joinToString(" "))
        for (pair in listOf("es" to "en", "de" to "en", "ja" to "en")) {
            line("-- ${pair.first} -> ${pair.second} --")
            var n = 0
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (r.optString("fromLang") != pair.first || r.optString("toLang") != pair.second) continue
                if (n++ >= 10) break
                val a = r.optJSONObject("attachment")
                line("  ${r.optString("fileType")} v${r.optString("version")} ${a?.optString("filename")} ${a?.optLong("size")}B")
            }
            if (n == 0) line("  (none)")
        }
    }

    private fun probePackage() {
        line("")
        line("== ENGINE PACKAGE ON jsDelivr (@browsermt/bergamot-translator) ==")
        val (c1, b1) = httpGet("https://data.jsdelivr.com/v1/packages/npm/@browsermt/bergamot-translator")
        line("package info HTTP $c1")
        if (c1 != 200) {
            line(short(b1, 200))
            return
        }
        val latest = JSONObject(b1).optJSONObject("tags")?.optString("latest") ?: ""
        line("latest=$latest")
        if (latest.isEmpty()) return
        val (c2, b2) = httpGet("https://data.jsdelivr.com/v1/packages/npm/@browsermt/bergamot-translator@$latest?structure=flat")
        line("file list HTTP $c2")
        if (c2 != 200) {
            line(short(b2, 200))
            return
        }
        val files = JSONObject(b2).optJSONArray("files") ?: return
        var n = 0
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.optString("name")
            if (name.endsWith(".js") || name.endsWith(".wasm") || name.endsWith(".json")) {
                if (n++ < 30) line("  $name ${f.optLong("size")}B")
            }
        }
    }

    private fun excerpts(src: String) {
        val keys = listOf(
            "TranslationModel(", "BlockingService(", "AlignedMemory", "gemm-precision", "beam-size",
            "wasmBinary", "instantiateWasm", "VectorString", "VectorResponseOptions", ".translate("
        )
        val lines = src.split("\n")
        val picked = sortedSetOf<Int>()
        for ((i, l) in lines.withIndex()) {
            if (keys.any { l.contains(it) }) {
                for (k in (i - 1)..(i + 1)) if (k in lines.indices) picked.add(k)
            }
        }
        var n = 0
        for (i in picked) {
            if (n++ > 90) break
            line("  ${i + 1}: " + short(lines[i].trim(), 150))
        }
    }

    private fun probeGecko() {
        line("")
        line("== FIREFOX SOURCE (how Firefox calls the engine) ==")
        val dirs = listOf(
            "toolkit/components/translations",
            "toolkit/components/translations/content",
            "toolkit/components/translations/bergamot-translator"
        )
        val workers = ArrayList<String>()
        for (d in dirs) {
            val (code, body) = httpGet("https://api.github.com/repos/mozilla/gecko-dev/contents/$d")
            line("[$d] HTTP $code")
            if (code != 200) continue
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val type = o.optString("type")
                line("  $name ${if (type == "dir") "/" else "(${o.optLong("size")}B)"}")
                if (type == "file" && name.contains("worker") && (name.endsWith(".js") || name.endsWith(".mjs"))) {
                    workers.add(o.optString("path"))
                }
            }
        }
        for (p in workers.take(3)) {
            val (code, body) = httpGet("https://raw.githubusercontent.com/mozilla/gecko-dev/master/$p")
            line("")
            line("-- $p HTTP $code, ${body.length} chars --")
            if (code == 200) excerpts(body)
        }
    }

    private fun runProbe(ctx: Context) {
        val steps = listOf<Pair<String, () -> Unit>>(
            "device" to { probeDevice(ctx) },
            "text classifier" to { probeTextClassifier(ctx) },
            "webview" to { probeWebView(ctx) },
            "engine files" to { probeWasm() },
            "models" to { probeModels() },
            "package" to { probePackage() },
            "firefox source" to { probeGecko() }
        )
        for ((name, fn) in steps) {
            try {
                fn()
            } catch (e: Throwable) {
                line("")
                line("!! step '$name' failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun startProbe() {
        val ctx = uiCtx?.get() ?: return
        Thread {
            synchronized(rep) { rep.setLength(0) }
            runProbe(ctx)
            val text = synchronized(rep) { rep.toString() }
            ui.post {
                try {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("bgprobe", text.take(18000)))
                } catch (e: Throwable) {
                    Log.e("LiveTranslate", "clipboard failed", e)
                }
                showInfo(ctx, "Probe report (copied to clipboard)", text)
            }
        }.start()
    }

    override fun start(context: Context) {
        try {
            patcher.after<WidgetChatListAdapterItemMessage>(
                "onConfigure", Int::class.java, ChatListEntry::class.java
            ) { param ->
                try {
                    uiCtx = WeakReference((param.thisObject as RecyclerView.ViewHolder).itemView.context)
                } catch (e: Throwable) {
                }
            }
        } catch (e: Throwable) {
            Log.e("LiveTranslate", "hook failed", e)
        }

        commands.registerCommand("bgprobe", "Collect facts for the offline translation engine", emptyList()) { _ ->
            if (uiCtx?.get() == null) {
                CommandsAPI.CommandResult("Open a chat with some messages on screen first, then run this again.", null, false)
            } else {
                startProbe()
                CommandsAPI.CommandResult("Probe running (about 30 seconds). A box opens when it's done and the report is copied to your clipboard.", null, false)
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }
}
