package com.github.yournamehere

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.after
import com.discord.api.commands.ApplicationCommandType
import com.discord.utilities.icon.IconUtils
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.WeakHashMap

@AliucordPlugin
class LocalEditor : Plugin() {

    private class Orig(val tint: ColorStateList?, val colors: ColorStateList?)
    private class InlineInfo(val content: TextView, val oldMin: Int)
    private class Field(
        val key: String, val label: String, val kind: Int, val value: String,
        val options: List<String> = emptyList()
    )

    private val origs = WeakHashMap<View, Orig>()
    private val hiddenByUs = WeakHashMap<View, MutableList<View>>()
    private val inlineInfo = WeakHashMap<View, InlineInfo>()
    private val penciled = WeakHashMap<View, Boolean>()
    private val decoUrls = WeakHashMap<View, String>()
    private val origNames = HashMap<String, String>()
    private var chatRef: WeakReference<RecyclerView>? = null
    private var editingMid: String? = null
    private var draft = ""
    private val ui = Handler(Looper.getMainLooper())

    private val botTagId = "chat_list_adapter_item_text_tag"
    private val guildKeys = listOf("clan", "guild_tag", "guildtag", "primary_guild", "server_tag", "guild_badge")

    private val cText = 0xFFDBDEE1.toInt()
    private val cMuted = 0xFF949BA4.toInt()
    private val cBg = 0xFF313338.toInt()
    private val cDark = 0xFF1E1F22.toInt()
    private val cBlurple = 0xFF5865F2.toInt()
    private val cRed = 0xFFDA373C.toInt()
    private val cGray = 0xFF4E5058.toInt()

    // ---------- storage ----------
    private fun loadObj(key: String) = JSONObject(settings.getString(key, "{}"))

    private fun userCfg(uid: String): JSONObject? = loadObj("users").optJSONObject(uid)

    private fun saveUser(uid: String, cfg: JSONObject?) {
        val all = loadObj("users")
        if (cfg == null || cfg.length() == 0) all.remove(uid) else all.put(uid, cfg)
        settings.setString("users", all.toString())
    }

    private fun setMsgField(mid: String, key: String, value: String?) {
        val all = loadObj("msgs")
        val o = all.optJSONObject(mid) ?: JSONObject()
        if (value == null || value.isEmpty()) o.remove(key) else o.put(key, value)
        if (o.length() == 0) all.remove(mid) else all.put(mid, o)
        settings.setString("msgs", all.toString())
    }

    // ---------- helpers ----------
    private fun call(o: Any?, name: String): Any? {
        if (o == null) return null
        return try { o.javaClass.getMethod(name).invoke(o) } catch (e: Throwable) { null }
    }

    private fun safe(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { Log.e("LocalEditor", "$what failed", e) }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun idName(v: View): String? {
        if (v.id == View.NO_ID) return null
        return try { v.resources.getResourceEntryName(v.id) } catch (e: Throwable) { null }
    }

    private fun refreshChat() {
        ui.post { chatRef?.get()?.adapter?.notifyDataSetChanged() }
    }

    private fun walk(v: View, fn: (View) -> Unit) {
        fn(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), fn)
        }
    }

    private fun findById(v: View, name: String): View? {
        if (idName(v) == name) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findById(v.getChildAt(i), name)
                if (r != null) return r
            }
        }
        return null
    }

    private fun firstTextView(v: View): TextView? {
        if (v is TextView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = firstTextView(v.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    private fun copyParams(lp: ViewGroup.LayoutParams?): ViewGroup.LayoutParams? {
        if (lp == null) return null
        return try {
            lp.javaClass.getConstructor(lp.javaClass).newInstance(lp) as ViewGroup.LayoutParams
        } catch (e: Throwable) {
            ViewGroup.LayoutParams(lp.width, lp.height)
        }
    }

    private fun hide(root: View, v: View) {
        v.visibility = View.GONE
        hiddenByUs.getOrPut(root) { mutableListOf() }.add(v)
    }

    private fun hideKeyboard(v: View) {
        try {
            val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        } catch (e: Throwable) {
        }
    }

    // ---------- dark UI building blocks ----------
    private fun rounded(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radius.toFloat()
        return g
    }

    private fun makeLabel(ctx: Context, text: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(ctx)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun makeButton(ctx: Context, text: String, color: Int, onClick: () -> Unit): TextView {
        val b = makeLabel(ctx, text, 14f, Color.WHITE, true)
        b.gravity = Gravity.CENTER
        b.background = rounded(color, dp(ctx, 8))
        val p = dp(ctx, 10)
        b.setPadding(p * 2, p, p * 2, p)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun darkEdit(ctx: Context, value: String, hint: String): EditText {
        val et = EditText(ctx)
        et.setText(value)
        et.hint = hint
        et.setTextColor(cText)
        et.setHintTextColor(cMuted)
        et.background = rounded(cDark, dp(ctx, 8))
        val p = dp(ctx, 10)
        et.setPadding(p, p, p, p)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        return et
    }

    private fun openDialog(ctx: Context, dlg: Dialog, root: View, heightFraction: Float) {
        dlg.setContentView(root)
        dlg.setCanceledOnTouchOutside(true)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
        val w = (ctx.resources.displayMetrics.widthPixels * 0.92f).toInt()
        val h = if (heightFraction > 0f) (ctx.resources.displayMetrics.heightPixels * heightFraction).toInt()
        else ViewGroup.LayoutParams.WRAP_CONTENT
        dlg.window?.setLayout(w, h)
    }

    // kind 0 = text box, 1 = on/off, 2 = pick one
    private fun showForm(
        ctx: Context, title: String, fields: List<Field>,
        onSave: (Map<String, String>) -> Unit, onReset: (() -> Unit)?
    ) {
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.background = rounded(cBg, dp(ctx, 14))
        val pad = dp(ctx, 16)
        root.setPadding(pad, pad, pad, pad)
        root.addView(makeLabel(ctx, title, 18f, cText, true))

        val body = LinearLayout(ctx)
        body.orientation = LinearLayout.VERTICAL
        val getters = LinkedHashMap<String, () -> String>()

        for (f in fields) {
            when (f.kind) {
                1 -> {
                    val cb = CheckBox(ctx)
                    cb.text = f.label
                    cb.setTextColor(cText)
                    cb.buttonTintList = ColorStateList.valueOf(cBlurple)
                    cb.isChecked = f.value == "1"
                    cb.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
                    body.addView(cb)
                    getters[f.key] = { if (cb.isChecked) "1" else "0" }
                }
                2 -> {
                    val cap = makeLabel(ctx, f.label, 12f, cMuted, false)
                    cap.setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
                    body.addView(cap)
                    var sel = f.value
                    val chips = ArrayList<TextView>()
                    val rowv = LinearLayout(ctx)
                    fun restyle() {
                        for (c in chips) {
                            c.background = rounded(if (c.text.toString() == sel) cBlurple else cDark, dp(ctx, 14))
                        }
                    }
                    for (o in f.options) {
                        val chip = makeLabel(ctx, o, 13f, cText, false)
                        val cp = dp(ctx, 10)
                        chip.setPadding(cp * 2, cp, cp * 2, cp)
                        val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        clp.setMargins(0, 0, dp(ctx, 6), 0)
                        chip.layoutParams = clp
                        chip.setOnClickListener {
                            sel = if (sel == o) "" else o
                            restyle()
                        }
                        chips.add(chip)
                        rowv.addView(chip)
                    }
                    restyle()
                    body.addView(rowv)
                    getters[f.key] = { sel }
                }
                else -> {
                    val cap = makeLabel(ctx, f.label, 12f, cMuted, false)
                    cap.setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
                    body.addView(cap)
                    val et = darkEdit(ctx, f.value, "")
                    body.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    getters[f.key] = { et.text.toString() }
                }
            }
        }

        val sv = ScrollView(ctx)
        sv.addView(body)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val btns = LinearLayout(ctx)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.setPadding(0, dp(ctx, 12), 0, 0)
        btns.addView(makeButton(ctx, "Cancel", cGray) { dlg.dismiss() })
        if (onReset != null) {
            btns.addView(makeButton(ctx, "Reset", cRed) {
                dlg.dismiss()
                onReset()
            })
        }
        btns.addView(makeButton(ctx, "Save", cBlurple) {
            val out = LinkedHashMap<String, String>()
            for ((k, g) in getters) out[k] = g()
            dlg.dismiss()
            onSave(out)
        })
        root.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        openDialog(ctx, dlg, root, 0f)
    }

    private fun showInfo(ctx: Context, title: String, text: String) {
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.background = rounded(cBg, dp(ctx, 14))
        val pad = dp(ctx, 16)
        root.setPadding(pad, pad, pad, pad)
        root.addView(makeLabel(ctx, title, 18f, cText, true))
        val tv = makeLabel(ctx, text, 10f, cText, false)
        tv.typeface = Typeface.MONOSPACE
        tv.setTextIsSelectable(true)
        val sv = ScrollView(ctx)
        sv.addView(tv)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val close = makeButton(ctx, "Close", cBlurple) { dlg.dismiss() }
        close.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(close)
        openDialog(ctx, dlg, root, 0.85f)
    }

    // ---------- forms ----------
    private fun showUserForm(ctx: Context, uid: String, displayName: String) {
        val c = userCfg(uid) ?: JSONObject()
        fun tog(k: String) = if (c.optBoolean(k, false)) "1" else "0"
        showForm(
            ctx, "Edit $displayName",
            listOf(
                Field("name", "Display name", 0, c.optString("name", "")),
                Field("tag", "Tag text (any length, symbols OK)", 0, c.optString("tag", "")),
                Field("official", "Official style (blue check tag, replaces server tag)", 1, tog("official")),
                Field("hideTag", "Hide their existing server tag", 1, tog("hideTag")),
                Field("hideBadge", "Hide the server tag icon", 1, tog("hideBadge")),
                Field("avatar", "Avatar image URL (direct link)", 0, c.optString("avatar", "")),
                Field("deco", "Avatar decoration image URL (type hide to remove it)", 0, c.optString("deco", "")),
                Field("status", "Status (profile only)", 2, c.optString("status", ""), listOf("online", "idle", "dnd", "offline")),
                Field("statusText", "Status text (profile only)", 0, c.optString("statusText", ""))
            ),
            { out ->
                val o = JSONObject()
                for (k in listOf("name", "tag", "avatar", "deco", "statusText")) {
                    val v = (out[k] ?: "").trim()
                    if (v.isNotEmpty()) o.put(k, v)
                }
                for (k in listOf("official", "hideTag", "hideBadge")) {
                    if (out[k] == "1") o.put(k, true)
                }
                val st = out["status"] ?: ""
                if (st.isNotEmpty()) o.put("status", st)
                saveUser(uid, o)
                refreshChat()
            },
            {
                saveUser(uid, null)
                refreshChat()
            }
        )
    }

    private fun showSingle(ctx: Context, title: String, label: String, initial: String, mid: String, field: String) {
        showForm(
            ctx, title, listOf(Field("v", label, 0, initial)),
            { out ->
                setMsgField(mid, field, (out["v"] ?: "").trim())
                refreshChat()
            },
            {
                setMsgField(mid, field, null)
                refreshChat()
            }
        )
    }

    // ---------- pencils ----------
    private fun pencil(ctx: Context, sizeDp: Int): Drawable? {
        for (n in listOf("ic_edit_24dp", "ic_edit_white_24dp", "ic_pencil_24dp")) {
            val id = Utils.getResId(n, "drawable")
            if (id != 0) {
                val d = ctx.getDrawable(id)?.mutate() ?: continue
                d.setTint(cMuted)
                val s = dp(ctx, sizeDp)
                d.setBounds(0, 0, s, s)
                return d
            }
        }
        return null
    }

    private fun setPencil(tv: TextView?, on: Boolean, action: () -> Unit) {
        if (tv == null) return
        if (!on) {
            if (penciled.remove(tv) != null) {
                tv.setCompoundDrawablesRelative(null, null, null, null)
                tv.setOnTouchListener(null)
            }
            return
        }
        if (tv.compoundDrawablesRelative[2] != null && !penciled.containsKey(tv)) return
        val d = pencil(tv.context, 14) ?: return
        tv.setCompoundDrawablesRelative(tv.compoundDrawablesRelative[0], null, d, null)
        tv.compoundDrawablePadding = dp(tv.context, 6)
        penciled[tv] = true
        var downOnPencil = false
        tv.setOnTouchListener { _, ev ->
            val zone = dp(tv.context, 30)
            val hit = ev.x >= tv.width - tv.paddingEnd - zone
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downOnPencil = hit
                    downOnPencil
                }
                MotionEvent.ACTION_UP -> {
                    if (downOnPencil) {
                        downOnPencil = false
                        action()
                        true
                    } else false
                }
                MotionEvent.ACTION_CANCEL -> {
                    downOnPencil = false
                    false
                }
                else -> downOnPencil
            }
        }
    }

    // ---------- server tag / bot tag ----------
    private fun findGuildTag(v: View): View? {
        val n = idName(v)
        if (n != null && n != botTagId && guildKeys.any { n.contains(it) }) {
            return if (v.visibility == View.VISIBLE) v else null
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findGuildTag(v.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    private fun hideImages(root: View, v: View) {
        if (v is ImageView && v.visibility == View.VISIBLE) { hide(root, v); return }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) hideImages(root, v.getChildAt(i))
        }
    }

    private fun setGuildText(root: View, g: View, text: String, noBadge: Boolean) {
        val tv = firstTextView(g) ?: return
        tv.text = text
        tv.filters = arrayOf<InputFilter>()
        tv.ellipsize = null
        tv.requestLayout()
        if (noBadge) {
            if (g is ViewGroup) hideImages(root, g)
            else tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    private fun styleBotTag(tv: TextView, text: String, official: Boolean) {
        if (!origs.containsKey(tv)) origs[tv] = Orig(tv.backgroundTintList, tv.textColors)
        tv.visibility = View.VISIBLE
        tv.text = text
        tv.filters = arrayOf<InputFilter>()
        tv.ellipsize = null
        tv.setTextColor(Color.WHITE)
        if (official) {
            tv.backgroundTintList = ColorStateList.valueOf(cBlurple)
            for (name in listOf("ic_verified_badge", "ic_verified_10dp", "ic_check_16dp")) {
                val res = Utils.getResId(name, "drawable")
                if (res != 0) {
                    tv.setCompoundDrawablesWithIntrinsicBounds(res, 0, 0, 0)
                    break
                }
            }
        } else {
            tv.backgroundTintList = ColorStateList.valueOf(cGray)
            tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    private fun resetBotTag(tv: TextView) {
        val o = origs.remove(tv) ?: return
        tv.backgroundTintList = o.tint
        if (o.colors != null) tv.setTextColor(o.colors)
        tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
    }

    private fun applyTag(root: View, uc: JSONObject?) {
        val botTag = root.findViewById<TextView>(Utils.getResId(botTagId, "id"))
        val text = uc?.optString("tag", "") ?: ""
        val guild = findGuildTag(root)
        if (uc == null || text.isEmpty()) {
            if (botTag != null) resetBotTag(botTag)
            if (uc != null && uc.optBoolean("hideTag", false) && guild != null) hide(root, guild)
            return
        }
        val official = uc.optBoolean("official", false)
        val noBadge = uc.optBoolean("hideBadge", false)
        if (guild != null && !official) {
            setGuildText(root, guild, text, noBadge)
            if (botTag != null) resetBotTag(botTag)
        } else {
            if (guild != null && (official || uc.optBoolean("hideTag", false))) hide(root, guild)
            if (botTag != null) styleBotTag(botTag, text, official)
        }
    }

    // ---------- avatar + decoration ----------
    private fun newDrawee(ctx: Context): ImageView? {
        return try {
            Class.forName("com.facebook.drawee.view.SimpleDraweeView")
                .getConstructor(Context::class.java).newInstance(ctx) as ImageView
        } catch (e: Throwable) {
            null
        }
    }

    private fun setDraweeUrl(v: View, url: String) {
        try {
            v.javaClass.getMethod("setImageURI", String::class.java).invoke(v, url)
        } catch (e: Throwable) {
        }
    }

    private fun applyAvatar(root: View, avatar: View?, uc: JSONObject?) {
        val url = uc?.optString("avatar", "") ?: ""
        if (avatar != null && url.isNotEmpty()) setDraweeUrl(avatar, url)

        val deco = uc?.optString("deco", "") ?: ""
        val old = root.findViewWithTag<View>("le_deco")
        if (deco.isEmpty()) {
            if (old != null) (old.parent as? ViewGroup)?.removeView(old)
            return
        }
        walk(root) { v ->
            val n = idName(v)?.lowercase()
            if (n != null && n.contains("avatar") && n.contains("decoration") &&
                v.visibility == View.VISIBLE && v.tag != "le_deco"
            ) hide(root, v)
        }
        if (deco == "hide" || avatar == null) {
            if (old != null) (old.parent as? ViewGroup)?.removeView(old)
            return
        }
        val parent = avatar.parent as? ViewGroup ?: return
        var ov = old as? ImageView
        if (ov == null) {
            ov = newDrawee(avatar.context) ?: return
            ov.tag = "le_deco"
            ov.isClickable = false
            val lp = copyParams(avatar.layoutParams)
            if (lp != null) ov.layoutParams = lp
            parent.addView(ov, parent.indexOfChild(avatar) + 1)
        }
        ov.scaleX = 1.2f
        ov.scaleY = 1.2f
        if (decoUrls[ov] != deco) {
            setDraweeUrl(ov, deco)
            decoUrls[ov] = deco
        }
    }

    // ---------- reply preview ----------
    private fun findReplyText(v: View): TextView? {
        val n = idName(v)?.lowercase()
        if (v is TextView && n != null && n.contains("reply") &&
            listOf("name", "time", "avatar", "icon", "tag", "label").none { n.contains(it) } &&
            v.visibility == View.VISIBLE
        ) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findReplyText(v.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    // ---------- mentions follow renamed users ----------
    private fun renameMentions(tv: TextView) {
        val all = loadObj("users")
        val keys = all.keys()
        var sb: SpannableStringBuilder? = null
        while (keys.hasNext()) {
            val id = keys.next()
            val newName = all.optJSONObject(id)?.optString("name", "") ?: ""
            val old = origNames[id] ?: continue
            if (newName.isEmpty() || newName == old) continue
            val needle = "@$old"
            val cur = sb ?: SpannableStringBuilder(tv.text)
            var i = cur.indexOf(needle)
            if (i < 0) continue
            while (i >= 0) {
                cur.replace(i, i + needle.length, "@$newName")
                i = cur.indexOf(needle, i + newName.length + 1)
            }
            sb = cur
        }
        if (sb != null) tv.text = sb
    }

    // ---------- inline message editor ----------
    private fun startInline(mid: String, current: String) {
        val ov = loadObj("msgs").optJSONObject(mid)?.optString("content", "") ?: ""
        draft = if (ov.isNotEmpty()) ov else current
        editingMid = mid
        refreshChat()
    }

    private fun removeInline(root: View) {
        val box = root.findViewWithTag<View>("le_inline")
        if (box != null) (box.parent as? ViewGroup)?.removeView(box)
        val info = inlineInfo.remove(root)
        if (info != null) {
            info.content.visibility = View.VISIBLE
            info.content.minHeight = info.oldMin
        }
    }

    private fun smallButton(ctx: Context, text: String, color: Int, onClick: () -> Unit): TextView {
        val b = makeButton(ctx, text, color, onClick)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(ctx, 6), dp(ctx, 6), 0, 0)
        b.layoutParams = lp
        return b
    }

    private fun showInline(root: View, content: TextView, mid: String) {
        try {
            val parent = content.parent as? ViewGroup ?: throw IllegalStateException("no parent")
            val constraint = parent.javaClass.name.contains("ConstraintLayout")
            if (root.findViewWithTag<View>("le_inline") != null) {
                content.visibility = if (constraint) View.INVISIBLE else View.GONE
                return
            }
            val ctx = root.context
            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.VERTICAL
            box.tag = "le_inline"

            val et = EditText(ctx)
            et.setText(draft)
            et.setTextColor(cText)
            et.setHintTextColor(cMuted)
            et.setTextSize(TypedValue.COMPLEX_UNIT_PX, content.textSize)
            et.background = rounded(cDark, dp(ctx, 6))
            val p = dp(ctx, 8)
            et.setPadding(p, p, p, p)
            et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            et.gravity = Gravity.TOP or Gravity.START
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { draft = s?.toString() ?: "" }
            })
            box.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.END
            row.addView(smallButton(ctx, "Cancel", cGray) {
                editingMid = null
                hideKeyboard(et)
                refreshChat()
            })
            row.addView(smallButton(ctx, "Reset", cRed) {
                setMsgField(mid, "content", null)
                editingMid = null
                hideKeyboard(et)
                refreshChat()
            })
            row.addView(smallButton(ctx, "Save", cBlurple) {
                setMsgField(mid, "content", draft.trim())
                editingMid = null
                hideKeyboard(et)
                refreshChat()
            })
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val lp = copyParams(content.layoutParams)
            if (lp != null) box.layoutParams = lp
            inlineInfo[root] = InlineInfo(content, content.minHeight)
            if (constraint) {
                content.visibility = View.INVISIBLE
                box.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    val h = bottom - top
                    if (h != oldBottom - oldTop) content.minHeight = h
                }
            } else {
                content.visibility = View.GONE
            }
            parent.addView(box, parent.indexOfChild(content) + 1)
            et.requestFocus()
            et.postDelayed({
                try {
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                } catch (e: Throwable) {
                }
            }, 150)
        } catch (e: Throwable) {
            Log.e("LocalEditor", "inline editor failed, using dialog", e)
            editingMid = null
            removeInline(root)
            showSingle(root.context, "Edit message", "Message text", draft, mid, "content")
        }
    }

    // ---------- message rows ----------
    private fun applyMessage(root: View, entry: MessageEntry) {
        val msg = entry.message
        val mid = msg.id.toString()
        val uid = msg.author.id.toString()
        val ctx = root.context
        val uc = userCfg(uid)
        val mc = loadObj("msgs").optJSONObject(mid)
        val pencils = settings.getString("pencils", "1") == "1"

        hiddenByUs.remove(root)?.forEach { it.visibility = View.VISIBLE }
        if (editingMid != mid) removeInline(root)

        // name
        val nameTv = root.findViewById<TextView>(Utils.getResId("chat_list_adapter_item_text_name", "id"))
        if (nameTv != null) {
            val cur = nameTv.text.toString()
            val custom = uc?.optString("name", "") ?: ""
            if (custom.isNotEmpty()) {
                if (cur != custom) origNames[uid] = cur
                nameTv.text = custom
            } else if (cur.isNotEmpty()) {
                origNames[uid] = cur
            }
            setPencil(nameTv, pencils) { showUserForm(ctx, uid, origNames[uid] ?: cur) }
        }

        // timestamp
        val timeTv = root.findViewById<TextView>(Utils.getResId("chat_list_adapter_item_text_timestamp", "id"))
        val customTime = mc?.optString("time", "") ?: ""
        if (timeTv != null && customTime.isNotEmpty()) timeTv.text = customTime
        setPencil(timeTv, pencils) {
            showSingle(
                ctx, "Edit timestamp", "Timestamp text",
                if (customTime.isNotEmpty()) customTime else (timeTv?.text?.toString() ?: ""),
                mid, "time"
            )
        }

        // message text
        val content = root.findViewById<TextView>(Utils.getResId("chat_list_adapter_item_text", "id"))
        if (content != null) {
            val ov = mc?.optString("content", "") ?: ""
            if (ov.isNotEmpty()) content.text = ov
            renameMentions(content)
            if (editingMid == mid) {
                setPencil(content, false) {}
                showInline(root, content, mid)
            } else {
                setPencil(content, pencils) { startInline(mid, content.text.toString()) }
            }
        }

        // reply preview text
        val replyTv = findReplyText(root)
        val customReply = mc?.optString("replyText", "") ?: ""
        if (replyTv != null && customReply.isNotEmpty()) replyTv.text = customReply
        setPencil(replyTv, pencils) {
            showSingle(
                ctx, "Edit reply preview", "Quoted text",
                if (customReply.isNotEmpty()) customReply else (replyTv?.text?.toString() ?: ""),
                mid, "replyText"
            )
        }

        // avatar, decoration, tag
        val avatar = root.findViewById<View>(Utils.getResId("chat_list_adapter_item_text_avatar", "id"))
        applyAvatar(root, avatar, uc)
        applyTag(root, uc)
    }

    // ---------- profile screen ----------
    private fun applyProfile(root: View, uid: String) {
        val c = userCfg(uid) ?: return
        val status = c.optString("status", "")
        val statusText = c.optString("statusText", "")
        val colors = mapOf(
            "online" to 0xFF23A55A.toInt(), "idle" to 0xFFF0B232.toInt(),
            "dnd" to 0xFFF23F43.toInt(), "offline" to 0xFF80848E.toInt()
        )
        walk(root) { v ->
            val n = idName(v)?.lowercase() ?: ""
            if (status.isNotEmpty() && v is ImageView &&
                (n.contains("status") || n.contains("presence")) && !n.contains("text")
            ) {
                val col = colors[status]
                if (col != null) v.setColorFilter(col, PorterDuff.Mode.SRC_IN)
            }
            if (statusText.isNotEmpty() && v is TextView &&
                (n.contains("custom_status") || n.contains("status_text") || n.contains("presence_text"))
            ) v.text = statusText
        }
    }

    // ---------- menu item that matches the real buttons ----------
    private fun addEditItem(layout: LinearLayout, label: String, onClick: () -> Unit) {
        val ctx = layout.context
        val wanted = listOf("edit", "reply", "forward", "copy text", "pin", "mark unread")
        var tpl: TextView? = null
        var tplIndex = -1
        for (i in 0 until layout.childCount) {
            val c = layout.getChildAt(i)
            if (c is TextView && c.tag != "le_edit" && c.text.toString().trim().lowercase() in wanted) {
                tpl = c
                tplIndex = i
                break
            }
        }
        val item = TextView(ctx)
        item.tag = "le_edit"
        item.text = label
        var insertAt = 0
        val t = tpl
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

            val isEdit = t.text.toString().trim().lowercase() == "edit"
            val src = t.compoundDrawablesRelative[0] ?: t.compoundDrawables[0]
            var icon: Drawable? = null
            if (isEdit && src != null) icon = src.constantState?.newDrawable()?.mutate()
            if (icon == null) icon = pencil(ctx, 24)
            if (icon != null) {
                if (src != null && src.bounds.width() > 0) icon.setBounds(0, 0, src.bounds.width(), src.bounds.height())
                else if (icon.bounds.width() <= 0) icon.setBounds(0, 0, dp(ctx, 24), dp(ctx, 24))
                item.setCompoundDrawablesRelative(icon, null, null, null)
            }
            insertAt = if (isEdit) tplIndex + 1 else tplIndex
        } else {
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            item.setTextColor(cText)
            item.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            item.compoundDrawablePadding = dp(ctx, 16)
            val icon = pencil(ctx, 24)
            if (icon != null) item.setCompoundDrawablesRelative(icon, null, null, null)
        }
        item.setOnClickListener { onClick() }
        layout.addView(item, insertAt)
    }

    // ---------- debug dump ----------
    private fun dump(v: View, depth: Int, sb: StringBuilder) {
        if (depth > 9 || sb.length > 14000) return
        sb.append("  ".repeat(depth)).append(v.javaClass.simpleName)
        val n = idName(v)
        if (n != null) sb.append(" #").append(n)
        if (v.visibility != View.VISIBLE) sb.append(" [hidden]")
        if (v is TextView) sb.append(" \"").append(v.text.toString().take(20)).append("\"")
        sb.append('\n')
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) dump(v.getChildAt(i), depth + 1, sb)
        }
    }

    private fun showRowDump() {
        ui.post {
            val rv = chatRef?.get() ?: return@post
            var target: View? = null
            for (i in 0 until rv.childCount) {
                val row = rv.getChildAt(i)
                if (findById(row, "chat_list_adapter_item_text_name") != null) {
                    target = row
                    break
                }
            }
            val t = target
            val sb = StringBuilder()
            if (t == null) sb.append("No message row on screen.") else dump(t, 0, sb)
            showInfo(rv.context, "Message row layout", sb.toString())
        }
    }

    // ---------- hooks ----------
    private fun hookAvatars() {
        patcher.patch(
            IconUtils::class.java.getDeclaredMethod(
                "getForUser",
                java.lang.Long::class.java, String::class.java, Integer::class.java,
                java.lang.Boolean.TYPE, Integer::class.java
            ),
            Hook { param ->
                val id = (param.args[0] as? Long)?.toString() ?: return@Hook
                val url = userCfg(id)?.optString("avatar", "") ?: ""
                if (url.isNotEmpty()) param.result = url
            }
        )
    }

    private fun hookMessages() {
        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure", Int::class.java, ChatListEntry::class.java
        ) { param ->
            try {
                val entry = param.args[1] as? MessageEntry ?: return@after
                val root = (param.thisObject as RecyclerView.ViewHolder).itemView
                val rv = root.parent as? RecyclerView
                if (rv != null) chatRef = WeakReference(rv)
                applyMessage(root, entry)
            } catch (e: Throwable) {
                Log.e("LocalEditor", "row hook failed", e)
            }
        }
    }

    private fun hookMessageActions() {
        val cls = Class.forName("com.discord.widgets.chat.list.actions.WidgetChatListActions")
        val m = cls.declaredMethods.first { it.name == "configureUI" }
        patcher.patch(m, Hook { param ->
            try {
                val view = call(param.thisObject, "getView") as? ViewGroup ?: return@Hook
                val layout = view.getChildAt(0) as? LinearLayout ?: return@Hook
                if (layout.findViewWithTag<View>("le_edit") != null) return@Hook
                val msg = call(param.args[0], "getMessage") ?: return@Hook
                val msgId = call(msg, "getId")?.toString() ?: return@Hook
                val content = (call(msg, "getContent") as? String) ?: ""
                addEditItem(layout, "Edit locally") {
                    call(param.thisObject, "dismiss")
                    startInline(msgId, content)
                }
            } catch (e: Throwable) {
                Log.e("LocalEditor", "actions hook failed", e)
            }
        })
    }

    private fun hookUserSheet() {
        val cls = Class.forName("com.discord.widgets.user.usersheet.WidgetUserSheet")
        val m = cls.declaredMethods.first { it.name == "configureUI" }
        patcher.patch(m, Hook { param ->
            try {
                val view = call(param.thisObject, "getView") as? View ?: return@Hook
                val content = view.findViewById<ViewGroup>(Utils.getResId("user_sheet_content", "id")) ?: return@Hook
                val user = call(param.args[0], "getUser") ?: return@Hook
                val uid = call(user, "getId")?.toString() ?: return@Hook
                val name = (call(user, "getUsername") as? String) ?: "user"
                applyProfile(view, uid)
                if (content.findViewWithTag<View>("le_edit") != null) return@Hook
                val ctx = content.context
                val btn = makeButton(ctx, "Edit locally", cBlurple) {
                    call(param.thisObject, "dismiss")
                    showUserForm(ctx, uid, name)
                }
                btn.tag = "le_edit"
                btn.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8)) }
                content.addView(btn)
            } catch (e: Throwable) {
                Log.e("LocalEditor", "user sheet hook failed", e)
            }
        })
    }

    private fun registerCommands() {
        commands.registerCommand("ldump", "Debug: dump the layout of a message row", emptyList()) { _ ->
            showRowDump()
            CommandsAPI.CommandResult("Opening dump...", null, false)
        }

        commands.registerCommand(
            "lpencils", "Show or hide the edit pencils",
            listOf(Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "enabled", "Show pencils", required = true))
        ) { ctx ->
            val on = ctx.getBoolOrDefault("enabled", true)
            settings.setString("pencils", if (on) "1" else "0")
            refreshChat()
            CommandsAPI.CommandResult(if (on) "Pencils on." else "Pencils off.", null, false)
        }

        commands.registerCommand("lwipe", "Remove ALL local edits", emptyList()) { _ ->
            settings.setString("users", "{}")
            settings.setString("msgs", "{}")
            refreshChat()
            CommandsAPI.CommandResult("All local edits removed.", null, false)
        }
    }
    private fun hookChatList() {
        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure", Int::class.java, ChatListEntry::class.java
        ) { param ->
            try {
                val root = (param.thisObject as RecyclerView.ViewHolder).itemView
                val cur = chatRef?.get()
                if (cur == null || !cur.isAttachedToWindow) {
                    root.post {
                        var p: android.view.ViewParent? = root.parent
                        while (p != null && p !is RecyclerView) p = p.parent
                        if (p is RecyclerView) chatRef = WeakReference(p)
                    }
                }
            } catch (e: Throwable) {
                Log.e("LocalEditor", "chat list hook failed", e)
            }
        }
    }
    override fun start(context: Context) {
        safe("avatars") { hookAvatars() }
        safe("messages") { hookMessages() }
        safe("chat list") { hookChatList() }
        safe("message actions") { hookMessageActions() }
        safe("user sheet") { hookUserSheet() }
        safe("commands") { registerCommands() }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
        editingMid = null
    }
}
