package com.github.yournamehere

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
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
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import org.json.JSONArray
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.WeakHashMap

@AliucordPlugin
class TranslateButtons : Plugin() {

    private class Entry(val text: String, val src: String)

    private val cache = HashMap<Long, Entry>()
    private val showing = HashSet<Long>()
    private val busy = HashSet<Long>()
    private val anchorKind = WeakHashMap<View, Int>()
    private val seenFrags = LinkedHashSet<String>()
    private var chatRef: WeakReference<RecyclerView>? = null
    private var inputRoot: WeakReference<View>? = null
    private var emojiRef: WeakReference<View>? = null
    private var btnRef: WeakReference<View>? = null
    private var lastRow: WeakReference<View>? = null
    private var emojiWidth = 0
    private val ui = Handler(Looper.getMainLooper())

    private val cIcon = 0xFFB5BAC1.toInt()
    private val cBlurple = 0xFF5865F2.toInt()
    private val cText = 0xFFDBDEE1.toInt()
    private val cBg = 0xFF313338.toInt()

    private val langNames = linkedMapOf(
        "english" to "en", "german" to "de", "french" to "fr", "spanish" to "es",
        "italian" to "it", "portuguese" to "pt", "russian" to "ru", "japanese" to "ja",
        "korean" to "ko", "chinese" to "zh-CN", "dutch" to "nl", "polish" to "pl",
        "turkish" to "tr", "swedish" to "sv", "arabic" to "ar", "hindi" to "hi",
        "ukrainian" to "uk"
    )

    // ---------- settings ----------
    private fun inputLang(): String = settings.getString("inputLang", "de")
    private fun msgLang(): String = settings.getString("msgLang", "en")
    private fun hideEmoji(): Boolean = settings.getString("hideEmoji", "1") == "1"

    private fun resolveLang(input: String): String? {
        val s = input.trim().lowercase()
        if (s.isEmpty()) return null
        val byName = langNames[s]
        if (byName != null) return byName
        if (!Regex("[a-z]{2,3}(-[a-z]{2,4})?").matches(s)) return null
        if (!s.contains("-")) return s
        val p = s.split("-")
        return p[0] + "-" + p[1].uppercase()
    }

    private fun langName(code: String): String {
        for ((k, v) in langNames) {
            if (v == code) return k.replaceFirstChar { it.uppercase() }
        }
        return code
    }

    // ---------- helpers ----------
    private fun call(o: Any?, name: String): Any? {
        if (o == null) return null
        return try { o.javaClass.getMethod(name).invoke(o) } catch (e: Throwable) { null }
    }

    private fun safe(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { Log.e("TranslateButtons", "$what failed", e) }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private fun idName(v: View): String? {
        if (v.id == View.NO_ID) return null
        return try { v.resources.getResourceEntryName(v.id) } catch (e: Throwable) { null }
    }

    private fun walk(v: View, fn: (View) -> Unit) {
        fn(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), fn)
        }
    }

    private fun hasClass(o: Any, simple: String): Boolean {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            if (c.name.endsWith(simple)) return true
            c = c.superclass
        }
        return false
    }

    private fun copyParams(lp: ViewGroup.LayoutParams?): ViewGroup.LayoutParams? {
        if (lp == null) return null
        return try {
            lp.javaClass.getConstructor(lp.javaClass).newInstance(lp) as ViewGroup.LayoutParams
        } catch (e: Throwable) {
            ViewGroup.LayoutParams(lp.width, lp.height)
        }
    }

    // ---------- translation (same Google endpoint the old plugin used) ----------
    private fun translateChunk(text: String, to: String): Array<String> {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" +
            URLEncoder.encode(to, "UTF-8") + "&dt=t&q=" + URLEncoder.encode(text, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            if (code == 429) throw IllegalStateException("Translate API rate limit reached. Try again later.")
            if (code != 200) throw IllegalStateException("Translate failed (HTTP $code).")
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

    private fun translateLong(text: String, to: String): Array<String> {
        if (text.length <= 1500) return translateChunk(text, to)
        val out = StringBuilder()
        var src = "auto"
        val buf = StringBuilder()
        for (line in text.split("\n")) {
            if (buf.isNotEmpty() && buf.length + line.length + 1 > 1500) {
                val r = translateChunk(buf.toString(), to)
                out.append(r[0])
                src = r[1]
                buf.setLength(0)
            }
            buf.append(line).append('\n')
        }
        if (buf.isNotEmpty()) {
            val r = translateChunk(buf.toString(), to)
            out.append(r[0])
            src = r[1]
        }
        return arrayOf(out.toString().trimEnd('\n'), src)
    }

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

    // ---------- refresh one message instantly ----------
    private fun trackChat(root: View) {
        val cur = chatRef?.get()
        if (cur != null && cur.isAttachedToWindow) return
        root.post {
            var p: ViewParent? = root.parent
            while (p != null && p !is RecyclerView) p = p.parent
            if (p is RecyclerView) chatRef = WeakReference(p)
        }
    }

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
                Log.e("TranslateButtons", "refresh failed", e)
            }
        }
    }

    // ---------- translate / toggle a message ----------
    private fun runTranslate(ctx: Context, mid: Long, src: String) {
        val cached = cache[mid]
        if (cached != null) {
            if (!showing.remove(mid)) showing.add(mid)
            refreshMessage(mid)
            return
        }
        if (src.isBlank()) return
        if (!busy.add(mid)) return
        val target = msgLang()
        Thread {
            try {
                val r = translateLong(src, target)
                ui.post {
                    busy.remove(mid)
                    if (r[1] == target || r[0].trim() == src.trim()) {
                        toast(ctx, "Already in ${langName(target)}")
                    } else {
                        cache[mid] = Entry(r[0], r[1])
                        showing.add(mid)
                        refreshMessage(mid)
                    }
                }
            } catch (e: Throwable) {
                ui.post {
                    busy.remove(mid)
                    toast(ctx, e.message ?: "Translation failed")
                }
            }
        }.start()
    }
        // ---------- the button on each message ----------
    private fun buildParams(
        group: ViewGroup, anchor: View?, size: Int, endMargin: Int, center: Boolean
    ): ViewGroup.LayoutParams? {
        try {
            val direct = anchor != null && anchor.parent === group && anchor.id != View.NO_ID
            if (hasClass(group, "ConstraintLayout")) {
                val cls = Class.forName("androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams")
                val lp = cls.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .newInstance(size, size) as ViewGroup.MarginLayoutParams
                cls.getField("endToEnd").setInt(lp, 0)
                if (direct && anchor != null) {
                    cls.getField("topToTop").setInt(lp, anchor.id)
                    if (center) cls.getField("bottomToBottom").setInt(lp, anchor.id)
                } else {
                    cls.getField("topToTop").setInt(lp, 0)
                    lp.topMargin = dp(group.context, 4)
                }
                lp.marginEnd = endMargin
                return lp
            }
            if (group is FrameLayout) {
                val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END)
                lp.topMargin = dp(group.context, 4)
                lp.marginEnd = endMargin
                return lp
            }
            if (group is RelativeLayout) {
                val lp = RelativeLayout.LayoutParams(size, size)
                lp.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                lp.addRule(RelativeLayout.ALIGN_PARENT_END)
                lp.topMargin = dp(group.context, 4)
                lp.marginEnd = endMargin
                return lp
            }
        } catch (e: Throwable) {
            Log.e("TranslateButtons", "button params failed", e)
        }
        return null
    }

    private fun addRowButton(group: ViewGroup, anchor: View?, center: Boolean): ImageView? {
        val ctx = group.context
        val iconId = Utils.getResId("ic_locale_24dp", "drawable")
        if (iconId == 0) return null
        val btn = ImageView(ctx)
        btn.tag = "tb_btn"
        btn.setImageDrawable(ctx.getDrawable(iconId)?.mutate())
        btn.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp(ctx, 5)
        btn.setPadding(pad, pad, pad, pad)
        btn.contentDescription = "Translate"
        val lp = buildParams(group, anchor, dp(ctx, 28), dp(ctx, 34), center) ?: return null
        btn.layoutParams = lp
        group.addView(btn)
        return btn
    }

    private fun applyRow(root: View, entry: MessageEntry) {
        val mid = entry.message.id
        lastRow = WeakReference(root)
        val content = root.findViewById<TextView>(Utils.getResId("chat_list_adapter_item_text", "id"))
        val e = cache[mid]
        if (content != null && e != null && showing.contains(mid)) content.text = e.text

        val group = root as? ViewGroup
        var old = root.findViewWithTag<View>("tb_btn")
        val nameTv = root.findViewById<View>(Utils.getResId("chat_list_adapter_item_text_name", "id"))
        val header = nameTv != null && nameTv.visibility == View.VISIBLE
        val kind = if (header) 0 else 1
        val hasText = content != null && content.visibility == View.VISIBLE && content.text.isNotEmpty()

        if (group == null || !hasText) {
            if (old != null) (old.parent as? ViewGroup)?.removeView(old)
            return
        }
        if (old != null && anchorKind[old] != kind) {
            (old.parent as? ViewGroup)?.removeView(old)
            old = null
        }
        var made = old as? ImageView
        if (made == null) {
            made = addRowButton(group, if (header) nameTv else content, header) ?: return
            anchorKind[made] = kind
        }
        val button: ImageView = made
        button.setColorFilter(if (showing.contains(mid)) cBlurple else cIcon)
        val c = content
        button.setOnClickListener { runTranslate(button.context, mid, c?.text?.toString() ?: "") }
    }

    // ---------- long-press menu item (backup way to translate) ----------
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
        item.tag = "tb_menu"
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

    // ---------- the text-bar button ----------
    private fun findEmoji(root: View): View? {
        var byId: View? = null
        var byDesc: View? = null
        walk(root) { v ->
            if (v is ImageView) {
                val n = idName(v)?.lowercase() ?: ""
                val d = v.contentDescription?.toString()?.lowercase() ?: ""
                if (byId == null && n.contains("emoji")) byId = v
                if (byDesc == null && (d.contains("emoji") || d.contains("sticker"))) byDesc = v
            }
        }
        return byId ?: byDesc
    }

    private fun applyEmojiState() {
        val emoji = emojiRef?.get() ?: return
        val btn = btnRef?.get() ?: return
        val hide = hideEmoji()
        val flows = emoji.parent is LinearLayout
        emoji.visibility = if (hide) (if (flows) View.GONE else View.INVISIBLE) else View.VISIBLE
        val shift = if (emojiWidth > 0) emojiWidth else dp(btn.context, 36)
        btn.translationX = if (hide || flows) 0f else -shift.toFloat()
    }

    private fun translateInput(input: EditText) {
        val ctx = input.context
        val text = input.text.toString()
        if (text.isBlank()) {
            toast(ctx, "Type something first")
            return
        }
        val target = inputLang()
        Thread {
            try {
                val r = translateLong(text, target)
                ui.post {
                    input.setText(r[0])
                    input.setSelection(input.text.length)
                }
            } catch (e: Throwable) {
                ui.post { toast(ctx, e.message ?: "Translation failed") }
            }
        }.start()
    }

    private fun setupInput(root: View) {
        inputRoot = WeakReference(root)
        val existing = root.findViewWithTag<View>("tb_input")
        if (existing != null) {
            btnRef = WeakReference(existing)
            applyEmojiState()
            return
        }
        var found: EditText? = null
        walk(root) { v -> if (found == null && v is EditText) found = v }
        val input = found ?: return
        val emoji = findEmoji(root) ?: return
        val parent = emoji.parent as? ViewGroup ?: return
        val ctx = root.context
        val iconId = Utils.getResId("ic_locale_24dp", "drawable")
        if (iconId == 0) return

        val btn = ImageView(ctx)
        btn.tag = "tb_input"
        btn.setImageDrawable(ctx.getDrawable(iconId)?.mutate())
        btn.setColorFilter(cIcon)
        btn.contentDescription = "Translate"
        if (emoji is ImageView) btn.scaleType = emoji.scaleType
        btn.setPadding(emoji.paddingLeft, emoji.paddingTop, emoji.paddingRight, emoji.paddingBottom)
        val bg = emoji.background?.constantState?.newDrawable()
        if (bg != null) btn.background = bg
        val lp = copyParams(emoji.layoutParams)
        if (lp != null) btn.layoutParams = lp
        btn.setOnClickListener { translateInput(input) }

        emojiWidth = emoji.width
        parent.addView(btn, parent.indexOfChild(emoji) + 1)
        emojiRef = WeakReference(emoji)
        btnRef = WeakReference(btn)
        emoji.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (hideEmoji() && v.visibility == View.VISIBLE) v.post { applyEmojiState() }
        }
        applyEmojiState()
    }

    // ---------- debug ----------
    private fun dump(v: View, depth: Int, sb: StringBuilder) {
        if (depth > 8 || sb.length > 14000) return
        sb.append("  ".repeat(depth)).append(v.javaClass.simpleName)
        val n = idName(v)
        if (n != null) sb.append(" #").append(n)
        if (v.visibility != View.VISIBLE) sb.append(" [hidden]")
        val d = v.contentDescription
        if (d != null) sb.append(" desc=\"").append(d.toString().take(20)).append("\"")
        sb.append('\n')
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) dump(v.getChildAt(i), depth + 1, sb)
        }
    }

    private fun buildDump(): String {
        val sb = StringBuilder()
        val row = lastRow?.get()
        sb.append("Message row root: ").append(row?.javaClass?.name ?: "none yet").append('\n')
        sb.append("Emoji button found: ").append(emojiRef?.get() != null).append('\n')
        sb.append("Fragments seen: ").append(seenFrags.joinToString(", ")).append("\n\n")
        val r = inputRoot?.get()
        if (r == null) sb.append("Chat input not seen yet. Open a channel first.") else dump(r, 0, sb)
        return sb.toString()
    }

    // ---------- hooks ----------
    private fun hookRows() {
        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure", Int::class.java, ChatListEntry::class.java
        ) { param ->
            try {
                val entry = param.args[1] as? MessageEntry ?: return@after
                val root = (param.thisObject as RecyclerView.ViewHolder).itemView
                trackChat(root)
                applyRow(root, entry)
            } catch (e: Throwable) {
                Log.e("TranslateButtons", "row hook failed", e)
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
                val id = entry.message.id
                val e = cache[id]
                if (e != null && showing.contains(id)) tv.text = e.text
            } catch (e: Throwable) {
                Log.e("TranslateButtons", "text hook failed", e)
            }
        })
    }

    private fun hookMenu() {
        val cls = Class.forName("com.discord.widgets.chat.list.actions.WidgetChatListActions")
        val m = cls.declaredMethods.first { it.name == "configureUI" }
        patcher.patch(m, Hook { param ->
            try {
                val view = call(param.thisObject, "getView") as? ViewGroup ?: return@Hook
                val layout = view.getChildAt(0) as? LinearLayout ?: return@Hook
                if (layout.findViewWithTag<View>("tb_menu") != null) return@Hook
                val msg = call(param.args[0], "getMessage") ?: return@Hook
                val mid = call(msg, "getId") as? Long ?: return@Hook
                val raw = (call(msg, "getContent") as? String) ?: ""
                if (raw.isBlank()) return@Hook
                val label = if (showing.contains(mid)) "Show original" else "Translate to ${langName(msgLang())}"
                addMenuItem(layout, label) {
                    call(param.thisObject, "dismiss")
                    runTranslate(layout.context, mid, raw)
                }
            } catch (e: Throwable) {
                Log.e("TranslateButtons", "menu hook failed", e)
            }
        })
    }

    private fun hookInput() {
        val m = Class.forName("androidx.fragment.app.Fragment").getDeclaredMethod("onResume")
        patcher.patch(m, Hook { param ->
            try {
                val frag = param.thisObject ?: return@Hook
                val name = frag.javaClass.name
                if (name.contains("Input", true) || name.contains("Chat", true)) seenFrags.add(name.substringAfterLast('.'))
                if (name.endsWith("WidgetChatInput")) {
                    val v = call(frag, "getView") as? View ?: return@Hook
                    v.post { safe("setup input") { setupInput(v) } }
                }
            } catch (e: Throwable) {
                Log.e("TranslateButtons", "input hook failed", e)
            }
        })
    }

    private fun registerCommands() {
        commands.registerCommand(
            "tblang", "Set the language the text-bar button translates to (default German)",
            listOf(Utils.createCommandOption(ApplicationCommandType.STRING, "language", "Code or name, e.g. de, french", required = true))
        ) { ctx ->
            val code = resolveLang(ctx.getRequiredString("language"))
            if (code == null) {
                CommandsAPI.CommandResult("Unknown language. Use a code like de, fr, es, ja or a name like german.", null, false)
            } else {
                settings.setString("inputLang", code)
                CommandsAPI.CommandResult("Text-bar language set to ${langName(code)} ($code).", null, false)
            }
        }

        commands.registerCommand(
            "tbmsglang", "Set the language the message buttons translate to (default English)",
            listOf(Utils.createCommandOption(ApplicationCommandType.STRING, "language", "Code or name, e.g. en, spanish", required = true))
        ) { ctx ->
            val code = resolveLang(ctx.getRequiredString("language"))
            if (code == null) {
                CommandsAPI.CommandResult("Unknown language. Use a code like en, fr, es, ja or a name like spanish.", null, false)
            } else {
                settings.setString("msgLang", code)
                cache.clear()
                showing.clear()
                chatRef?.get()?.adapter?.notifyDataSetChanged()
                CommandsAPI.CommandResult("Message language set to ${langName(code)} ($code).", null, false)
            }
        }

        commands.registerCommand(
            "tbemoji", "Show or hide the emoji button next to the translate button",
            listOf(Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "show", "Show the emoji button", required = true))
        ) { ctx ->
            val show = ctx.getBoolOrDefault("show", false)
            settings.setString("hideEmoji", if (show) "0" else "1")
            ui.post { applyEmojiState() }
            CommandsAPI.CommandResult(if (show) "Emoji button shown." else "Emoji button hidden.", null, false)
        }

        commands.registerCommand("tbdump", "Debug: dump the chat input layout", emptyList()) { _ ->
            ui.post {
                val ctx = inputRoot?.get()?.context ?: chatRef?.get()?.context
                if (ctx != null) showInfo(ctx, "Chat input layout", buildDump())
            }
            CommandsAPI.CommandResult("Opening dump...", null, false)
        }
    }

    override fun start(context: Context) {
        safe("rows") { hookRows() }
        safe("text") { hookText() }
        safe("menu") { hookMenu() }
        safe("input") { hookInput() }
        safe("commands") { registerCommands() }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }
}
