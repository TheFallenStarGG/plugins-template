package com.github.yournamehere

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
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

    private val origs = WeakHashMap<View, Orig>()
    private val hiddenByUs = WeakHashMap<View, MutableList<View>>()
    private var chatRef: WeakReference<RecyclerView>? = null
    private val ui = Handler(Looper.getMainLooper())
    private val botTagId = "chat_list_adapter_item_text_tag"
    private val guildKeys = listOf("clan", "guild_tag", "guildtag", "primary_guild", "server_tag", "guild_badge")

    // ---------- storage ----------
    private fun load(key: String) = JSONObject(settings.getString(key, "{}"))

    private fun put(key: String, id: String, value: String?) {
        val o = load(key)
        if (value == null || value.isEmpty()) o.remove(id) else o.put(id, value)
        settings.setString(key, o.toString())
    }

    private fun saveTag(uid: String, text: String, official: Boolean, noBadge: Boolean) {
        val all = load("tagdata")
        if (text.isEmpty()) all.remove(uid)
        else all.put(uid, JSONObject().put("t", text).put("o", official).put("nb", noBadge))
        settings.setString("tagdata", all.toString())
    }

    private fun clearUser(uid: String) {
        saveTag(uid, "", false, false)
        put("avatars", uid, null)
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

    // finds the visible server-tag view in a message row (not the BOT tag)
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

    private fun hide(root: View, v: View) {
        v.visibility = View.GONE
        hiddenByUs.getOrPut(root) { mutableListOf() }.add(v)
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
        tv.setTextColor(0xFFFFFFFF.toInt())
        if (official) {
            tv.backgroundTintList = ColorStateList.valueOf(0xFF5865F2.toInt())
            for (name in listOf("ic_verified_badge", "ic_verified_10dp", "ic_check_16dp")) {
                val res = Utils.getResId(name, "drawable")
                if (res != 0) {
                    tv.setCompoundDrawablesWithIntrinsicBounds(res, 0, 0, 0)
                    break
                }
            }
        } else {
            tv.backgroundTintList = ColorStateList.valueOf(0xFF4E5058.toInt())
            tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    private fun resetBotTag(tv: TextView) {
        val o = origs.remove(tv) ?: return
        tv.backgroundTintList = o.tint
        if (o.colors != null) tv.setTextColor(o.colors)
        tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
    }

    // ---------- message rows ----------
    private fun applyMessage(root: View, entry: MessageEntry) {
        val msg = entry.message
        hiddenByUs.remove(root)?.forEach { it.visibility = View.VISIBLE }

        val newText = load("messages").optString(msg.id.toString(), "")
        if (newText.isNotEmpty()) {
            root.findViewById<TextView>(Utils.getResId("chat_list_adapter_item_text", "id"))?.text = newText
        }

        val botTag = root.findViewById<TextView>(Utils.getResId(botTagId, "id"))
        val uid = msg.author.id.toString()
        val data = load("tagdata").optJSONObject(uid)
        if (data == null || data.optString("t", "").isEmpty()) {
            if (botTag != null) resetBotTag(botTag)
            return
        }
        val text = data.optString("t", "")
        val official = data.optBoolean("o", false)
        val noBadge = data.optBoolean("nb", false)
        val guild = findGuildTag(root)

        if (guild != null && !official) {
            setGuildText(root, guild, text, noBadge)
            if (botTag != null) resetBotTag(botTag)
        } else {
            if (official && guild != null) hide(root, guild)
            if (botTag != null) styleBotTag(botTag, text, official)
        }
    }

    // ---------- dialogs ----------
    private fun showMessageDialog(ctx: Context, msgId: String, current: String) {
        val input = EditText(ctx)
        input.setText(load("messages").optString(msgId, current))
        input.setSelection(input.text.length)
        val box = LinearLayout(ctx)
        val p = dp(ctx, 20)
        box.setPadding(p, p, p, 0)
        box.addView(input, LinearLayout.LayoutParams(-1, -2))
        AlertDialog.Builder(ctx)
            .setTitle("Edit message (only you see this)")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                put("messages", msgId, input.text.toString())
                refreshChat()
            }
            .setNeutralButton("Reset") { _, _ ->
                put("messages", msgId, null)
                refreshChat()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUserDialog(ctx: Context, uid: String, name: String) {
        val cur = load("tagdata").optJSONObject(uid)

        val tag = EditText(ctx)
        tag.hint = "Tag text (any length, symbols OK)"
        tag.setText(cur?.optString("t", "") ?: "")

        val official = CheckBox(ctx)
        official.text = "Official style (blue check tag, replaces server tag)"
        official.isChecked = cur?.optBoolean("o", false) ?: false

        val noBadge = CheckBox(ctx)
        noBadge.text = "Hide the server tag icon"
        noBadge.isChecked = cur?.optBoolean("nb", false) ?: false

        val avatar = EditText(ctx)
        avatar.hint = "Avatar image URL (direct link)"
        avatar.setText(load("avatars").optString(uid, ""))

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 20)
        box.setPadding(p, p, p, 0)
        box.addView(tag)
        box.addView(official)
        box.addView(noBadge)
        box.addView(avatar)

        AlertDialog.Builder(ctx)
            .setTitle("Edit $name (only you see this)")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                saveTag(uid, tag.text.toString().trim(), official.isChecked, noBadge.isChecked)
                put("avatars", uid, avatar.text.toString().trim())
                refreshChat()
            }
            .setNeutralButton("Reset all") { _, _ ->
                clearUser(uid)
                refreshChat()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun makeMenuItem(ctx: Context, label: String): TextView {
        val tv = TextView(ctx, null, 0, Utils.getResId("UiKit_Settings_Item_Icon", "style"))
        tv.text = label
        tv.tag = "le_edit"
        for (n in listOf("ic_edit_24dp", "ic_edit_white_24dp", "ic_pencil_24dp")) {
            val id = Utils.getResId(n, "drawable")
            if (id != 0) {
                val d = ctx.getDrawable(id)
                d?.mutate()?.setTint(0xFFB5BAC1.toInt())
                tv.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
                break
            }
        }
        return tv
    }

    // ---------- debug dump ----------
    private fun dump(v: View, depth: Int, sb: StringBuilder) {
        if (depth > 5) return
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

    private fun showDump() {
        ui.post {
            val rv = chatRef?.get()
            if (rv == null) return@post
            var target: View? = null
            for (i in 0 until rv.childCount) {
                val nameView = findById(rv.getChildAt(i), "chat_list_adapter_item_text_name")
                if (nameView != null) {
                    val parent = nameView.parent as? View
                    target = (parent?.parent as? View) ?: parent
                    break
                }
            }
            val ctx = rv.context
            val sb = StringBuilder()
            if (target == null) sb.append("No message header found on screen.")
            else dump(target, 0, sb)
            val tv = TextView(ctx)
            tv.text = sb.toString()
            tv.textSize = 10f
            tv.setTextIsSelectable(true)
            val p = dp(ctx, 12)
            tv.setPadding(p, p, p, p)
            val sv = ScrollView(ctx)
            sv.addView(tv)
            AlertDialog.Builder(ctx).setTitle("Header layout").setView(sv).setPositiveButton("OK", null).show()
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
                val url = load("avatars").optString(id, "")
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

    // adds "Edit locally" to the message three-dots / long-press menu
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
                val ctx = layout.context
                val item = makeMenuItem(ctx, "Edit locally")
                item.setOnClickListener {
                    call(param.thisObject, "dismiss")
                    showMessageDialog(ctx, msgId, content)
                }
                layout.addView(item, 0)
            } catch (e: Throwable) {
                Log.e("LocalEditor", "actions hook failed", e)
            }
        })
    }

    // adds "Edit locally (tag / avatar)" at the bottom of the user profile sheet
    private fun hookUserSheet() {
        val cls = Class.forName("com.discord.widgets.user.usersheet.WidgetUserSheet")
        val m = cls.declaredMethods.first { it.name == "configureUI" }
        patcher.patch(m, Hook { param ->
            try {
                val view = call(param.thisObject, "getView") as? View ?: return@Hook
                val content = view.findViewById<ViewGroup>(Utils.getResId("user_sheet_content", "id")) ?: return@Hook
                if (content.findViewWithTag<View>("le_edit") != null) return@Hook
                val user = call(param.args[0], "getUser") ?: return@Hook
                val uid = call(user, "getId")?.toString() ?: return@Hook
                val name = (call(user, "getUsername") as? String) ?: "user"
                val ctx = content.context
                val item = makeMenuItem(ctx, "Edit locally (tag / avatar)")
                item.setOnClickListener {
                    call(param.thisObject, "dismiss")
                    showUserDialog(ctx, uid, name)
                }
                content.addView(item)
            } catch (e: Throwable) {
                Log.e("LocalEditor", "user sheet hook failed", e)
            }
        })
    }

    // ---------- commands (fallback) ----------
    private fun registerCommands() {
        val user = { Utils.createCommandOption(ApplicationCommandType.USER, "user", "Target user", required = true) }

        commands.registerCommand(
            "ltag", "Set a local tag on a user",
            listOf(
                user(),
                Utils.createCommandOption(ApplicationCommandType.STRING, "text", "Tag text, any length", required = true),
                Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "official", "Blue check official-style tag"),
                Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "nobadge", "Hide the server tag icon")
            )
        ) { ctx ->
            val id = ctx.getRequiredUser("user").id.toString()
            saveTag(
                id, ctx.getRequiredString("text"),
                ctx.getBoolOrDefault("official", false), ctx.getBoolOrDefault("nobadge", false)
            )
            refreshChat()
            CommandsAPI.CommandResult("Tag set.", null, false)
        }

        commands.registerCommand(
            "lavatar", "Set a local avatar URL for a user",
            listOf(
                user(),
                Utils.createCommandOption(ApplicationCommandType.STRING, "url", "Direct image link", required = true)
            )
        ) { ctx ->
            put("avatars", ctx.getRequiredUser("user").id.toString(), ctx.getRequiredString("url"))
            refreshChat()
            CommandsAPI.CommandResult("Avatar set. Restart the app if it doesn't change.", null, false)
        }

        commands.registerCommand(
            "lmsg", "Locally edit a message (needs the message ID)",
            listOf(
                Utils.createCommandOption(ApplicationCommandType.STRING, "id", "Message ID", required = true),
                Utils.createCommandOption(ApplicationCommandType.STRING, "text", "New text", required = true)
            )
        ) { ctx ->
            put("messages", ctx.getRequiredString("id"), ctx.getRequiredString("text"))
            refreshChat()
            CommandsAPI.CommandResult("Message edited.", null, false)
        }

        commands.registerCommand(
            "lclear", "Remove local edits for a user",
            listOf(user())
        ) { ctx ->
            clearUser(ctx.getRequiredUser("user").id.toString())
            refreshChat()
            CommandsAPI.CommandResult("Cleared.", null, false)
        }

        commands.registerCommand(
            "ldump", "Debug: show the message header layout",
            emptyList()
        ) { _ ->
            showDump()
            CommandsAPI.CommandResult("Opening dump...", null, false)
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
    }
}
