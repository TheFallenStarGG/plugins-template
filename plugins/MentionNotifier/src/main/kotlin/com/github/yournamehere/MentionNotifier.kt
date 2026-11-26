package com.github.yournamehere

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.api.commands.ApplicationCommandType
import com.discord.stores.StoreStream

@AliucordPlugin
class MentionNotifier : Plugin() {

    private val notifChannel = "mention_notifier"
    private var appCtx: Context? = null
    private var hooked = 0
    private var lastNote = "nothing received yet"
    private val seen = LinkedHashSet<Long>()
    private val history = HashMap<Long, MutableList<String>>()

    // ---------- helpers ----------
    private fun fld(o: Any?, name: String): Any? {
        var c: Class<*>? = o?.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(o)
            } catch (e: NoSuchFieldException) {
            } catch (e: Throwable) {
                return null
            }
            c = c.superclass
        }
        return null
    }

    private fun callArgs(o: Any?, name: String, vararg args: Any?): Any? {
        if (o == null) return null
        return try {
            val m = o.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
                ?: return null
            m.invoke(o, *args)
        } catch (e: Throwable) {
            null
        }
    }

    private fun idOfUser(u: Any?): Long? =
        (callArgs(u, "getId") ?: fld(u, "id")) as? Long

    private fun appInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun clean(raw: String, mentions: List<*>?): String {
        var s = raw
        mentions?.forEach { u ->
            val id = idOfUser(u)
            val name = fld(u, "username") as? String
            if (id != null && name != null) {
                s = s.replace("<@$id>", "@$name").replace("<@!$id>", "@$name")
            }
        }
        s = s.replace(Regex("<a?:(\\w+):\\d+>"), ":\$1:")
        s = s.replace(Regex("<@&\\d+>"), "@role")
        s = s.replace(Regex("<#\\d+>"), "#channel")
        return s
    }

    // ---------- notification ----------
    private fun post(ctx: Context, chanId: Long, guildId: Long, msgId: Long, title: String, line: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val lines = history.getOrPut(chanId) { mutableListOf() }
        lines.add(line)
        while (lines.size > 6) lines.removeAt(0)

        val where = if (guildId == 0L) "@me" else guildId.toString()
        val uri = Uri.parse("https://discord.com/channels/$where/$chanId/$msgId")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, chanId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, notifChannel)
        else Notification.Builder(ctx)
        val icon = if (ctx.applicationInfo.icon != 0) ctx.applicationInfo.icon
        else android.R.drawable.ic_dialog_info
        b.setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(Notification.CATEGORY_MESSAGE)
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH)
        nm.notify(chanId.hashCode(), b.build())
    }

    // ---------- message handling ----------
    private fun onMessage(raw: Any?) {
        try {
            var msg = raw
            if (fld(msg, "channelId") == null) msg = fld(msg, "message") ?: msg
            val msgId = fld(msg, "id") as? Long ?: return

            synchronized(seen) {
                if (!seen.add(msgId)) return
                if (seen.size > 300) seen.remove(seen.first())
            }

            val ctx = appCtx ?: return
            val me = idOfUser(callArgs(StoreStream.getUsers(), "getMe")) ?: run {
                lastNote = "no current user id found"
                return
            }

            val author = fld(msg, "author")
            if (idOfUser(author) == me) { lastNote = "ignored: own message"; return }

            val type = fld(msg, "type") as? Int
            if (type != null && type != 0 && type != 19 && type != 20) {
                lastNote = "ignored: system message"
                return
            }

            val chanId = fld(msg, "channelId") as? Long ?: return
            val ch = callArgs(StoreStream.getChannels(), "getChannel", chanId)
            val chType = fld(ch, "type") as? Int
            val guildId = (fld(msg, "guildId") as? Long) ?: (fld(ch, "guildId") as? Long) ?: 0L
            val isDm = chType == 1 || chType == 3 || (chType == null && guildId == 0L)

            val mentions = fld(msg, "mentions") as? List<*>
            val mentionedMe = mentions?.any { idOfUser(it) == me } == true
            val everyone = fld(msg, "mentionEveryone") as? Boolean == true
            val wantEveryone = settings.getString("everyone", "1") == "1"

            if (!(isDm || mentionedMe || (everyone && wantEveryone))) {
                lastNote = "ignored: not a DM or mention"
                return
            }
            if (settings.getString("whileopen", "0") != "1" && appInForeground()) {
                lastNote = "skipped: app is open"
                return
            }

            val username = fld(author, "username") as? String ?: "Someone"
            val nick = fld(fld(msg, "member"), "nick") as? String
            val authorName = if (!nick.isNullOrEmpty()) nick else username

            var text = clean((fld(msg, "content") as? String) ?: "", mentions)
            if (text.isBlank()) {
                text = when {
                    (fld(msg, "attachments") as? List<*>)?.isNotEmpty() == true -> "Sent an attachment"
                    (fld(msg, "embeds") as? List<*>)?.isNotEmpty() == true -> "Sent an embed"
                    (fld(msg, "stickerItems") as? List<*>)?.isNotEmpty() == true -> "Sent a sticker"
                    else -> "Sent a message"
                }
            }

            val title: String
            val line: String
            if (isDm) {
                title = if (chType == 3) "$authorName (Group DM)" else authorName
                line = text
            } else {
                val chName = fld(ch, "name") as? String ?: "channel"
                val guild = callArgs(StoreStream.getGuilds(), "getGuild", guildId)
                val guildName = callArgs(guild, "getName") as? String ?: "server"
                title = "#$chName ($guildName)"
                line = "$authorName: $text"
            }

            post(ctx, chanId, guildId, msgId, title, line)
            lastNote = "notified for message $msgId"
        } catch (e: Throwable) {
            lastNote = "error: ${e.javaClass.simpleName} ${e.message}"
            Log.e("MentionNotifier", "onMessage failed", e)
        }
    }

    // ---------- setup ----------
    private fun hookMessages() {
        var n = 0
        for (cn in listOf("com.discord.stores.StoreMessages", "com.discord.stores.StoreStream")) {
            val cls = try { Class.forName(cn) } catch (e: Throwable) { continue }
            for (m in cls.declaredMethods) {
                if (m.name != "handleMessageCreate" || m.parameterTypes.size != 1) continue
                patcher.patch(m, Hook { param -> onMessage(param.args[0]) })
                n++
            }
        }
        hooked = n
    }

    private fun registerCommands() {
        commands.registerCommand("nmtest", "Send a test notification now", emptyList()) { _ ->
            val ctx = appCtx
            if (ctx == null) {
                CommandsAPI.CommandResult("Plugin not ready.", null, false)
            } else {
                try {
                    post(ctx, 1L, 0L, 1L, "MentionNotifier", "Test notification. If you can see this, posting works.")
                    CommandsAPI.CommandResult("Test notification sent. Put the app in the background to see it.", null, false)
                } catch (e: Throwable) {
                    CommandsAPI.CommandResult("Failed: ${e.message}", null, false)
                }
            }
        }

        commands.registerCommand("nmstatus", "Show what MentionNotifier is doing", emptyList()) { _ ->
            val ctx = appCtx
            val allowed = try {
                (ctx?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.areNotificationsEnabled()
            } catch (e: Throwable) { null }
            CommandsAPI.CommandResult(
                "Message hooks installed: $hooked\n" +
                    "Notifications allowed: $allowed\n" +
                    "App in foreground: ${appInForeground()}\n" +
                    "Notify while app open: ${settings.getString("whileopen", "0") == "1"}\n" +
                    "Notify for @everyone/@here: ${settings.getString("everyone", "1") == "1"}\n" +
                    "Last event: $lastNote",
                null, false
            )
        }

        commands.registerCommand(
            "nmoptions", "MentionNotifier options",
            listOf(
                Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "everyone", "Notify for @everyone and @here"),
                Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "whileopen", "Also notify while the app is open (for testing)")
            )
        ) { ctx ->
            val ev = ctx.getBoolOrDefault("everyone", settings.getString("everyone", "1") == "1")
            val wo = ctx.getBoolOrDefault("whileopen", settings.getString("whileopen", "0") == "1")
            settings.setString("everyone", if (ev) "1" else "0")
            settings.setString("whileopen", if (wo) "1" else "0")
            CommandsAPI.CommandResult("Saved. everyone=$ev, whileopen=$wo", null, false)
        }
    }

    override fun start(context: Context) {
        appCtx = context.applicationContext ?: context
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val ch = NotificationChannel(notifChannel, "Mentions & DMs", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "Pings and direct messages"
                nm.createNotificationChannel(ch)
            }
        } catch (e: Throwable) {
            Log.e("MentionNotifier", "channel create failed", e)
        }
        try { hookMessages() } catch (e: Throwable) { Log.e("MentionNotifier", "hook failed", e) }
        try { registerCommands() } catch (e: Throwable) { Log.e("MentionNotifier", "commands failed", e) }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }
}
