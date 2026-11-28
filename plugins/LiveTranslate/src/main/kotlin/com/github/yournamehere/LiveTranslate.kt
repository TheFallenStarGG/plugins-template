package com.github.yournamehere

import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.after
import com.discord.api.commands.ApplicationCommandType
import com.discord.stores.StoreStream
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

// The C++ side (built by the GitHub workflow) registers these exact names.
class NativeBergamot {
    external fun nInit(): Boolean
    external fun nLoad(configPath: String): Boolean
    external fun nUnload()
    external fun nTranslate(input: ByteArray): ByteArray?
    external fun nError(): String
    external fun nInfo(): String
}

@AliucordPlugin
class LiveTranslate : Plugin() {

    private class Job(val mid: Long, val text: String)
    private class Local(val model: String, val lex: String, val vocabs: List<String>)
    private class Pick(val files: List<JSONObject>, val totalSize: Long, val version: String)

    private val libUrl = "https://github.com/TheFallenStarGG/plugins-template/releases/download/bergamot-android-arm64/libbergamot_jni.so"
    private val recordsUrl = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records"
    private val attachBase = "https://firefox-settings-attachments.cdn.mozilla.net/"

    // models bigger than this (all files together) are never picked
    private val maxModelBytes = 100L * 1024L * 1024L
    // bump this to make the plugin re-download models picked by an older rule
    private val pickRule = 2

    private val ui = Handler(Looper.getMainLooper())
    private var engineThread: HandlerThread? = null
    private lateinit var engine: Handler
    private var appCtx: Context? = null
    private var uiCtx: WeakReference<Context>? = null
    private var chatRef: WeakReference<RecyclerView>? = null

    private val cache = LruCache<Long, String>(200)
    private val labels = LruCache<Long, String>(200)
    private val skip = LruCache<Long, Boolean>(600)
    private val pending = HashSet<Long>()
    private val queue = ArrayDeque<Job>()
    private val logLines = ArrayDeque<String>()
    private val failedUntil = HashMap<String, Long>()
    private val supportedCache = HashMap<String, Boolean>()

    private var native: NativeBergamot? = null
    private var libReady = false
    private var libBroken = false
    private var libRetryAt = 0L
    private var libError = ""
    private var loadedLang: String? = null
    private var lastError = ""
    private var index: JSONArray? = null
    private var indexTime = 0L
    private var myId: Long? = null

    private val cBg = 0xFF313338.toInt()
    private val cText = 0xFFDBDEE1.toInt()
    private val cBlurple = 0xFF5865F2.toInt()
    private val cIcon = 0xFFB5BAC1.toInt()

    // ---------- small helpers ----------
    private fun enabled() = settings.getString("enabled", "1") == "1"
    private fun labelOn() = settings.getString("label", "1") == "1"
    private fun cleanOn() = settings.getString("clean", "1") == "1"
    private fun beam(): Int = settings.getString("beam", "2").toIntOrNull()?.coerceIn(1, 4) ?: 2

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun call(o: Any?, name: String): Any? {
        if (o == null) return null
        return try { o.javaClass.getMethod(name).invoke(o) } catch (e: Throwable) { null }
    }

    private fun copyParams(lp: ViewGroup.LayoutParams?): ViewGroup.LayoutParams? {
        if (lp == null) return null
        return try {
            lp.javaClass.getConstructor(lp.javaClass).newInstance(lp) as ViewGroup.LayoutParams
        } catch (e: Throwable) {
            ViewGroup.LayoutParams(lp.width, lp.height)
        }
    }

    private fun log(s: String) {
        Log.d("LiveTranslate", s)
        synchronized(logLines) {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            logLines.addLast("$t $s")
            while (logLines.size > 80) logLines.removeFirst()
        }
    }

    private fun toastUi(msg: String) {
        ui.post {
            try {
                val c = appCtx ?: return@post
                Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
            }
        }
    }

    private fun baseDir(): File = File(appCtx!!.filesDir, "bergamot")
    private fun libFile(): File = File(baseDir(), "libbergamot_jni.so")
    private fun modelDir(lang: String): File = File(File(baseDir(), "models"), lang)

    // ---------- dark info box ----------
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
        tv.textSize = 11f
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

    private fun showResult(title: String, text: String) {
        ui.post {
            val c = uiCtx?.get()
            if (c != null) showInfo(c, title, text)
        }
    }

    // ---------- downloading ----------
    private fun downloadTo(url: String, dest: File, expectSize: Long, expectHash: String?): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".part")
        dest.parentFile?.mkdirs()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode != 200) {
                log("download failed HTTP ${c.responseCode}: ${dest.name}")
                return false
            }
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                    }
                }
            }
            if (expectSize > 0 && tmp.length() != expectSize) {
                log("size mismatch for ${dest.name}: got ${tmp.length()}, expected $expectSize")
                tmp.delete()
                return false
            }
            if (expectHash != null) {
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (!hex.equals(expectHash, true)) {
                    log("hash mismatch for ${dest.name}")
                    tmp.delete()
                    return false
                }
            }
            if (dest.exists()) dest.delete()
            return tmp.renameTo(dest)
        } finally {
            c.disconnect()
        }
    }

    // ---------- the native library ----------
    private fun ensureLib(): Boolean {
        if (libReady) return true
        if (libBroken) return false
        if (System.currentTimeMillis() < libRetryAt) return false
        try {
            if (!Process.is64Bit()) {
                libError = "Aliucord runs as a 32-bit process here, the arm64 library can't load"
                libBroken = true
                log(libError)
                return false
            }
            val f = libFile()
            if (!f.exists() || f.length() < 1000000L) {
                toastUi("LiveTranslate: downloading the translation engine (10 MB, first time only)")
                if (!downloadTo(libUrl, f, 0L, null)) {
                    libError = "engine download failed, will retry"
                    libRetryAt = System.currentTimeMillis() + 120000L
                    return false
                }
                log("engine downloaded: ${f.length()} bytes")
            }
            System.load(f.absolutePath)
            val n = NativeBergamot()
            if (!n.nInit()) {
                libError = "nInit failed: " + n.nError()
                libBroken = true
                log(libError)
                return false
            }
            native = n
            libReady = true
            libError = ""
            log("engine ready: " + n.nInfo())
            return true
        } catch (e: Throwable) {
            libError = "${e.javaClass.simpleName}: ${e.message}"
            libBroken = true
            log("engine load failed: $libError")
            return false
        }
    }

    // ---------- Mozilla's model list ----------
    private fun loadIndex(): JSONArray? {
        val now = System.currentTimeMillis()
        val cached = index
        if (cached != null && now - indexTime < 6L * 3600 * 1000) return cached
        val f = File(baseDir(), "records.json")
        var body: String? = null
        if (f.exists() && now - f.lastModified() < 24L * 3600 * 1000) body = f.readText()
        if (body == null) {
            try {
                val c = URL(recordsUrl).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 15000
                    c.readTimeout = 30000
                    c.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (c.responseCode == 200) {
                        body = c.inputStream.bufferedReader().use { it.readText() }
                        f.parentFile?.mkdirs()
                        f.writeText(body)
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: Throwable) {
                log("model list download failed: ${e.javaClass.simpleName}")
            }
        }
        if (body == null && f.exists()) body = f.readText()
        if (body == null) return null
        val arr = JSONObject(body).getJSONArray("data")
        index = arr
        indexTime = now
        return arr
    }

    private fun versionParts(v: String): List<Int> = v.split(".").map { it.toIntOrNull() ?: 0 }

    private fun isNewer(a: String, b: String): Boolean {
        val x = versionParts(a)
        val y = versionParts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    // newest stable version of the (lang -> en) model that has all its files
    // and whose files add up to at most maxModelBytes
    private fun pickFiles(lang: String, arr: JSONArray): Pick? {
        val groups = HashMap<String, MutableMap<String, MutableList<JSONObject>>>()
        val versionOf = HashMap<String, String>()
        val stable = Regex("[0-9]+(\\.[0-9]+)*")
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optString("fromLang") != lang || r.optString("toLang") != "en") continue
            val v = r.optString("version")
            if (!stable.matches(v)) continue
            val key = v + "|" + r.optString("variant")
            versionOf[key] = v
            groups.getOrPut(key) { HashMap() }.getOrPut(r.optString("fileType")) { mutableListOf() }.add(r)
        }
        var best: Pick? = null
        for ((key, types) in groups) {
            val model = types["model"]?.firstOrNull() ?: continue
            val lex = types["lex"]?.firstOrNull() ?: continue
            val files = ArrayList<JSONObject>()
            files.add(model)
            files.add(lex)
            val single = types["vocab"]?.firstOrNull()
            val sv = types["srcvocab"]?.firstOrNull()
            val tv = types["trgvocab"]?.firstOrNull()
            if (single != null) {
                files.add(single)
            } else if (sv != null && tv != null) {
                files.add(sv)
                files.add(tv)
            } else {
                continue
            }
            var size = 0L
            for (f in files) size += f.optJSONObject("attachment")?.optLong("size") ?: 0L
            if (size > maxModelBytes) continue
            val ver = versionOf[key] ?: continue
            val b = best
            if (b == null || isNewer(ver, b.version) || (ver == b.version && size > b.totalSize)) {
                best = Pick(files, size, ver)
            }
        }
        return best
    }

    private fun supported(lang: String): Boolean {
        val known = supportedCache[lang]
        if (known != null) return known
        val arr = try { loadIndex() } catch (e: Throwable) { null } ?: return true
        val ok = pickFiles(lang, arr) != null
        supportedCache[lang] = ok
        return ok
    }

    // makes sure the model files for this language are on disk, downloading if needed
    private fun ensureModelFiles(lang: String): Local? {
        val dir = modelDir(lang)
        val meta = File(dir, "meta.json")
        if (meta.exists()) {
            try {
                val m = JSONObject(meta.readText())
                if (m.optInt("rule", 0) == pickRule) {
                    val model = File(dir, m.getString("model"))
                    val lex = File(dir, m.getString("lex"))
                    val vs = m.getJSONArray("vocabs")
                    val vocabs = ArrayList<String>()
                    for (i in 0 until vs.length()) vocabs.add(File(dir, vs.getString(i)).absolutePath)
                    if (model.exists() && lex.exists() && vocabs.all { File(it).exists() }) {
                        return Local(model.absolutePath, lex.absolutePath, vocabs)
                    }
                }
            } catch (e: Throwable) {
            }
        }
        val arr = loadIndex()
        if (arr == null) {
            lastError = "can't reach Mozilla's model list (offline?)"
            log(lastError)
            return null
        }
        val pick = pickFiles(lang, arr)
        if (pick == null) {
            lastError = "no usable model for $lang (under ${maxModelBytes / 1048576} MB)"
            log(lastError)
            return null
        }
        dir.deleteRecursively()
        dir.mkdirs()
        toastUi("LiveTranslate: downloading the $lang model (${pick.totalSize / 1048576} MB)")
        var modelName = ""
        var lexName = ""
        val vocabNames = JSONArray()
        for (r in pick.files) {
            val a = r.getJSONObject("attachment")
            val name = a.getString("filename")
            val ok = downloadTo(
                attachBase + a.getString("location"), File(dir, name),
                a.optLong("size"), a.optString("hash").ifEmpty { null }
            )
            if (!ok) {
                dir.deleteRecursively()
                lastError = "model download failed for $lang"
                return null
            }
            when (r.optString("fileType")) {
                "model" -> modelName = name
                "lex" -> lexName = name
                else -> vocabNames.put(name)
            }
        }
        val out = JSONObject()
        out.put("model", modelName)
        out.put("lex", lexName)
        out.put("vocabs", vocabNames)
        out.put("rule", pickRule)
        out.put("version", pick.version)
        meta.writeText(out.toString())
        val vocabs = ArrayList<String>()
        for (i in 0 until vocabNames.length()) vocabs.add(File(dir, vocabNames.getString(i)).absolutePath)
        log("model $lang v${pick.version} downloaded (${pick.totalSize / 1048576} MB)")
        return Local(File(dir, modelName).absolutePath, File(dir, lexName).absolutePath, vocabs)
    }

    // ---------- loading / unloading the one model ----------
    private fun tryLoad(lang: String, loc: Local): Boolean {
        val n = native ?: return false
        val cfg = File(modelDir(lang), "config.yml")
        val vocabLines = if (loc.vocabs.size == 1) listOf(loc.vocabs[0], loc.vocabs[0]) else loc.vocabs
        for (prec in listOf("int8shiftAlphaAll", "int8shiftAll", "int8shift", "int8")) {
            val sb = StringBuilder()
            sb.append("models:\n  - ").append(loc.model).append('\n')
            sb.append("vocabs:\n")
            for (v in vocabLines) sb.append("  - ").append(v).append('\n')
            sb.append("shortlist:\n  - ").append(loc.lex).append("\n  - false\n")
            sb.append("beam-size: ").append(beam()).append('\n')
            sb.append("normalize: 1.0\nword-penalty: 0\nmax-length-break: 128\nmini-batch-words: 1024\n")
            sb.append("mean-length-penalty: 0\nmax-length-factor: 2.0\nskip-cost: true\ncpu-threads: 0\n")
            sb.append("quiet: true\nquiet-translation: true\nworkspace: 64\nalignment: soft\n")
            sb.append("gemm-precision: ").append(prec).append('\n')
            cfg.writeText(sb.toString())
            if (n.nLoad(cfg.absolutePath)) {
                log("model $lang loaded (precision $prec, beam ${beam()})")
                return true
            }
            lastError = n.nError()
            log("load $lang with $prec failed: $lastError")
        }
        return false
    }

    private fun ensureModel(lang: String): Boolean {
        if (loadedLang == lang) return true
        if (!ensureLib()) return false
        val until = failedUntil[lang]
        if (until != null && System.currentTimeMillis() < until) return false
        val n = native ?: return false
        val am = appCtx?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            if (mi.lowMemory) {
                lastError = "device is low on memory, skipped loading $lang"
                log(lastError)
                return false
            }
        }
        val loc = ensureModelFiles(lang)
        if (loc == null) {
            failedUntil[lang] = System.currentTimeMillis() + 600000L
            return false
        }
        val old = loadedLang
        if (old != null) {
            n.nUnload()
            loadedLang = null
            log("unloaded $old to make room for $lang")
        }
        if (!tryLoad(lang, loc)) {
            failedUntil[lang] = System.currentTimeMillis() + 600000L
            return false
        }
        loadedLang = lang
        touchUsed(lang)
        scheduleIdle()
        return true
    }

    private val idleRunnable = Runnable {
        val n = native
        val l = loadedLang
        if (n != null && l != null) {
            n.nUnload()
            loadedLang = null
            log("idle for 60s: unloaded $l")
        }
        cleanupOld()
    }

    private fun scheduleIdle() {
        engine.removeCallbacks(idleRunnable)
        engine.postDelayed(idleRunnable, 60000L)
    }

    private fun touchUsed(lang: String) {
        try {
            val used = JSONObject(settings.getString("lastUsed", "{}"))
            used.put(lang, System.currentTimeMillis())
            settings.setString("lastUsed", used.toString())
        } catch (e: Throwable) {
        }
    }

    // deletes models that haven't been used for 7 days
    private fun cleanupOld() {
        try {
            val dirs = File(baseDir(), "models").listFiles() ?: return
            val used = JSONObject(settings.getString("lastUsed", "{}"))
            val now = System.currentTimeMillis()
            for (d in dirs) {
                val t = used.optLong(d.name, 0L)
                if (t == 0L) {
                    used.put(d.name, now)
                } else if (now - t > 7L * 24 * 3600 * 1000) {
                    d.deleteRecursively()
                    used.remove(d.name)
                    log("deleted unused model ${d.name}")
                }
            }
            settings.setString("lastUsed", used.toString())
        } catch (e: Throwable) {
            log("cleanup failed: ${e.javaClass.simpleName}")
        }
    }

    // ---------- language detection (Android's built-in, no extra RAM) ----------
    private fun cleanForDetect(text: String): String {
        return text
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("<[@#:a][^>]*>"), " ")
            .replace(Regex(":[a-zA-Z0-9_]+:"), " ")
            .trim()
    }

    private fun mapLang(tag: String): String {
        val l = tag.substringBefore('-').lowercase()
        return when (l) {
            "zh" -> if (tag.contains("Hant") || tag.contains("TW") || tag.contains("HK") || tag.contains("MO")) "zh-Hant" else "zh-Hans"
            "iw" -> "he"
            "in" -> "id"
            "fil" -> "tl"
            "no" -> "nb"
            else -> l
        }
    }

    private fun detect(ctx: Context, text: String): String? {
        val cleaned = cleanForDetect(text)
        var letters = 0
        var cjk = false
        for (c in cleaned) {
            if (c.isLetter()) {
                letters++
                val sc = Character.UnicodeScript.of(c.code)
                if (sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA ||
                    sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL
                ) cjk = true
            }
        }
        if (letters < (if (cjk) 2 else 8)) return null
        val tcm = ctx.getSystemService(TextClassificationManager::class.java) ?: return null
        val r = tcm.textClassifier.detectLanguage(TextLanguage.Request.Builder(cleaned).build())
        if (r.localeHypothesisCount == 0) return null
        val loc = r.getLocale(0)
        if (r.getConfidenceScore(loc) < 0.75f) return null
        return mapLang(loc.toLanguageTag())
    }

    // ---------- cleaning text before the model sees it ----------
    private val stretchRegex = Regex("([\\p{IsLatin}\\p{IsCyrillic}])\\1{2,}")
    private val wordRegex = Regex("\\S+")

    private fun isSpecialToken(tok: String): Boolean {
        return tok.contains("://") || tok.startsWith("@") || tok.startsWith("#") ||
            tok.startsWith("<") || (tok.length > 2 && tok.startsWith(":") && tok.endsWith(":"))
    }

    // collapses stretched letters (jaaaa -> ja), capitalizes sentence starts,
    // adds a final period. Links, mentions, #channels and :emoji: are left alone.
    private fun cleanForTranslate(text: String): String {
        val sb = StringBuilder()
        var last = 0
        var capNext = true
        var lastSpecial = false
        for (m in wordRegex.findAll(text)) {
            sb.append(text, last, m.range.first)
            last = m.range.last + 1
            var tok = m.value
            val special = isSpecialToken(tok)
            if (!special) {
                tok = stretchRegex.replace(tok, "\$1")
                if (capNext && tok.isNotEmpty() && tok[0].isLowerCase()) {
                    tok = tok.substring(0, 1).uppercase() + tok.substring(1)
                }
            }
            sb.append(tok)
            capNext = !special && (tok.endsWith(".") || tok.endsWith("!") || tok.endsWith("?"))
            lastSpecial = special
        }
        sb.append(text, last, text.length)
        var out = sb.toString().trim()
        if (out.isNotEmpty() && !lastSpecial) {
            val c = out.last()
            val sc = Character.UnicodeScript.of(c.code)
            if (c.isLetterOrDigit() && (sc == Character.UnicodeScript.LATIN || sc == Character.UnicodeScript.CYRILLIC || c.isDigit())) {
                out += "."
            }
        }
        return out
    }

    // ---------- queue (newest message first, max 12 waiting) ----------
    private fun enqueue(mid: Long, text: String) {
        synchronized(pending) {
            if (!pending.add(mid)) return
        }
        synchronized(queue) {
            queue.addLast(Job(mid, text))
            while (queue.size > 12) {
                val dropped = queue.removeFirst()
                synchronized(pending) { pending.remove(dropped.mid) }
            }
        }
        engine.post { pump() }
    }

    private fun pump() {
        val job = synchronized(queue) { queue.removeLastOrNull() } ?: return
        processJob(job)
    }

    private fun processJob(job: Job) {
        try {
            val ctx = appCtx ?: return
            if (skip[job.mid] != null) return
            val lang = detect(ctx, job.text)
            if (lang == null || lang == "en") {
                skip.put(job.mid, true)
                return
            }
            if (!supported(lang)) {
                skip.put(job.mid, true)
                return
            }
            if (!ensureModel(lang)) return
            val n = native ?: return
            val t0 = SystemClock.elapsedRealtime()
            val src = if (cleanOn()) cleanForTranslate(job.text) else job.text
            val out = n.nTranslate(src.take(2000).toByteArray(Charsets.UTF_8))
            if (out == null) {
                lastError = n.nError()
                log("translate failed: $lastError")
                return
            }
            val res = String(out, Charsets.UTF_8).trim()
            if (res.isEmpty() || res == job.text.trim()) {
                skip.put(job.mid, true)
                return
            }
            cache.put(job.mid, res)
            labels.put(job.mid, lang)
            scheduleIdle()
            log("translated ${job.mid} ($lang) in ${SystemClock.elapsedRealtime() - t0} ms")
            refreshMessage(job.mid)
        } catch (e: Throwable) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            log("job error: $lastError")
        } finally {
            synchronized(pending) { pending.remove(job.mid) }
        }
    }

    // ---------- Google backup (long-press a message) ----------
    private fun googleChunk(text: String): Array<String> {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q=" +
            URLEncoder.encode(text, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            if (code == 429) throw IllegalStateException("Google rate limit reached. Try again later.")
            if (code != 200) throw IllegalStateException("Google translate failed (HTTP $code).")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONArray(body)
            val parts = root.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONArray(i) ?: continue
                sb.append(p.optString(0, ""))
            }
            val src = if (root.length() > 2) root.optString(2, "auto") else "auto"
            return arrayOf(sb.toString(), src)
        } finally {
            conn.disconnect()
        }
    }

    private fun googleTranslate(text: String): Array<String> {
        if (text.length <= 1500) return googleChunk(text)
        val out = StringBuilder()
        var src = "auto"
        val buf = StringBuilder()
        for (line in text.split("\n")) {
            if (buf.isNotEmpty() && buf.length + line.length + 1 > 1500) {
                val r = googleChunk(buf.toString())
                out.append(r[0])
                src = r[1]
                buf.setLength(0)
            }
            buf.append(line).append('\n')
        }
        if (buf.isNotEmpty()) {
            val r = googleChunk(buf.toString())
            out.append(r[0])
            src = r[1]
        }
        return arrayOf(out.toString().trimEnd('\n'), src)
    }

    private fun googleRetranslate(mid: Long, raw: String) {
        if (raw.isBlank()) return
        toastUi("LiveTranslate: translating with Google...")
        Thread {
            try {
                val r = googleTranslate(raw.take(4000))
                if (r[1] == "en" || r[0].isBlank() || r[0].trim() == raw.trim()) {
                    toastUi("Google says this is already English")
                } else {
                    cache.put(mid, r[0])
                    labels.put(mid, r[1] + ", google")
                    skip.remove(mid)
                    log("google translated $mid (${r[1]})")
                    refreshMessage(mid)
                }
            } catch (e: Throwable) {
                log("google failed: ${e.javaClass.simpleName}: ${e.message}")
                toastUi(e.message ?: "Google translate failed")
            }
        }.start()
    }

    // ---------- chat list ----------
    private fun trackChat(root: View) {
        val cur = chatRef?.get()
        if (cur != null && cur.isAttachedToWindow) return
        root.post {
            var p: ViewParent? = root.parent
            while (p != null && p !is RecyclerView) p = p.parent
            if (p is RecyclerView) chatRef = WeakReference(p)
        }
    }

    // redraws just this one message
    private fun refreshMessage(mid: Long) {
        ui.post {
            try {
                val rv = chatRef?.get() ?: return@post
                val adapter = rv.adapter ?: return@post
                val list = call(adapter, "getInternalData") as? List<*>
                var idx = -1
                if (list != null) {
                    for (i in list.indices) {
                        val e = list[i]
                        if (e is MessageEntry && e.message.id == mid) {
                            idx = i
                            break
                        }
                    }
                }
                if (idx >= 0) adapter.notifyItemChanged(idx) else adapter.notifyDataSetChanged()
            } catch (e: Throwable) {
                Log.e("LiveTranslate", "refresh failed", e)
            }
        }
    }

    private fun applyCached(tv: TextView, mid: Long) {
        val t = cache[mid] ?: return
        if (labelOn()) {
            val lang = labels[mid] ?: ""
            val sb = SpannableStringBuilder(t)
            val start = sb.length
            sb.append("  [").append(lang).append("]")
            sb.setSpan(ForegroundColorSpan(0xFF949BA4.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.8f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = sb
        } else {
            tv.text = t
        }
    }

    private fun handleRow(tv: TextView, entry: MessageEntry) {
        val mid = entry.message.id
        if (cache[mid] != null) {
            applyCached(tv, mid)
            return
        }
        if (!enabled()) return
        if (skip[mid] != null) return
        if (myId == null) myId = call(call(StoreStream.getUsers(), "getMe"), "getId") as? Long
        if (entry.message.author.id == myId) {
            skip.put(mid, true)
            return
        }
        val text = tv.text.toString()
        if (text.length < 4) return
        enqueue(mid, text)
    }

    private fun hookRows() {
        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure", Int::class.java, ChatListEntry::class.java
        ) { param ->
            try {
                val entry = param.args[1] as? MessageEntry ?: return@after
                val root = (param.thisObject as RecyclerView.ViewHolder).itemView
                uiCtx = WeakReference(root.context)
                trackChat(root)
                val tv = root.findViewById<TextView>(Utils.getResId("chat_list_adapter_item_text", "id")) ?: return@after
                if (tv.visibility == View.VISIBLE) handleRow(tv, entry)
            } catch (e: Throwable) {
                Log.e("LiveTranslate", "row hook failed", e)
            }
        }
    }

    private fun hookText() {
        val m = WidgetChatListAdapterItemMessage::class.java.declaredMethods
            .firstOrNull { it.name == "processMessageText" } ?: return
        patcher.patch(m, Hook { param ->
            try {
                val tv = param.args.firstOrNull { it is TextView } as? TextView ?: return@Hook
                val entry = param.args.firstOrNull { it is MessageEntry } as? MessageEntry ?: return@Hook
                if (cache[entry.message.id] != null) applyCached(tv, entry.message.id)
            } catch (e: Throwable) {
                Log.e("LiveTranslate", "text hook failed", e)
            }
        })
    }

    // ---------- long-press menu item ----------
    private fun addMenuItem(layout: LinearLayout, label: String, onClick: () -> Unit) {
        val ctx = layout.context
        val wanted = listOf("edit", "reply", "forward", "copy text", "pin", "mark unread")
        var tpl: TextView? = null
        var idx = -1
        for (i in 0 until layout.childCount) {
            val c = layout.getChildAt(i)
            if (c !is TextView) continue
            val s = c.text.toString().trim().lowercase()
            if (s == "copy text") {
                tpl = c
                idx = i
            } else if (tpl == null && s in wanted) {
                tpl = c
                idx = i
            }
        }
        val item = TextView(ctx)
        item.tag = "lt_menu"
        item.text = label
        val iconId = Utils.getResId("ic_locale_24dp", "drawable")
        val icon = if (iconId != 0) ctx.getDrawable(iconId)?.mutate() else null
        val t = tpl
        var insertAt = layout.childCount
        if (t != null) {
            val lp = copyParams(t.layoutParams)
            if (lp != null) item.layoutParams = lp
            item.setPaddingRelative(t.paddingStart, t.paddingTop, t.paddingEnd, t.paddingBottom)
            item.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.textSize)
            item.setTextColor(t.textColors)
            item.typeface = t.typeface
            item.gravity = t.gravity
            item.minHeight = t.minHeight
            item.compoundDrawablePadding = t.compoundDrawablePadding
            val bg = t.background?.constantState?.newDrawable()
            if (bg != null) item.background = bg
            val src = t.compoundDrawablesRelative[0] ?: t.compoundDrawables[0]
            if (icon != null) {
                if (src != null && src.bounds.width() > 0) icon.setBounds(0, 0, src.bounds.width(), src.bounds.height())
                else icon.setBounds(0, 0, dp(ctx, 24), dp(ctx, 24))
                val tint = t.compoundDrawableTintList
                if (tint != null) icon.setTintList(tint) else icon.setTint(cIcon)
                item.setCompoundDrawablesRelative(icon, null, null, null)
            }
            insertAt = if (t.text.toString().trim().lowercase() == "copy text") idx + 1 else idx
        } else {
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            item.setTextColor(cText)
            item.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            item.compoundDrawablePadding = dp(ctx, 16)
            if (icon != null) {
                icon.setBounds(0, 0, dp(ctx, 24), dp(ctx, 24))
                icon.setTint(cIcon)
                item.setCompoundDrawablesRelative(icon, null, null, null)
            }
        }
        item.setOnClickListener { onClick() }
        layout.addView(item, insertAt)
    }

    private fun hookMenu() {
        val cls = Class.forName("com.discord.widgets.chat.list.actions.WidgetChatListActions")
        val m = cls.declaredMethods.first { it.name == "configureUI" }
        patcher.patch(m, Hook { param ->
            try {
                val view = call(param.thisObject, "getView") as? ViewGroup ?: return@Hook
                val layout = view.getChildAt(0) as? LinearLayout ?: return@Hook
                if (layout.findViewWithTag<View>("lt_menu") != null) return@Hook
                val msg = call(param.args[0], "getMessage") ?: return@Hook
                val mid = call(msg, "getId") as? Long ?: return@Hook
                val raw = (call(msg, "getContent") as? String) ?: ""
                if (raw.isBlank()) return@Hook
                val label = if (cache[mid] != null) "Redo with Google" else "Translate with Google"
                addMenuItem(layout, label) {
                    call(param.thisObject, "dismiss")
                    googleRetranslate(mid, raw)
                }
            } catch (e: Throwable) {
                Log.e("LiveTranslate", "menu hook failed", e)
            }
        })
    }

    // ---------- status, test, commands ----------
    private fun dirSize(d: File): Long {
        var s = 0L
        val files = d.listFiles() ?: return 0L
        for (f in files) s += if (f.isDirectory) dirSize(f) else f.length()
        return s
    }

    private fun modelVersion(d: File): String {
        return try { JSONObject(File(d, "meta.json").readText()).optString("version", "?") } catch (e: Throwable) { "?" }
    }

    private fun statusText(): String {
        val sb = StringBuilder()
        sb.append("enabled: ").append(enabled()).append(", language tags: ").append(labelOn()).append('\n')
        sb.append("text cleanup: ").append(cleanOn()).append(", beam size: ").append(beam()).append('\n')
        sb.append("model size cap: ").append(maxModelBytes / 1048576).append(" MB\n")
        sb.append("64-bit process: ").append(Process.is64Bit()).append('\n')
        sb.append("engine library: ").append(
            if (libReady) "loaded" else if (libBroken) "BROKEN: $libError" else if (libError.isNotEmpty()) libError else "not loaded yet (loads on first foreign message)"
        ).append('\n')
        sb.append("loaded model: ").append(loadedLang ?: "none (idle)").append('\n')
        sb.append("native memory in use: ").append(Debug.getNativeHeapAllocatedSize() / 1048576).append(" MB\n")
        val waiting = synchronized(queue) { queue.size }
        sb.append("cache: ").append(cache.size()).append("/200 messages, waiting: ").append(waiting).append('\n')
        val dirs = appCtx?.let { File(baseDir(), "models").listFiles() }
        sb.append("models on disk: ").append(
            if (dirs == null || dirs.isEmpty()) "none" else dirs.joinToString(", ") { "${it.name} v${modelVersion(it)} (${dirSize(it) / 1048576} MB)" }
        ).append('\n')
        sb.append("last error: ").append(if (lastError.isEmpty()) "none" else lastError)
        return sb.toString()
    }

    private fun runTest(text: String) {
        engine.post {
            val sb = StringBuilder()
            try {
                val ctx = appCtx ?: return@post
                val lang = detect(ctx, text)
                sb.append("detected language: ").append(lang ?: "unsure/too short").append('\n')
                if (lang == null || lang == "en") {
                    sb.append("nothing to translate (English or unsure)\n")
                } else if (!supported(lang)) {
                    sb.append("no Mozilla model for $lang (under ${maxModelBytes / 1048576} MB)\n")
                } else if (!ensureModel(lang)) {
                    sb.append("model not ready. error: ").append(lastError).append(" / library: ").append(libError).append('\n')
                } else {
                    val n = native
                    val t0 = SystemClock.elapsedRealtime()
                    val src = if (cleanOn()) cleanForTranslate(text) else text
                    if (src != text) sb.append("cleaned input: ").append(src).append('\n')
                    val out = n?.nTranslate(src.take(2000).toByteArray(Charsets.UTF_8))
                    if (out == null) {
                        sb.append("translate failed: ").append(n?.nError()).append('\n')
                    } else {
                        sb.append("result (").append(SystemClock.elapsedRealtime() - t0).append(" ms):\n")
                        sb.append(String(out, Charsets.UTF_8)).append('\n')
                        scheduleIdle()
                    }
                }
            } catch (e: Throwable) {
                sb.append("error: ${e.javaClass.simpleName}: ${e.message}\n")
            }
            showResult("LiveTranslate test", sb.toString())
        }
    }

    private fun registerCommands() {
        commands.registerCommand("ltstatus", "Show what LiveTranslate is doing", emptyList()) { _ ->
            CommandsAPI.CommandResult(statusText(), null, false)
        }

        commands.registerCommand("ltlog", "Show the LiveTranslate activity log", emptyList()) { _ ->
            val text = synchronized(logLines) { logLines.joinToString("\n") }
            showResult("LiveTranslate log", if (text.isEmpty()) "(nothing yet)" else text)
            CommandsAPI.CommandResult("Opening log...", null, false)
        }

        commands.registerCommand(
            "lttest", "Run the engine on some text right now",
            listOf(Utils.createCommandOption(ApplicationCommandType.STRING, "text", "Text in another language", required = true))
        ) { ctx ->
            runTest(ctx.getRequiredString("text"))
            CommandsAPI.CommandResult("Testing... a box opens with the result (first run downloads the engine and a model).", null, false)
        }

        commands.registerCommand(
            "lton", "Turn automatic translation on or off",
            listOf(Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "enabled", "On or off", required = true))
        ) { ctx ->
            val on = ctx.getBoolOrDefault("enabled", true)
            settings.setString("enabled", if (on) "1" else "0")
            CommandsAPI.CommandResult(if (on) "Automatic translation on." else "Automatic translation off.", null, false)
        }

        commands.registerCommand(
            "ltlabel", "Show or hide the small language tag after translated messages",
            listOf(Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "enabled", "Show tag", required = true))
        ) { ctx ->
            val on = ctx.getBoolOrDefault("enabled", true)
            settings.setString("label", if (on) "1" else "0")
            chatRef?.get()?.adapter?.notifyDataSetChanged()
            CommandsAPI.CommandResult(if (on) "Language tags on." else "Language tags off.", null, false)
        }

        commands.registerCommand(
            "ltclean", "Turn text cleanup before translating on or off",
            listOf(Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "enabled", "On or off", required = true))
        ) { ctx ->
            val on = ctx.getBoolOrDefault("enabled", true)
            settings.setString("clean", if (on) "1" else "0")
            CommandsAPI.CommandResult(
                if (on) "Text cleanup on (applies to messages translated from now on)."
                else "Text cleanup off (applies to messages translated from now on).", null, false
            )
        }

        commands.registerCommand(
            "ltbeam", "Set the search width, 1 to 4 (higher = better but slower, default 2)",
            listOf(Utils.createCommandOption(ApplicationCommandType.STRING, "size", "A number from 1 to 4", required = true))
        ) { ctx ->
            val n = ctx.getRequiredString("size").trim().toIntOrNull()
            if (n == null || n < 1 || n > 4) {
                CommandsAPI.CommandResult("Use a number from 1 to 4.", null, false)
            } else {
                settings.setString("beam", n.toString())
                engine.post {
                    native?.nUnload()
                    loadedLang = null
                    log("beam set to $n, model unloaded so it reloads with the new setting")
                }
                CommandsAPI.CommandResult("Beam size set to $n. The model reloads on the next message.", null, false)
            }
        }

        commands.registerCommand("ltwipe", "Delete downloaded models and the translation cache", emptyList()) { _ ->
            engine.post {
                native?.nUnload()
                loadedLang = null
                File(baseDir(), "models").deleteRecursively()
                File(baseDir(), "records.json").delete()
                index = null
                supportedCache.clear()
                failedUntil.clear()
                synchronized(queue) { queue.clear() }
                synchronized(pending) { pending.clear() }
                cache.evictAll()
                labels.evictAll()
                skip.evictAll()
                lastError = ""
                log("wiped models and cache")
                refreshMessage(-1L)
            }
            CommandsAPI.CommandResult("Models and cache deleted. They download again when needed.", null, false)
        }
    }

    override fun start(context: Context) {
        appCtx = context.applicationContext ?: context
        val t = HandlerThread("LiveTranslateEngine", Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        engineThread = t
        engine = Handler(t.looper)
        try { hookRows() } catch (e: Throwable) { Log.e("LiveTranslate", "rows failed", e) }
        try { hookText() } catch (e: Throwable) { Log.e("LiveTranslate", "text failed", e) }
        try { hookMenu() } catch (e: Throwable) { Log.e("LiveTranslate", "menu failed", e) }
        try { registerCommands() } catch (e: Throwable) { Log.e("LiveTranslate", "commands failed", e) }
        engine.postDelayed({ cleanupOld() }, 30000L)
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
        try {
            engine.removeCallbacksAndMessages(null)
            engine.post {
                native?.nUnload()
                loadedLang = null
            }
            engineThread?.quitSafely()
        } catch (e: Throwable) {
        }
    }
}
