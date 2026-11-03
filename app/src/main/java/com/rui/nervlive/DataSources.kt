package com.rui.nervlive

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

/** Simple HTTP GET helper (no external libraries). */
object Http {
    fun get(url: String, timeoutMs: Int = 12000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        )
        conn.setRequestProperty("Accept-Language", "ja")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

object Const {
    const val NERV_CHANNEL_ID = "UCkj_aPICs7Z5YEizYU8gj_Q"
    const val CHANNEL_LIVE_URL = "https://www.youtube.com/channel/$NERV_CHANNEL_ID/live"
    const val WOLFX_EEW_URL = "https://api.wolfx.jp/jma_eew.json"
    const val P2P_QUAKE_URL = "https://api.p2pquake.net/v2/history?codes=551&limit=1"
    const val JMA_FORECAST_URL = "https://www.jma.go.jp/bosai/forecast/data/forecast/230000.json"
    const val JMA_EXTRA_FEED_URL = "https://www.data.jma.go.jp/developer/xml/feed/extra.xml"
    const val NAGOYA_OFFICE = "名古屋地方気象台"
    val AICHI_EEW_AREAS = listOf("愛知県西部", "愛知県東部")
}

/** Method-2 live tracking: read the channel /live page and extract the current video id. */
object LiveTracker {
    private val canonicalRe = Regex("""watch\?v=([A-Za-z0-9_-]{11})""")

    fun fetchCurrentVideoId(): String? {
        return try {
            val html = Http.get(Const.CHANNEL_LIVE_URL, 15000)
            if (!html.contains("\"isLive\":true")) return null
            canonicalRe.find(html)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}

fun shindoText(scale: Int): String = when (scale) {
    10 -> "1"; 20 -> "2"; 30 -> "3"; 40 -> "4"
    45 -> "5弱"; 50 -> "5強"; 55 -> "6弱"; 60 -> "6強"; 70 -> "7"
    else -> if (scale <= 0) "-" else scale.toString()
}

data class QuakeInfo(
    val hypocenter: String,
    val magnitude: Double,
    val maxScale: Int,
    val depthKm: Int,
    val timeText: String,
    val tsunami: String
)

object QuakeSource {
    fun fetchLatest(): QuakeInfo? {
        return try {
            val arr = org.json.JSONArray(Http.get(Const.P2P_QUAKE_URL))
            if (arr.length() == 0) return null
            val o = arr.getJSONObject(0)
            val eq = o.getJSONObject("earthquake")
            val hypo = eq.getJSONObject("hypocenter")
            val tsunami = when (eq.optString("domesticTsunami")) {
                "None" -> "津波の心配なし"
                "Unknown" -> "津波は調査中"
                "Checking" -> "津波は調査中"
                "NonEffective" -> "若干の海面変動"
                "Watch" -> "津波注意報"
                "Warning" -> "津波警報"
                else -> eq.optString("domesticTsunami")
            }
            QuakeInfo(
                hypocenter = hypo.optString("name", "-"),
                magnitude = hypo.optDouble("magnitude", -1.0),
                maxScale = eq.optInt("maxScale", -1),
                depthKm = hypo.optInt("depth", -1),
                timeText = eq.optString("time", ""),
                tsunami = tsunami
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class WeatherInfo(
    val todayText: String,
    val tomorrowText: String,
    val areaName: String = "",
    val minTemp: String,
    val maxTemp: String
)

object WeatherSource {
    fun fetch(prefCode: String = "230000"): WeatherInfo? {
        return try {
            val url = "https://www.jma.go.jp/bosai/forecast/data/forecast/$prefCode.json"
            val root = org.json.JSONArray(Http.get(url)).getJSONObject(0)
            val ts = root.getJSONArray("timeSeries")
            var today = "-"; var tomorrow = "-"; var areaName = ""
            var minT = "-"; var maxT = "-"
            for (i in 0 until ts.length()) {
                val areas = ts.getJSONObject(i).optJSONArray("areas") ?: continue
                for (j in 0 until areas.length()) {
                    val a = areas.getJSONObject(j)
                    val w = a.optJSONArray("weathers")
                    if (w != null && today == "-") {
                        if (w.length() > 0) today = w.getString(0).replace("　", " ")
                        if (w.length() > 1) tomorrow = w.getString(1).replace("　", " ")
                        areaName = a.getJSONObject("area").optString("name")
                    }
                    val t = a.optJSONArray("temps")
                    if (t != null && t.length() >= 2 && minT == "-") {
                        minT = t.getString(0); maxT = t.getString(1)
                    }
                }
            }
            WeatherInfo(today, tomorrow, minT, maxT, areaName)
        } catch (e: Exception) {
            null
        }
    }
}

data class WarningInfo(
    val headline: String,
    val specials: List<String>,
    val allNames: List<String>,
    val reportDatetime: String,
    val byArea: Map<String, List<String>> = emptyMap()
)

/** Active warnings for a prefecture, from the JMA bosai warning JSON. */
object WarningSource {
    private val SPECIAL = mapOf(
        "32" to "暴風雪特別警報", "33" to "大雨特別警報", "34" to "高潮特別警報",
        "35" to "暴風特別警報", "36" to "大雪特別警報", "37" to "波浪特別警報"
    )
    private val NAMES = mapOf(
        "02" to "暴風雪警報", "03" to "大雨警報", "04" to "洪水警報", "05" to "暴風警報",
        "06" to "大雪警報", "07" to "波浪警報", "08" to "高潮警報",
        "10" to "雷注意報", "12" to "強風注意報", "13" to "風雪注意報",
        "15" to "大雨注意報", "16" to "洪水注意報", "17" to "大雪注意報", "18" to "波浪注意報",
        "19" to "高潮注意報", "20" to "濃霧注意報", "21" to "乾燥注意報", "22" to "なだれ注意報",
        "23" to "低温注意報", "24" to "霜注意報", "25" to "着氷注意報", "26" to "着雪注意報",
        "27" to "融雪注意報"
    ) + SPECIAL

    fun fetch(prefCode: String): WarningInfo? {
        return try {
            val url = "https://www.jma.go.jp/bosai/warning/data/warning/$prefCode.json"
            val root = org.json.JSONObject(Http.get(url))
            val codes = mutableSetOf<String>()
            val byArea = mutableMapOf<String, List<String>>()
            val areaTypes = root.getJSONArray("areaTypes")
            for (i in 0 until areaTypes.length()) {
                val areas = areaTypes.getJSONObject(i).getJSONArray("areas")
                for (j in 0 until areas.length()) {
                    val areaObj = areas.getJSONObject(j)
                    val areaCode = areaObj.optString("code")
                    val areaNames = mutableListOf<String>()
                    val ws = areaObj.optJSONArray("warnings")
                    if (ws != null) {
                        for (k in 0 until ws.length()) {
                            val w = ws.getJSONObject(k)
                            if (w.optString("status") != "解除") {
                                val code = w.optString("code")
                                if (code.isNotEmpty()) {
                                    codes.add(code)
                                    NAMES[code]?.let { areaNames.add(it) }
                                }
                            }
                        }
                    }
                    if (areaCode.length == 7) byArea[areaCode] = areaNames.distinct()
                }
            }
            WarningInfo(
                headline = root.optString("headlineText"),
                specials = codes.mapNotNull { SPECIAL[it] },
                allNames = codes.mapNotNull { NAMES[it] }.distinct(),
                reportDatetime = root.optString("reportDatetime"),
                byArea = byArea
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** One feed entry of the JMA extra.xml Atom feed. */
data class FeedEntry(val id: String, val title: String, val author: String, val link: String, val content: String)

object JmaFeed {
    fun fetchEntries(): List<FeedEntry> {
        val xml = Http.get(Const.JMA_EXTRA_FEED_URL)
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val entries = mutableListOf<FeedEntry>()
        var ev = parser.eventType
        var cur: MutableMap<String, String>? = null
        var tag = ""
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "entry") cur = mutableMapOf()
                    tag = parser.name
                    if (parser.name == "link") {
                        cur?.set("link", parser.getAttributeValue(null, "href") ?: "")
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text?.trim().orEmpty()
                    if (t.isNotEmpty() && cur != null) {
                        when (tag) {
                            "title" -> cur["title"] = t
                            "id" -> cur["id"] = t
                            "name" -> cur["author"] = t
                            "content" -> cur["content"] = t
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "entry" && cur != null) {
                        entries += FeedEntry(
                            id = cur["id"] ?: "",
                            title = cur["title"] ?: "",
                            author = cur["author"] ?: "",
                            link = cur["link"] ?: "",
                            content = cur["content"] ?: ""
                        )
                        cur = null
                    }
                }
            }
            ev = parser.next()
        }
        return entries
    }

    /** Latest warning document URL for Aichi (VPWW53: 気象特別警報・警報・注意報). */
    fun latestAichiWarningUrl(entries: List<FeedEntry>): String? =
        entries.firstOrNull {
            it.author == Const.NAGOYA_OFFICE && it.title.startsWith("気象特別警報・警報・注意報")
        }?.link

    /**
     * Parse a VPWW53 document and return the active warning kinds for Nagoya city.
     * Returns kind names such as 乾燥注意報 / 大雨警報 / 大雨特別警報.
     */
    fun parseNagoyaWarnings(docUrl: String): List<String> {
        val xml = Http.get(docUrl)
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var ev = parser.eventType
        var inItem = false
        var inKind = false
        var areaName: String? = null
        var kindName: String? = null
        var kindStatus: String? = null
        var capture: String? = null
        val kindsByArea = mutableMapOf<String, MutableList<String>>()
        var currentKinds = mutableListOf<String>()
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "Item" -> { inItem = true; currentKinds = mutableListOf(); areaName = null }
                    "Kind" -> { inKind = true; kindName = null; kindStatus = null }
                    "Name", "Status" -> capture = parser.name
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text?.trim().orEmpty()
                    if (t.isNotEmpty()) {
                        when {
                            capture == "Name" && inKind -> kindName = t
                            capture == "Name" && inItem && !inKind -> areaName = t
                            capture == "Status" && inKind -> kindStatus = t
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "Kind" -> {
                        inKind = false
                        if ((kindStatus == "発表" || kindStatus == "継続") && kindName != null) {
                            currentKinds += kindName!!
                        }
                    }
                    "Item" -> {
                        inItem = false
                        val a = areaName
                        if (a != null) kindsByArea[a] = currentKinds
                    }
                    "Name", "Status" -> capture = null
                }
            }
            ev = parser.next()
        }
        return kindsByArea["名古屋市"] ?: emptyList()
    }

    /** Head title of a JMA XML document, e.g. 顕著な大雨に関する愛知県気象情報. */
    fun fetchHeadTitle(docUrl: String): String {
        val xml = Http.get(docUrl)
        val m = Regex("""<Title>([^<]+)</Title>""").find(xml)
        return m?.groupValues?.get(1) ?: ""
    }
}

/** Detailed quake info for the map page: hypocenter coordinates + per-prefecture max scale. */
data class QuakeDetail(
    val hypocenter: String,
    val magnitude: Double,
    val maxScale: Int,
    val depthKm: Int,
    val timeText: String,
    val tsunami: String,
    val latitude: Double,
    val longitude: Double,
    val prefScales: Map<String, Int>
)

object QuakeDetailSource {
    fun fetch(): QuakeDetail? {
        return try {
            val arr = org.json.JSONArray(Http.get(Const.P2P_QUAKE_URL))
            if (arr.length() == 0) return null
            val o = arr.getJSONObject(0)
            val eq = o.getJSONObject("earthquake")
            val hypo = eq.getJSONObject("hypocenter")
            val scales = mutableMapOf<String, Int>()
            val points = o.optJSONArray("points")
            if (points != null) {
                for (i in 0 until points.length()) {
                    val p = points.getJSONObject(i)
                    val pref = p.optString("pref")
                    val scale = p.optInt("scale", 0)
                    if (pref.isNotEmpty()) {
                        val cur = scales[pref] ?: 0
                        if (scale > cur) scales[pref] = scale
                    }
                }
            }
            QuakeDetail(
                hypocenter = hypo.optString("name", "不明"),
                magnitude = hypo.optDouble("magnitude", 0.0),
                maxScale = eq.optInt("maxScale", 0),
                depthKm = hypo.optInt("depth", 0),
                timeText = eq.optString("time", ""),
                tsunami = when (eq.optString("domesticTsunami")) {
                    "None" -> "津波の心配なし"
                    "Unknown" -> "津波の有無は調査中"
                    "Checking" -> "津波の有無を調査中"
                    "NonEffective" -> "津波の影響はなし（若干の海面変動）"
                    "Watch" -> "津波注意報が発表されています"
                    "Warning" -> "津波警報等が発表されています"
                    else -> "津波情報なし"
                },
                latitude = hypo.optDouble("latitude", 0.0),
                longitude = hypo.optDouble("longitude", 0.0),
                prefScales = scales
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** One tsunami area entry from the latest P2P 552 telegram. */
data class TsunamiArea(val name: String, val grade: String)

object TsunamiSource {
    private const val URL = "https://api.p2pquake.net/v2/history?codes=552&limit=3"

    /** Areas of the latest non-cancelled tsunami telegram; empty when nothing is in effect. */
    fun fetchAreas(): List<TsunamiArea> {
        return try {
            val arr = org.json.JSONArray(Http.get(URL))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optBoolean("cancelled", false)) continue
                val areas = o.optJSONArray("areas") ?: continue
                val list = mutableListOf<TsunamiArea>()
                for (j in 0 until areas.length()) {
                    val a = areas.getJSONObject(j)
                    list.add(TsunamiArea(a.optString("name"), a.optString("grade")))
                }
                return list
            }
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun gradeText(grade: String): String = when (grade) {
        "MajorWarning" -> "🟣 大津波警報"
        "Warning" -> "🔴 津波警報"
        "Watch" -> "🟡 津波注意報"
        else -> grade
    }
}

/** The newest 552 telegram as-is (for the monitor service's own state tracking). */
data class TsunamiIssue(val id: String, val cancelled: Boolean, val areas: List<TsunamiArea>)

fun fetchLatestTsunamiIssue(): TsunamiIssue? {
    return try {
        val arr = org.json.JSONArray(Http.get("https://api.p2pquake.net/v2/history?codes=552&limit=1"))
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        val areas = mutableListOf<TsunamiArea>()
        val arrAreas = o.optJSONArray("areas")
        if (arrAreas != null) {
            for (j in 0 until arrAreas.length()) {
                val a = arrAreas.getJSONObject(j)
                areas.add(TsunamiArea(a.optString("name"), a.optString("grade")))
            }
        }
        TsunamiIssue(o.optString("id"), o.optBoolean("cancelled", false), areas)
    } catch (e: Exception) {
        null
    }
}

/** The embedded earthquake-viewer URL: built-in default, user-overridable. */
object ViewerConfig {
    const val DEFAULT_URL = "https://kotoho7.github.io/scratch-realtime-earthquake-viewer-page/"
    private const val PREFS = "ruialert"
    private const val KEY = "viewer_url"

    fun url(context: android.content.Context): String =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY, DEFAULT_URL) ?: DEFAULT_URL

    fun setUrl(context: android.content.Context, url: String?) {
        val e = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
        if (url.isNullOrBlank()) {
            e.remove(KEY)
        } else {
            e.putString(KEY, url.trim())
        }
        e.apply()
    }
}


/** JMA area master: municipality code -> name, for showing warnings of the user's own city. */
object AreaMaster {
    private const val AREA_URL = "https://www.jma.go.jp/bosai/common/const/area.json"
    private const val CACHE_FILE = "jma_area_class20.json"
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    @Volatile
    private var cached: Map<String, String>? = null

    fun class20Names(context: android.content.Context): Map<String, String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            readDisk(context)?.let {
                cached = it
                return it
            }
            val fetched = try {
                parse(Http.get(AREA_URL))
            } catch (e: Exception) {
                emptyMap()
            }
            if (fetched.isNotEmpty()) {
                writeDisk(context, fetched)
                cached = fetched
            }
            return fetched
        }
    }

    private fun parse(json: String): Map<String, String> {
        val root = org.json.JSONObject(json)
        val c20 = root.getJSONObject("class20s")
        val out = HashMap<String, String>()
        val keys = c20.keys()
        while (keys.hasNext()) {
            val code = keys.next()
            out[code] = c20.getJSONObject(code).optString("name")
        }
        return out
    }

    private fun readDisk(context: android.content.Context): Map<String, String>? {
        return try {
            val f = java.io.File(context.filesDir, CACHE_FILE)
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > TTL_MS) return null
            val o = org.json.JSONObject(f.readText(Charsets.UTF_8))
            val out = HashMap<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.getString(k)
            }
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            null
        }
    }

    private fun writeDisk(context: android.content.Context, map: Map<String, String>) {
        try {
            val o = org.json.JSONObject()
            for ((k, v) in map) o.put(k, v)
            java.io.File(context.filesDir, CACHE_FILE).writeText(o.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // cache is best effort
        }
    }
}
