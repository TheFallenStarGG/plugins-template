package com.github.yournamehere

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@AliucordPlugin
class LiveTranslate : Plugin() {

    class Bridge(private val cb: (String) -> Unit) {
        @JavascriptInterface
        fun done(json: String) {
            cb(json)
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var uiCtx: WeakReference<Context>? = null
    private val rep = StringBuilder()
    private var consoleCount = 0

    private val cBg = 0xFF313338.toInt()
    private val cText = 0xFFDBDEE1.toInt()
    private val cBlurple = 0xFF5865F2.toInt()

    private val testJs = """
window.__errs = [];
window.addEventListener('error', function(e){ window.__errs.push('error: ' + e.message); });
window.addEventListener('unhandledrejection', function(e){ window.__errs.push('rejection: ' + e.reason); });

async function probeWasm(name){
  try {
    var r = await fetch('/f/' + name + '.wasm');
    var buf = await r.arrayBuffer();
    var t0 = performance.now();
    var mod = await WebAssembly.compile(buf);
    var imps = WebAssembly.Module.imports(mod);
    var exps = WebAssembly.Module.exports(mod);
    var mods = {};
    imps.forEach(function(i){ mods[i.module] = (mods[i.module] || 0) + 1; });
    var names = imps.map(function(i){ return i.module + '.' + i.name; }).filter(function(n){ return /gemm|simd|moz/i.test(n); });
    return {name: name, bytes: buf.byteLength, compileMs: Math.round(performance.now() - t0), importModules: mods, interesting: names.slice(0, 12), exports: exps.length};
  } catch (e) {
    return {name: name, error: String(e)};
  }
}

function initNpm(){
  return new Promise(async function(resolve){
    var out = {name: 'npm-full-init'};
    try {
      var buf = await (await fetch('/f/npm.wasm')).arrayBuffer();
      var logs = [];
      window.Module = {
        wasmBinary: buf,
        print: function(s){ logs.push('out: ' + s); },
        printErr: function(s){ logs.push('err: ' + s); },
        onAbort: function(w){ out.abort = String(w); out.logs = logs.slice(0, 10); resolve(out); },
        onRuntimeInitialized: function(){
          out.initialized = true;
          out.keys = Object.keys(window.Module).filter(function(k){ return /Translat|Service|Aligned|Vector|Response/i.test(k); }).slice(0, 30);
          out.logs = logs.slice(0, 10);
          resolve(out);
        }
      };
      var s = document.createElement('script');
      s.src = '/f/npm.js';
      s.onerror = function(){ out.error = 'script failed to load'; resolve(out); };
      document.head.appendChild(s);
      setTimeout(function(){
        if (!out.initialized) { out.timeout = true; out.logs = logs.slice(0, 10); resolve(out); }
      }, 30000);
    } catch (e) {
      out.error = String(e);
      resolve(out);
    }
  });
}

async function probeAll(){
  var res = [];
  var names = ['moz3', 'moz2', 'moz11', 'npm'];
  for (var i = 0; i < names.length; i++) res.push(await probeWasm(names[i]));
  res.push(await initNpm());
  res.push({errs: window.__errs.slice(0, 10)});
  return res;
}
"""

    // ---------- helpers ----------
    private fun line(s: String) {
        synchronized(rep) { rep.append(s).append('\n') }
    }

    private fun short(s: String?, n: Int = 140): String {
        if (s == null) return "null"
        return if (s.length > n) s.substring(0, n) + "..." else s
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun download(url: String, f: File) {
        if (f.exists() && f.length() > 1000) {
            line("cached ${f.name} ${f.length()}B")
            return
        }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = c.responseCode
            if (code != 200) {
                line("download ${f.name} failed: HTTP $code")
                return
            }
            f.parentFile?.mkdirs()
            c.inputStream.use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
            }
            line("downloaded ${f.name} ${f.length()}B")
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

    // ---------- the test ----------
    private fun runWasmTest(ctx: Context) {
        val dir = File(ctx.filesDir, "bgprobe")
        dir.mkdirs()
        val cdn = "https://firefox-settings-attachments.cdn.mozilla.net/main-workspace/translations-wasm/"
        val npm = "https://cdn.jsdelivr.net/npm/@browsermt/bergamot-translator@0.4.9/worker/"
        val files = listOf(
            "moz3.wasm" to cdn + "05082c31-aee8-4249-9e01-c1865afd7520.wasm",
            "moz2.wasm" to cdn + "ec4aab08-4e7c-4a62-a7a5-ee6874a5d1a2.wasm",
            "moz11.wasm" to cdn + "76ec99f8-04d5-47df-8c52-a3a38d864141.wasm",
            "npm.wasm" to npm + "bergamot-translator-worker.wasm",
            "npm.js" to npm + "bergamot-translator-worker.js"
        )
        line("== DOWNLOADS ==")
        for ((name, url) in files) {
            try {
                download(url, File(dir, name))
            } catch (e: Throwable) {
                line("download $name failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        line("")
        line("== RUNNING IN WEBVIEW (up to 100 seconds) ==")
        val latch = CountDownLatch(1)
        var result = "timeout (no answer in 100s)"
        var holder: WebView? = null
        consoleCount = 0
        ui.post {
            try {
                val wv = WebView(ctx)
                holder = wv
                wv.settings.javaScriptEnabled = true
                wv.addJavascriptInterface(Bridge { json ->
                    result = json
                    latch.countDown()
                }, "Android")
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                        if (m != null && consoleCount++ < 25) line("console: " + short(m.message(), 220))
                        return true
                    }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url ?: return null
                        val p = u.path ?: return null
                        if (u.host == "probe.local" && p.startsWith("/f/")) {
                            val f = File(dir, p.removePrefix("/f/"))
                            if (f.exists()) {
                                val mime = when {
                                    p.endsWith(".js") -> "application/javascript"
                                    p.endsWith(".wasm") -> "application/wasm"
                                    else -> "application/octet-stream"
                                }
                                val resp = WebResourceResponse(mime, null, FileInputStream(f))
                                resp.responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                                return resp
                            }
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        val js = testJs +
                            "probeAll().then(function(r){Android.done(JSON.stringify(r));})" +
                            ".catch(function(e){Android.done(JSON.stringify([{fatal:String(e)}]));});"
                        view?.evaluateJavascript(js, null)
                    }
                }
                wv.loadDataWithBaseURL("https://probe.local/", "<html><body>test</body></html>", "text/html", "utf-8", null)
            } catch (e: Throwable) {
                result = "WebView failed: ${e.javaClass.simpleName}: ${e.message}"
                latch.countDown()
            }
        }
        latch.await(100, TimeUnit.SECONDS)
        ui.post {
            try { holder?.destroy() } catch (e: Throwable) { }
        }

        try {
            val arr = JSONArray(result)
            for (i in 0 until arr.length()) line(short(arr.get(i).toString(), 1300))
        } catch (e: Throwable) {
            line(short(result, 1500))
        }
    }

    private fun startTest() {
        val ctx = uiCtx?.get() ?: return
        Thread {
            synchronized(rep) { rep.setLength(0) }
            try {
                runWasmTest(ctx)
            } catch (e: Throwable) {
                line("TEST ERROR: ${e.javaClass.simpleName}: ${e.message}")
            }
            val text = synchronized(rep) { rep.toString() }
            ui.post {
                try {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("bgwasm", text.take(18000)))
                } catch (e: Throwable) {
                    Log.e("LiveTranslate", "clipboard failed", e)
                }
                showInfo(ctx, "Engine test report (copied to clipboard)", text)
            }
        }.start()
    }

    override fun start(context: Context) {
        appCtx = context
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

        commands.registerCommand("bgwasm", "Test whether the offline translation engine can run here", emptyList()) { _ ->
            if (uiCtx?.get() == null) {
                CommandsAPI.CommandResult("Open a chat with some messages on screen first, then run this again.", null, false)
            } else {
                startTest()
                CommandsAPI.CommandResult("Test running (1 to 2 minutes, downloads about 20 MB). A box opens when it's done and the report is copied to your clipboard.", null, false)
            }
        }

        commands.registerCommand("bgclean", "Delete the engine test downloads", emptyList()) { _ ->
            val ctx = appCtx
            if (ctx == null) {
                CommandsAPI.CommandResult("Not ready.", null, false)
            } else {
                val ok = File(ctx.filesDir, "bgprobe").deleteRecursively()
                CommandsAPI.CommandResult(if (ok) "Test downloads deleted." else "Nothing to delete.", null, false)
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }
}
