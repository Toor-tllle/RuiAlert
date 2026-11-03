package com.rui.nervlive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import org.json.JSONObject

/**
 * Simplified Japan map: prefecture polygons from the bundled GeoJSON,
 * filled by the observed max seismic intensity per prefecture,
 * with the hypocenter marked by a cross.
 * Display window is mainland-centred (remote islands are clipped).
 */
class MapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile
    var prefScales: Map<String, Int> = emptyMap()
        set(value) {
            field = value
            postInvalidate()
        }

    @Volatile
    var hypoLat: Double = 0.0

    @Volatile
    var hypoLon: Double = 0.0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.rgb(90, 102, 124)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(255, 64, 60)
        strokeCap = Paint.Cap.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.argb(160, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }

    companion object {
        // name -> rings; each ring is [lon0, lat0, lon1, lat1, ...]
        @Volatile
        private var cached: List<Pair<String, List<FloatArray>>>? = null

        @Volatile
        private var loading = false

        private const val MIN_LON = 128.5
        private const val MAX_LON = 146.5
        private const val MIN_LAT = 30.8
        private const val MAX_LAT = 45.6
        private const val LON_SHRINK = 0.79 // cos(about 36 deg N)

        fun shindoColor(scale: Int): Int = when (scale) {
            10 -> Color.rgb(127, 153, 184)
            20 -> Color.rgb(79, 195, 247)
            30 -> Color.rgb(30, 136, 229)
            40 -> Color.rgb(255, 235, 59)
            45 -> Color.rgb(255, 152, 0)
            50 -> Color.rgb(244, 81, 30)
            55 -> Color.rgb(229, 57, 53)
            60 -> Color.rgb(183, 28, 28)
            70 -> Color.rgb(142, 36, 170)
            else -> Color.rgb(40, 47, 60)
        }
    }

    private fun ensureLoaded() {
        if (cached != null || loading) return
        loading = true
        val appContext = context.applicationContext
        Thread {
            try {
                val text = appContext.assets.open("prefectures.geojson")
                    .bufferedReader().readText()
                val root = JSONObject(text)
                val features = root.getJSONArray("features")
                val result = mutableListOf<Pair<String, List<FloatArray>>>()
                for (i in 0 until features.length()) {
                    val f = features.getJSONObject(i)
                    val name = f.getJSONObject("properties").getString("name")
                    val polys = f.getJSONObject("geometry").getJSONArray("coordinates")
                    val rings = mutableListOf<FloatArray>()
                    for (p in 0 until polys.length()) {
                        val poly = polys.getJSONArray(p)
                        for (r in 0 until poly.length()) {
                            val ring = poly.getJSONArray(r)
                            val arr = FloatArray(ring.length() * 2)
                            for (k in 0 until ring.length()) {
                                val pt = ring.getJSONArray(k)
                                arr[k * 2] = pt.getDouble(0).toFloat()
                                arr[k * 2 + 1] = pt.getDouble(1).toFloat()
                            }
                            rings.add(arr)
                        }
                    }
                    result.add(name to rings)
                }
                cached = result
            } catch (e: Exception) {
                // Map stays blank; the text details still show.
            } finally {
                loading = false
                postInvalidate()
            }
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureLoaded()
        val data = cached ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val lonRange = (MAX_LON - MIN_LON) * LON_SHRINK
        val latRange = MAX_LAT - MIN_LAT
        val scale = minOf(w / lonRange.toFloat(), h / latRange.toFloat())
        val mapW = (lonRange * scale).toFloat()
        val mapH = (latRange * scale).toFloat()
        val offX = (w - mapW) / 2f
        val offY = (h - mapH) / 2f

        fun x(lon: Double): Float = offX + ((lon - MIN_LON) * LON_SHRINK * scale).toFloat()
        fun y(lat: Double): Float = offY + ((MAX_LAT - lat) * scale).toFloat()

        val path = Path()
        for ((name, rings) in data) {
            path.reset()
            for (ring in rings) {
                val n = ring.size / 2
                if (n < 2) continue
                path.moveTo(x(ring[0].toDouble()), y(ring[1].toDouble()))
                for (k in 1 until n) {
                    path.lineTo(x(ring[k * 2].toDouble()), y(ring[k * 2 + 1].toDouble()))
                }
                path.close()
            }
            fillPaint.color = shindoColor(prefScales[name] ?: 0)
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        }

        if (hypoLat != 0.0 && hypoLon != 0.0) {
            val cx = x(hypoLon)
            val cy = y(hypoLat)
            val r = 14f
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, haloPaint)
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, haloPaint)
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, crossPaint)
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, crossPaint)
        }
    }
}
