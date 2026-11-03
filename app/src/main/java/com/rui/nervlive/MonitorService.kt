package com.rui.nervlive

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Background monitoring:
 * - EEW (Wolfx) every ~12 s: notify for Aichi areas, forecast (予報) and warning (警報).
 * - JMA feed every ~5 min: special warnings (特別警報) for Nagoya city and
 *   linear precipitation band occurrence info (顕著な大雨に関する情報) for Aichi.
 * The NERV live stream itself never raises a notification (by design).
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    // EEW state
    private var eewBaselineDone = false
    private var lastEventId: String? = null
    private var notifiedLevel = 0 // 0 none, 1 forecast, 2 warning

    // Tsunami state
    private var tsunamiBaselineDone = false
    private var lastTsunamiId: String? = null
    private var lastTsunamiRank = 0 // 0 none, 1 advisory, 2 warning, 3 major warning

    // Weather state
    private var weatherBaselineDone = false
    private var lastSpecialKey = ""
    private val seenInfoIds = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notify.ensureChannels(this)
        val notif = Notify.fgsNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notify.FGS_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(Notify.FGS_ID, notif)
            }
        } catch (e: Exception) {
            try {
                java.io.File(filesDir, "last_crash.txt").writeText(
                    "[monitor service startForeground failed]\n" + android.util.Log.getStackTraceString(e)
                )
            } catch (ignored: Exception) {
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            started = true
            scope.launch { eewLoop() }
            scope.launch { weatherLoop() }
            scope.launch { tsunamiLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun eewLoop() {
        while (scope.isActive) {
            try {
                checkEew()
            } catch (e: Exception) {
                // network hiccups are expected; keep monitoring
            }
            delay(12_000)
        }
    }

    private fun checkEew() {
        val root = JSONObject(Http.get(Const.WOLFX_EEW_URL, 8000))
        val eventId = root.optString("EventID")
        if (eventId.isEmpty()) return
        if (!eewBaselineDone) {
            // Do not replay whatever EEW was already in flight when the service started.
            eewBaselineDone = true
            lastEventId = eventId
            return
        }
        if (root.optBoolean("isCancel") || root.optBoolean("isTraining")) {
            lastEventId = eventId
            return
        }
        if (eventId != lastEventId) {
            lastEventId = eventId
            notifiedLevel = 0
        }
        // Freshness guard: ignore telegrams announced long ago.
        val announced = root.optString("AnnouncedTime")
        val announcedMs = parseDateTime(announced)
        if (announcedMs > 0 && System.currentTimeMillis() - announcedMs > 15 * 60_000) return

        val warnAreas = root.optJSONArray("WarnArea") ?: return
        val loc = LocationStore.current(this)
        var best: JSONObject? = null
        var bestRank = -1
        for (i in 0 until warnAreas.length()) {
            val a = warnAreas.getJSONObject(i)
            if (!a.optString("Chiiki").startsWith(loc.prefName)) continue
            val r = shindoRank(a.optString("Shindo1"))
            if (r > bestRank) {
                bestRank = r
                best = a
            }
        }
        val area = best ?: return

        val isWarn = root.optBoolean("isWarn")
        val level = if (isWarn) 2 else 1
        if (level <= notifiedLevel) return

        val shindo = area.optString("Shindo1", "-")
        val arrivalSec = secondsUntil(area.optString("Time"))
        Notify.notifyEew(
            context = this,
            isWarn = isWarn,
            hypocenter = root.optString("Hypocenter", "-"),
            magnitude = root.optDouble("Magunitude", root.optDouble("Magnitude", 0.0)),
            areaLabel = area.optString("Chiiki"),
            shindo = shindo,
            secondsToArrival = arrivalSec
        )
        notifiedLevel = level
    }

    private fun shindoRank(s: String): Int = when (s) {
        "7" -> 70; "6強" -> 60; "6弱" -> 55; "5強" -> 50; "5弱" -> 45
        "4" -> 40; "3" -> 30; "2" -> 20; "1" -> 10; else -> 0
    }

    private fun secondsUntil(hhmmss: String): Int? {
        if (hhmmss.isEmpty()) return null
        return try {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
            val today = SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(Date())
            val target = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).parse("$today $hhmmss")
                ?: return null
            ((target.time - System.currentTimeMillis()) / 1000).toInt()
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDateTime(s: String): Long {
        if (s.isEmpty()) return 0
        return try {
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).parse(s)?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private suspend fun weatherLoop() {
        while (scope.isActive) {
            try {
                checkWeather()
            } catch (e: Exception) {
                // keep monitoring
            }
            delay(5 * 60_000)
        }
    }

    private fun checkWeather() {
        val loc = LocationStore.current(this)

        // --- Special warnings for the user's prefecture (bosai warning JSON) ---
        val warn = try {
            WarningSource.fetch(loc.prefCode)
        } catch (e: Exception) {
            null
        }
        if (warn != null) {
            if (warn.specials.isEmpty()) {
                lastSpecialKey = ""
            } else {
                val key = loc.prefCode + "|" + warn.reportDatetime + "|" +
                    warn.specials.joinToString(",")
                if (weatherBaselineDone && key != lastSpecialKey) {
                    Notify.notifyWeather(
                        context = this,
                        title = warn.specials.joinToString("・"),
                        text = "${loc.prefName}に発表されています。直ちに安全を確保してください。",
                        purple = true
                    )
                }
                lastSpecialKey = key
            }
        }

        // --- Linear precipitation band (顕著な大雨に関する情報) for the user's prefecture ---
        val core = loc.prefName.removeSuffix("県").removeSuffix("府").removeSuffix("都")
        val entries = try {
            JmaFeed.fetchEntries()
        } catch (e: Exception) {
            emptyList()
        }
        for (e in entries) {
            if (e.id in seenInfoIds) continue
            val isInfoDoc = e.title.contains("気象情報") && !e.title.contains("警報・注意報")
            if (!isInfoDoc) {
                seenInfoIds += e.id
                continue
            }
            if (weatherBaselineDone) {
                val headTitle = try {
                    JmaFeed.fetchHeadTitle(e.link)
                } catch (ex: Exception) {
                    ""
                }
                if (headTitle.contains("顕著な大雨に関する") && headTitle.contains(core)) {
                    Notify.notifyWeather(
                        context = this,
                        title = "線状降水帯 発生情報",
                        text = "顕著な大雨に関する情報が発表されました（${loc.prefName}）。" +
                            "河川の増水や土砂災害に厳重に警戒してください。",
                        purple = false
                    )
                }
            }
            seenInfoIds += e.id
        }
        if (seenInfoIds.size > 400) {
            val keep = seenInfoIds.toList().takeLast(200).toSet()
            seenInfoIds.clear()
            seenInfoIds.addAll(keep)
        }

        weatherBaselineDone = true
    }

    private suspend fun tsunamiLoop() {
        while (scope.isActive) {
            try {
                checkTsunami()
            } catch (e: Exception) {
                // keep monitoring
            }
            delay(30_000)
        }
    }

    /** Tsunami telegrams (P2P 552): notify for Aichi coasts, graded, incl. escalations. */
    private fun checkTsunami() {
        val issue = fetchLatestTsunamiIssue() ?: return
        if (issue.cancelled) {
            if (issue.id == lastTsunamiId) {
                lastTsunamiId = null
                lastTsunamiRank = 0
            }
            tsunamiBaselineDone = true
            return
        }
        val loc = LocationStore.current(this)
        val core = loc.prefName.removeSuffix("県").removeSuffix("府").removeSuffix("都")
        val extraZones = TSUNAMI_EXTRA_ZONES[loc.prefName] ?: emptyList()
        val aichiAreas = issue.areas.filter {
            it.name.contains(core) || extraZones.contains(it.name)
        }
        if (aichiAreas.isEmpty()) {
            tsunamiBaselineDone = true
            return
        }
        fun rank(grade: String) = when (grade) {
            "MajorWarning" -> 3
            "Warning" -> 2
            "Watch" -> 1
            else -> 0
        }
        val top = aichiAreas.maxByOrNull { rank(it.grade) } ?: return
        val topRank = rank(top.grade)
        val isNew = issue.id != lastTsunamiId
        val escalated = !isNew && topRank > lastTsunamiRank
        if (tsunamiBaselineDone && (isNew || escalated) && topRank > 0) {
            val areasText = aichiAreas.joinToString("、") { it.name }
            Notify.notifyTsunami(this, top.grade, areasText)
        }
        lastTsunamiId = issue.id
        lastTsunamiRank = topRank
        tsunamiBaselineDone = true
    }
}

/** Tsunami forecast zones whose names do not contain their prefecture's name. */
private val TSUNAMI_EXTRA_ZONES = mapOf(
    "愛知県" to listOf("伊勢・三河湾"),
    "三重県" to listOf("伊勢・三河湾"),
    "静岡県" to listOf("遠州灘沿岸", "駿河湾沿岸"),
    "東京都" to listOf("東京湾内湾"),
    "千葉県" to listOf("東京湾内湾"),
    "神奈川県" to listOf("東京湾内湾")
)
