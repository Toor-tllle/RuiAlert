package com.rui.nervlive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/** Resolves the user's current prefecture from GPS/network location. */
object LocationStore {
    data class Loc(
        val prefName: String,
        val prefCode: String,
        val cityName: String?,
        val fromGps: Boolean
    )

    val PREF_CODES = mapOf(
        "北海道" to "011000", "青森県" to "020000", "岩手県" to "030000", "宮城県" to "040000",
        "秋田県" to "050000", "山形県" to "060000", "福島県" to "070000", "茨城県" to "080000",
        "栃木県" to "090000", "群馬県" to "100000", "埼玉県" to "110000", "千葉県" to "120000",
        "東京都" to "130000", "神奈川県" to "140000", "新潟県" to "150000", "富山県" to "160000",
        "石川県" to "170000", "福井県" to "180000", "山梨県" to "190000", "長野県" to "200000",
        "岐阜県" to "210000", "静岡県" to "220000", "愛知県" to "230000", "三重県" to "240000",
        "滋賀県" to "250000", "京都府" to "260000", "大阪府" to "270000", "兵庫県" to "280000",
        "奈良県" to "290000", "和歌山県" to "300000", "鳥取県" to "310000", "島根県" to "320000",
        "岡山県" to "330000", "広島県" to "340000", "山口県" to "350000", "徳島県" to "360000",
        "香川県" to "370000", "愛媛県" to "380000", "高知県" to "390000", "福岡県" to "400000",
        "佐賀県" to "410000", "長崎県" to "420000", "熊本県" to "430000", "大分県" to "440000",
        "宮崎県" to "450000", "鹿児島県" to "460000", "沖縄県" to "470000"
    )

    private val FALLBACK = Loc("愛知県", "230000", null, false)

    @Volatile
    private var cached: Loc = FALLBACK

    @Volatile
    private var cachedGrid: String = ""

    @Volatile
    private var cachedAt: Long = 0

    private var geoFeatures: List<Pair<String, List<List<List<Pair<Double, Double>>>>>>? = null

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Current prefecture. Cheap when cached: re-resolves at most every 10 minutes,
     * or when the device moved into a different ~0.2-degree grid cell.
     * Falls back to Aichi when there is no permission or no fix.
     * Call from background threads (geocoding / polygon matching can block).
     */
    fun current(context: Context): Loc {
        if (!hasPermission(context)) return FALLBACK
        val lm = context.getSystemService(LocationManager::class.java) ?: return cached
        val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p ->
                try {
                    lm.getLastKnownLocation(p)
                } catch (e: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time } ?: return cached
        val grid = "${(loc.latitude * 5).roundToInt()},${(loc.longitude * 5).roundToInt()}"
        val now = System.currentTimeMillis()
        if (grid == cachedGrid && now - cachedAt < 10 * 60_000) return cached
        val pref = prefectureAt(context, loc.longitude, loc.latitude)
        cachedGrid = grid
        cachedAt = now
        cached = if (pref != null) {
            val city = try {
                Geocoder(context, Locale.JAPAN)
                    .getFromLocation(loc.latitude, loc.longitude, 1)
                    ?.firstOrNull()?.locality
            } catch (e: Exception) {
                null
            }
            Loc(pref, PREF_CODES[pref] ?: "230000", city, true)
        } else {
            FALLBACK
        }
        return cached
    }

    private fun loadGeo(
        context: Context
    ): List<Pair<String, List<List<List<Pair<Double, Double>>>>>> {
        geoFeatures?.let { return it }
        val text = context.assets.open("prefectures.geojson").bufferedReader().readText()
        val features = JSONObject(text).getJSONArray("features")
        val out = mutableListOf<Pair<String, List<List<List<Pair<Double, Double>>>>>>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val name = f.getJSONObject("properties").getString("name")
            val geom = f.getJSONObject("geometry")
            val coords = geom.getJSONArray("coordinates")
            val polys = mutableListOf<List<List<Pair<Double, Double>>>>()
            if (geom.getString("type") == "Polygon") {
                polys.add(readPolygon(coords))
            } else {
                for (p in 0 until coords.length()) {
                    polys.add(readPolygon(coords.getJSONArray(p)))
                }
            }
            out.add(name to polys)
        }
        geoFeatures = out
        return out
    }

    private fun readPolygon(polyArr: org.json.JSONArray): List<List<Pair<Double, Double>>> {
        val rings = mutableListOf<List<Pair<Double, Double>>>()
        for (r in 0 until polyArr.length()) {
            val ringArr = polyArr.getJSONArray(r)
            val ring = mutableListOf<Pair<Double, Double>>()
            for (k in 0 until ringArr.length()) {
                val pt = ringArr.getJSONArray(k)
                ring.add(pt.getDouble(0) to pt.getDouble(1))
            }
            rings.add(ring)
        }
        return rings
    }

    private fun prefectureAt(context: Context, lon: Double, lat: Double): String? {
        return try {
            for ((name, polys) in loadGeo(context)) {
                for (poly in polys) {
                    if (poly.isNotEmpty() && inRing(lon, lat, poly[0])) return name
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun inRing(x: Double, y: Double, ring: List<Pair<Double, Double>>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (xi, yi) = ring[i]
            val (xj, yj) = ring[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
