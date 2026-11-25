package com.github.yournamehere

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
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

@AliucordPlugin
class LocalEditor : Plugin() {

    private fun load(key: String) = JSONObject(settings.getString(key, "{}"))

    private fun put(key: String, id: String, value: String?) {
        val o = load(key)
        if (value == null) o.remove(id) else o.put(id, value)
        settings.setString(key, o.toString())
    }

    override fun start(context: Context) {
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

        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure", Int::class.java, ChatListEntry::class.java
        ) { param ->
            val entry = param.args[1] as? MessageEntry ?: return@after
            val msg = entry.message
            val root = (param.thisObject as RecyclerView.ViewHolder).itemView

            val newText = load("messages").optString(msg.id.toString(), "")
            if (newText.isNotEmpty()) {
                val tv = root.findViewById<TextView>(Utils.getResId("chat_list_adapter_item_text", "id"))
                tv?.text = newText
            }

            val tagText = load("tags").optString(msg.author.id.toString(), "")
            if (tagText.isNotEmpty()) {
                val tag = root.findViewById<TextView>(Utils.getResId("chat_list_adapter_item_text_tag", "id"))
                if (tag != null) {
                    tag.visibility = View.VISIBLE
                    tag.text = tagText
                    if (load("official").has(msg.author.id.toString())) {
                        tag.backgroundTintList = ColorStateList.valueOf(0xFF5865F2.toInt())
                        for (name in listOf("ic_verified_badge", "ic_verified_10dp", "ic_check_16dp")) {
                            val res = Utils.getResId(name, "drawable")
                            if (res != 0) {
                                tag.setCompoundDrawablesWithIntrinsicBounds(res, 0, 0, 0)
                                break
                            }
                        }
                    }
                }
            }
        }

        val user = { Utils.createCommandOption(ApplicationCommandType.USER, "user", "Target user", required = true) }

        commands.registerCommand(
            "ltag", "Set a local tag on a user",
            listOf(
                user(),
                Utils.createCommandOption(ApplicationCommandType.STRING, "text", "Tag text, any length", required = true),
                Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "official", "Blurple official-style tag")
            )
        ) { ctx ->
            val id = ctx.getRequiredUser("user").id.toString()
            put("tags", id, ctx.getRequiredString("text"))
            put("official", id, if (ctx.getBoolOrDefault("official", false)) "1" else null)
            CommandsAPI.CommandResult("Tag set. Reopen the channel to refresh.", null, false)
        }

        commands.registerCommand(
            "lavatar", "Set a local avatar URL for a user",
            listOf(
                user(),
                Utils.createCommandOption(ApplicationCommandType.STRING, "url", "Direct image link", required = true)
            )
        ) { ctx ->
            put("avatars", ctx.getRequiredUser("user").id.toString(), ctx.getRequiredString("url"))
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
            CommandsAPI.CommandResult("Message edited. Reopen the channel to refresh.", null, false)
        }

        commands.registerCommand(
            "lclear", "Remove local edits for a user",
            listOf(user())
        ) { ctx ->
            val id = ctx.getRequiredUser("user").id.toString()
            for (k in listOf("tags", "official", "avatars")) put(k, id, null)
            CommandsAPI.CommandResult("Cleared.", null, false)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }
}
