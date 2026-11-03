package com.rui.nervlive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build

object Notify {
    const val CH_MONITOR = "monitor"
    const val CH_EEW_F = "eew_forecast_v2"
    const val CH_EEW_W = "eew_warn_v2"
    const val CH_WEATHER = "weather_alert_v2"
    const val CH_TSUNAMI = "tsunami_alert_v2"
    const val FGS_ID = 1
    private var nextId = 100

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "監視状態", NotificationManager.IMPORTANCE_LOW).apply {
                description = "RuiAlert監視サービスの常駐通知"
            }
        )
        // Chimes ship as raw resources and are set as the channel sounds, so the
        // system itself plays them (the standard way; reliable on all devices).
        val pinponUri = android.net.Uri.parse(
            "android.resource://" + context.packageName + "/" + R.raw.chime_pinpon
        )
        val alertUri = android.net.Uri.parse(
            "android.resource://" + context.packageName + "/" + R.raw.chime_alert
        )
        val notifAttr = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarmAttr = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CH_EEW_F, "緊急地震速報（予報）", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "緊急地震速報の予報（ピンポーン）"
                enableVibration(true)
                setSound(pinponUri, notifAttr)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EEW_W, "緊急地震速報（警報）", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "緊急地震速報の警報（重要チャイム）"
                enableVibration(true)
                setSound(alertUri, alarmAttr)
                try {
                    setBypassDnd(true)
                } catch (e: Exception) {
                    // takes effect once notification-policy access is granted
                }
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WEATHER, "気象の重大情報", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "特別警報・線状降水帯の発生情報（重要チャイム）"
                enableVibration(true)
                setSound(alertUri, alarmAttr)
                try {
                    setBypassDnd(true)
                } catch (e: Exception) {
                    // best effort
                }
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TSUNAMI, "津波情報", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "大津波警報・津波警報・津波注意報（現在地の沿岸／重要チャイム）"
                enableVibration(true)
                setSound(alertUri, alarmAttr)
                try {
                    setBypassDnd(true)
                } catch (e: Exception) {
                    // best effort
                }
            }
        )
    }

    /**
     * Ask the system to promote this notification to a Live Update.
     * The framework method exists on Android 16 QPR1+ / Android 17 devices;
     * it is called reflectively so the app still compiles and runs elsewhere.
     */
    private fun requestPromotion(builder: Notification.Builder) {
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                Notification.Builder::class.java
                    .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                    .invoke(builder, true)
            } catch (e: Exception) {
                // Not available on this device; the plain notification still shows.
            }
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun fgsNotification(context: Context): Notification =
        Notification.Builder(context, CH_MONITOR)
            .setContentTitle("RuiAlert監視中")
            .setContentText("緊急地震速報と気象の重大情報を監視しています")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .build()

    /**
     * EEW notification. On Android 16+ (API 36) it requests Live Updates promotion
     * and, while the shaking is on its way, shows a ProgressStyle bar for the countdown.
     */
    fun notifyEew(
        context: Context,
        isWarn: Boolean,
        hypocenter: String,
        magnitude: Double,
        areaLabel: String,
        shindo: String,
        secondsToArrival: Int?
    ) {
        val level = if (isWarn) "警報" else "予報"
        val title = "緊急地震速報（$level）"
        // By spec, the EEW notification never shows arrival countdown seconds.
        val text = "$hypocenter M$magnitude ${areaLabel}の予想震度 $shindo"
        val color = if (isWarn) Color.rgb(198, 40, 40) else Color.rgb(239, 108, 0)
        val builder = Notification.Builder(context, if (isWarn) CH_EEW_W else CH_EEW_F)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(if (isWarn) R.drawable.ic_eew_warn_picto else R.drawable.ic_eew_forecast)
            .setColor(color)
            .setContentIntent(openAppIntent(context))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                requestPromotion(builder)
            } catch (e: Exception) {
                // Live Updates are best-effort; the plain high-priority notification still shows.
            }
        }
        if (isWarn) DndBypass.apply(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(2000 + (nextId++ % 100), builder.build())
        NotifiedLog.add(context, title, text)
        // Voice readout, starting after the channel chime has finished.
        val spoken = if (isWarn) {
            "緊急地震速報、警報です。震源は${hypocenter}、マグニチュード${magnitude}。${areaLabel}で震度${shindo}程度の強い揺れが予想されます。"
        } else {
            "緊急地震速報、予報です。震源は${hypocenter}、マグニチュード${magnitude}。${areaLabel}で震度${shindo}程度の揺れが予想されます。"
        }
        Speaker.speak(context, spoken, if (isWarn) 2600 else 2800, critical = isWarn)
    }

    fun notifyWeather(context: Context, title: String, text: String, purple: Boolean) {
        val builder = Notification.Builder(context, CH_WEATHER)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(if (purple) android.R.drawable.stat_sys_warning else R.drawable.ic_rainband_picto)
            .setColor(if (purple) Color.rgb(106, 27, 154) else Color.rgb(255, 193, 7))
            .setContentIntent(openAppIntent(context))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                requestPromotion(builder)
            } catch (e: Exception) {
                // best effort
            }
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(3000 + (nextId++ % 100), builder.build())
        NotifiedLog.add(context, title, text)
    }

    /** Tsunami alerts for Aichi coasts: always treated as critical. */
    fun notifyTsunami(context: Context, grade: String, areasText: String) {
        val title = when (grade) {
            "MajorWarning" -> "大津波警報"
            "Warning" -> "津波警報"
            else -> "津波注意報"
        }
        val text = "$areasText に${title}が発表されています。高台や避難ビルなど安全な場所へ避難してください。"
        val color = when (grade) {
            "MajorWarning" -> Color.rgb(106, 27, 154)
            "Warning" -> Color.rgb(198, 40, 40)
            else -> Color.rgb(255, 193, 7)
        }
        val builder = Notification.Builder(context, CH_TSUNAMI)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_tsunami_picto)
            .setColor(color)
            .setContentIntent(openAppIntent(context))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                requestPromotion(builder)
            } catch (e: Exception) {
                // best effort
            }
        }
        DndBypass.apply(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(4000 + (nextId++ % 100), builder.build())
        NotifiedLog.add(context, title, text)
        // Voice readout, starting after the alert chime has finished.
        val advice = if (grade == "Advisory") {
            "海岸には近づかないでください。"
        } else {
            "直ちに高台や避難ビルなど、安全な場所へ避難してください。"
        }
        Speaker.speak(context, "${title}が発表されました。${areasText}です。${advice}", 2600)
    }
}

/** If the user granted notification-policy access, lift Do Not Disturb for critical alerts. */
object DndBypass {
    fun isGranted(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.isNotificationPolicyAccessGranted
    }

    fun apply(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted &&
                nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            ) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (e: Exception) {
            // best effort
        }
    }
}

/** On-device history of notifications this app has sent. Entries can be locked. */
object NotifiedLog {
    private const val FILE = "notified_log.tsv"
    private const val MAX_UNLOCKED = 100

    data class Entry(val time: Long, val title: String, val text: String, val locked: Boolean)

    private fun file(context: android.content.Context) = java.io.File(context.filesDir, FILE)

    private fun writeAll(context: android.content.Context, entries: List<Entry>) {
        val sb = StringBuilder()
        for (e in entries) {
            val t = e.title.replace('\t', ' ').replace('\n', ' ')
            val x = e.text.replace('\t', ' ').replace('\n', ' ')
            sb.append(e.time).append('\t').append(if (e.locked) '1' else '0')
                .append('\t').append(t).append('\t').append(x).append('\n')
        }
        file(context).writeText(sb.toString())
    }

    fun add(context: android.content.Context, title: String, text: String) {
        try {
            val entries = read(context).toMutableList()
            entries.add(0, Entry(System.currentTimeMillis(), title, text, false))
            // Trim oldest unlocked entries beyond the cap; locked ones always stay.
            var unlocked = entries.count { !it.locked }
            var i = entries.size - 1
            while (unlocked > MAX_UNLOCKED && i >= 0) {
                if (!entries[i].locked) {
                    entries.removeAt(i)
                    unlocked--
                }
                i--
            }
            writeAll(context, entries)
        } catch (e: Exception) {
            // best effort
        }
    }

    /** Entries newest first. Understands both the v1 (3-field) and v2 (4-field) formats. */
    fun read(context: android.content.Context): List<Entry> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyList()
            val rawLines = f.readLines().filter { it.isNotBlank() }
            val isV1 = rawLines.firstOrNull()?.split('\t')?.size == 3
            val parsed = rawLines.mapNotNull { line ->
                val parts = line.split('\t')
                when {
                    parts.size >= 4 -> {
                        val t = parts[0].toLongOrNull() ?: return@mapNotNull null
                        Entry(t, parts[2], parts[3], parts[1] == "1")
                    }
                    parts.size == 3 -> {
                        val t = parts[0].toLongOrNull() ?: return@mapNotNull null
                        Entry(t, parts[1], parts[2], false)
                    }
                    else -> null
                }
            }
            // v1 files were stored oldest-first; v2 files are newest-first.
            if (isV1) parsed.reversed() else parsed
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun delete(context: android.content.Context, entry: Entry) {
        try {
            val entries = read(context).filterNot {
                it.time == entry.time && it.title == entry.title && it.text == entry.text
            }
            writeAll(context, entries)
        } catch (e: Exception) {
            // best effort
        }
    }

    fun setLocked(context: android.content.Context, entry: Entry, locked: Boolean) {
        try {
            val entries = read(context).map {
                if (it.time == entry.time && it.title == entry.title && it.text == entry.text) {
                    it.copy(locked = locked)
                } else it
            }
            writeAll(context, entries)
        } catch (e: Exception) {
            // best effort
        }
    }

    fun clearUnlocked(context: android.content.Context) {
        try {
            writeAll(context, read(context).filter { it.locked })
        } catch (e: Exception) {
            // best effort
        }
    }
}
