package com.rui.nervlive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
* Main screen with plain Android Views and a bottom tab bar.
* Tabs: home (status board + demo buttons), quake (hypocenter map,
* per-prefecture intensities, tsunami info), history (sent notifications).
*/
class MainActivity: android.app.Activity() {

@Volatile
private var destroyed = false

private lateinit var boardText: TextView
private lateinit var mapView: MapView
private lateinit var quakeDetailText: TextView
private lateinit var tsunamiText: TextView
private lateinit var locLabel: TextView
private lateinit var historyList: LinearLayout
private lateinit var dndStatus: TextView

private lateinit var pageHome: View
private lateinit var pageQuake: View
private lateinit var pageHistory: View
private lateinit var tabHome: TextView
private lateinit var tabQuake: TextView
private lateinit var tabHistory: TextView
private lateinit var tabWeb: TextView
private lateinit var pageWeb: View
private lateinit var webView: android.webkit.WebView
private lateinit var webError: View
private var webLoaded = false

@Volatile
private var detail: QuakeDetail? = null

@Volatile
private var tsunamiAreas: List<TsunamiArea> = emptyList()

@Volatile
private var weather: WeatherInfo? = null

@Volatile
private var warnings: List<String> = emptyList()

@Volatile
private var warningsLoaded = false
private var cityWarnings: List<String>? = null
private var hasCrashDump = false

@Volatile
private var locSnapshot: LocationStore.Loc? = null

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
Diag.crumb(this, "activity.onCreate.begin")
CrashLog.install(this)

// A crash record from a previous run never takes over the screen.
// It is surfaced as a small notice on the Home page instead.
hasCrashDump = CrashLog.read(this) != null

Notify.ensureChannels(this)
if (Build.VERSION.SDK_INT >= 33 &&
checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!= PackageManager.PERMISSION_GRANTED
) {
requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
}

val root = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundColor(Color.rgb(11, 14, 19))
layoutParams = ViewGroup.LayoutParams(
ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
)
}

// ---- Home page ----
val homeContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL}
val demoLabel = TextView(this).apply {
text = "デモ通知（テスト用）"
textSize = 12f
setTextColor(Color.rgb(160, 170, 185))
setPadding(40, 10, 40, 8)
}
fun demoButton(label: String, action: () -> Unit): android.widget.Button =
android.widget.Button(this).apply {
text = label
textSize = 12f
setTextColor(Color.WHITE)
setBackgroundColor(Color.rgb(30, 36, 48))
setOnClickListener { action()}
}
val demoRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL}
demoRow1.addView(demoButton("EEW警報デモ") {
Notify.notifyEew(this@MainActivity, true, "駿河湾（デモ）", 6.8, "愛知県西部", "5弱", 45)
}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
demoRow1.addView(demoButton("EEW予報デモ") {
Notify.notifyEew(this@MainActivity, false, "静岡県沖（デモ）", 5.6, "愛知県西部", "4", null)
}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
val demoRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL}
demoRow2.addView(demoButton("特別警報デモ") {
Notify.notifyWeather(this@MainActivity, "大雨特別警報", "名古屋市に大雨特別警報が発表されました（デモ通知です）", true)
}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
demoRow2.addView(demoButton("線状降水帯デモ") {
Notify.notifyWeather(this@MainActivity, "顕著な大雨に関する情報", "愛知県で線状降水帯による大雨が発生しています（デモ通知です）", false)
}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
val demoRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL}
demoRow3.addView(demoButton("津波注意報デモ") {
Notify.notifyTsunami(this@MainActivity, "Advisory", "愛知県外海（デモ）")
}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
demoRow3.addView(demoButton("津波警報デモ") {
Notify.notifyTsunami(this@MainActivity, "Warning", "愛知県外海（デモ）")
}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
demoRow3.addView(demoButton("大津波警報デモ") {
Notify.notifyTsunami(this@MainActivity, "MajorWarning", "愛知県外海（デモ）")
}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
boardText = TextView(this).apply {
text = "読み込み中…"
textSize = 15f
setTextColor(Color.rgb(225, 230, 238))
setPadding(40, 20, 40, 40)
setLineSpacing(6f, 1.15f)
}
homeContent.addView(demoLabel)
homeContent.addView(demoRow1)
homeContent.addView(demoRow2)
homeContent.addView(demoRow3)
if (hasCrashDump) {
val crashLabel = TextView(this).apply {
text = "前回、エラーで終了した記録があります"
textSize = 12f
setTextColor(Color.rgb(255, 150, 150))
setPadding(40, 16, 40, 4)
}
homeContent.addView(crashLabel)
val crashRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL}
crashRow.addView(demoButton("エラー記録を見る") {
val dumpText = CrashLog.read(this@MainActivity)?: ""
android.app.AlertDialog.Builder(this@MainActivity)
.setTitle("前回のエラー記録")
.setMessage(dumpText.take(3000))
.setPositiveButton("閉じる", null)
.setNeutralButton("記録を消す", android.content.DialogInterface.OnClickListener { _, _ ->
CrashLog.clear(this@MainActivity)
crashRow.visibility = android.view.View.GONE
crashLabel.visibility = android.view.View.GONE
})
.show()
}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
crashRow.addView(demoButton("記録を消す") {
CrashLog.clear(this@MainActivity)
crashRow.visibility = android.view.View.GONE
crashLabel.visibility = android.view.View.GONE
}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
homeContent.addView(crashRow)
}
val dndLabel = TextView(this).apply {
text = "重要通知（津波・EEW警報など）"
textSize = 12f
setTextColor(Color.rgb(160, 170, 185))
setPadding(40, 14, 40, 4)
}
dndStatus = TextView(this).apply {
textSize = 12f
setTextColor(Color.rgb(200, 208, 220))
setPadding(40, 0, 40, 8)
}
val dndBtn = android.widget.Button(this).apply {
text = "サイレント自動解除の許可を設定"
textSize = 12f
setTextColor(Color.WHITE)
setBackgroundColor(Color.rgb(30, 36, 48))
setOnClickListener {
try {
startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
} catch (e: Exception) {
}
}
}
homeContent.addView(dndLabel)
homeContent.addView(dndStatus)
homeContent.addView(dndBtn)
val updateBtn = android.widget.Button(this).apply {
text = "アプリの更新を確認（GitHub）"
textSize = 12f
setTextColor(Color.WHITE)
setBackgroundColor(Color.rgb(30, 36, 48))
setOnClickListener { checkForUpdate(manual = true)}
}
homeContent.addView(updateBtn)
homeContent.addView(ScrollView(this).apply { addView(boardText)})
pageHome = homeContent

// ---- Quake page ----
val quakeContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL}
mapView = MapView(this).apply {
setBackgroundColor(Color.rgb(16, 21, 30))
}
val screenW = resources.displayMetrics.widthPixels
quakeDetailText = TextView(this).apply {
text = "地震情報を取得中…"
textSize = 15f
setTextColor(Color.rgb(225, 230, 238))
setPadding(40, 20, 40, 8)
setLineSpacing(6f, 1.15f)
}
tsunamiText = TextView(this).apply {
text = ""
textSize = 15f
setTextColor(Color.rgb(225, 230, 238))
setPadding(40, 8, 40, 40)
setLineSpacing(6f, 1.15f)
}
quakeContent.addView(mapView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screenW * 0.92).toInt()))
quakeContent.addView(quakeDetailText)
quakeContent.addView(tsunamiText)
pageQuake = ScrollView(this).apply { addView(quakeContent)}

// ---- History page ----
val historyContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL}
val clearBtn = android.widget.Button(this).apply {
text = "ロック以外を全て消す"
textSize = 12f
setTextColor(Color.WHITE)
setBackgroundColor(Color.rgb(30, 36, 48))
setOnClickListener {
NotifiedLog.clearUnlocked(this@MainActivity)
refreshHistory()
}
}
historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL}
historyContent.addView(clearBtn)
historyContent.addView(historyList)
pageHistory = ScrollView(this).apply { addView(historyContent)}

val pages = FrameLayout(this)
pages.addView(pageHome)
pages.addView(pageQuake)
pages.addView(pageHistory)
pageQuake.visibility = View.GONE
pageHistory.visibility = View.GONE
val webContainer = FrameLayout(this)
webView = android.webkit.WebView(this).apply {
settings.javaScriptEnabled = true
settings.domStorageEnabled = true
settings.loadWithOverviewMode = true
settings.useWideViewPort = true
settings.mediaPlaybackRequiresUserGesture = false
setLayerType(View.LAYER_TYPE_HARDWARE, null)
webViewClient = object : android.webkit.WebViewClient() {
override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
webError.visibility = View.GONE
// The embedded viewer (TurboWarp/Scratch) needs WebGL; probe the environment.
view?.evaluateJavascript(
"(function(){try{var c=document.createElement('canvas');return (c.getContext('webgl2')||c.getContext('webgl'))?'OK':'NG';}catch(e){return 'ERR';}})()"
) { result ->
if (result != null && !result.contains("OK")) {
android.widget.Toast.makeText(
this@MainActivity,
"WebGLが使えていないようです。ビューアが表示されない場合は教えてください",
android.widget.Toast.LENGTH_LONG
).show()
}
}
}
override fun onReceivedError(
view: android.webkit.WebView?,
request: android.webkit.WebResourceRequest?,
error: android.webkit.WebResourceError?
) {
if (request?.isForMainFrame == true) webError.visibility = View.VISIBLE
}
override fun onReceivedHttpError(
view: android.webkit.WebView?,
request: android.webkit.WebResourceRequest?,
errorResponse: android.webkit.WebResourceResponse?
) {
if (request?.isForMainFrame == true) webError.visibility = View.VISIBLE
}
}
}
webError = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
gravity = Gravity.CENTER
setBackgroundColor(Color.rgb(16, 20, 28))
visibility = View.GONE
addView(TextView(this@MainActivity).apply {
text = "ビューアのページを開けませんでした"
textSize = 15f
setTextColor(Color.WHITE)
gravity = Gravity.CENTER
setPadding(40, 20, 40, 8)
})
addView(TextView(this@MainActivity).apply {
text = "通信状態を確認して、再読み込みをお試しください"
textSize = 12f
setTextColor(Color.rgb(160, 170, 185))
gravity = Gravity.CENTER
setPadding(40, 0, 40, 20)
})
addView(android.widget.Button(this@MainActivity).apply {
text = "再読み込み"
setOnClickListener {
webError.visibility = View.GONE
webView.loadUrl(ViewerConfig.url(this@MainActivity))
}
})
}
webContainer.addView(webView)
webContainer.addView(webError)
pageWeb = webContainer
pages.addView(pageWeb)
pageWeb.visibility = View.GONE
locLabel = TextView(this).apply {
text = "📍 現在地：取得中…"
textSize = 12f
setTextColor(Color.rgb(160, 170, 185))
setPadding(40, 0, 40, 10)
}
root.addView(locLabel)
root.addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

// ---- Bottom tab bar ----
val tabBar = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setBackgroundColor(Color.rgb(22, 27, 36))
setPadding(16, 12, 16, 12)
}
fun makeTab(label: String): TextView = TextView(this).apply {
text = label
textSize = 13f
gravity = Gravity.CENTER
setPadding(8, 18, 8, 18)
}
tabHome = makeTab("🏠 ホーム")
tabQuake = makeTab("🗾 地震・津波")
tabHistory = makeTab("🔔 通知履歴")
tabWeb = makeTab("🌐 ビューア")
tabHome.setOnClickListener { selectTab(0)}
tabQuake.setOnClickListener { selectTab(1)}
tabHistory.setOnClickListener { selectTab(2); refreshHistory()}
tabWeb.setOnLongClickListener {
val input = android.widget.EditText(this).apply {
setText(ViewerConfig.url(this@MainActivity))
setSingleLine(true)
}
android.app.AlertDialog.Builder(this)
.setTitle("ビューアのURL設定")
.setView(input)
.setPositiveButton("保存して開く") { _, _ ->
ViewerConfig.setUrl(this, input.text.toString())
webLoaded = true
selectTab(3)
webView.loadUrl(ViewerConfig.url(this))
}
.setNeutralButton("既定に戻す") { _, _ ->
ViewerConfig.setUrl(this, null)
webLoaded = true
selectTab(3)
webView.loadUrl(ViewerConfig.url(this))
}
.setNegativeButton("キャンセル", null)
.show()
true
}
tabWeb.setOnClickListener {
selectTab(3)
if (!webLoaded) {
webLoaded = true
webView.loadUrl(ViewerConfig.url(this@MainActivity))
}
}
tabBar.addView(tabHome, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
tabBar.addView(tabQuake, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
tabBar.addView(tabHistory, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
tabBar.addView(tabWeb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
root.addView(tabBar)
selectTab(0)

setContentView(root)
root.setOnApplyWindowInsetsListener { v, insets ->
val bars = insets.getInsets(WindowInsets.Type.systemBars())
v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
insets
}
Diag.crumb(this, "activity.uiSet")

checkForUpdate(manual = false)

if (!LocationStore.hasPermission(this)) {
try {
requestPermissions(
arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
1001
)
} catch (e: Exception) {
}
}

window.decorView.post {
Diag.firstFrame = true
Diag.crumb(this, "activity.firstFramePosted")
try {
ContextCompat.startForegroundService(
this, Intent(this, MonitorService::class.java)
)
Diag.crumb(this, "activity.serviceStarted")
} catch (e: Exception) {
try {
java.io.File(filesDir, "last_crash.txt").writeText(
"[service start failed]\n" + android.util.Log.getStackTraceString(e)
)
} catch (ignored: Exception) {
}
}
}

startLoops()
}

private fun checkForUpdate(manual: Boolean) {
Thread {
val latest = Updater.fetchLatest()
val current = Updater.currentVersionCode(this)
runOnUiThread {
if (destroyed) return@runOnUiThread
when {
latest == null -> {
if (manual) {
android.widget.Toast.makeText(
this, "更新情報を取得できませんでした", android.widget.Toast.LENGTH_SHORT
).show()
}
}
latest.versionCode.toLong() <= current -> {
if (manual) {
android.widget.Toast.makeText(
this, "最新版です（v${latest.versionName}）", android.widget.Toast.LENGTH_SHORT
).show()
}
}
else -> {
android.app.AlertDialog.Builder(this)
.setTitle("アップデートがあります")
.setMessage("RuiAlert v${latest.versionName} が利用できます。今すぐ更新しますか？")
.setPositiveButton("更新する") { _, _ -> startUpdateDownload(latest)}
.setNegativeButton("後で", null)
.show()
}
}
}
}.start()
}

private fun startUpdateDownload(latest: Updater.Info) {
if (!Updater.canInstall(this)) {
android.widget.Toast.makeText(
this,
"最初に「このアプリからのインストール」を許可してください",
android.widget.Toast.LENGTH_LONG
).show()
try {
startActivity(Updater.unknownSourcesIntent(this))
} catch (e: Exception) {
}
return
}
val progress = android.app.AlertDialog.Builder(this)
.setTitle("ダウンロード中…")
.setMessage("RuiAlert v${latest.versionName} をダウンロードしています")
.setCancelable(false)
.show()
Thread {
val apk = Updater.downloadApk(this, latest.apkUrl)
runOnUiThread {
if (destroyed) return@runOnUiThread
progress.dismiss()
if (apk == null) {
android.widget.Toast.makeText(
this, "ダウンロードに失敗しました", android.widget.Toast.LENGTH_LONG
).show()
} else {
try {
startActivity(Updater.installIntent(this, apk))
} catch (e: Exception) {
android.widget.Toast.makeText(
this, "インストーラを開けませんでした", android.widget.Toast.LENGTH_LONG
).show()
}
}
}
}.start()
}

override fun onResume() {
super.onResume()
if (::dndStatus.isInitialized) {
dndStatus.text = if (DndBypass.isGranted(this)) {
"状態：許可済み（重要な通知のとき、自動でサイレントを解除します）"
} else {
"状態：未許可（アナウンス音声はアラーム音量で鳴ります）"
}
}
}

override fun onDestroy() {
destroyed = true
super.onDestroy()
}

private fun selectTab(index: Int) {
pageHome.visibility = if (index == 0) View.VISIBLE else View.GONE
pageQuake.visibility = if (index == 1) View.VISIBLE else View.GONE
pageHistory.visibility = if (index == 2) View.VISIBLE else View.GONE
pageWeb.visibility = if (index == 3) View.VISIBLE else View.GONE
val tabs = listOf(tabHome, tabQuake, tabHistory, tabWeb)
tabs.forEachIndexed { i, tab ->
if (i == index) {
tab.background = GradientDrawable().apply {
cornerRadius = 40f
setColor(Color.rgb(13, 92, 92))
}
tab.setTextColor(Color.WHITE)
tab.typeface = Typeface.DEFAULT_BOLD
} else {
tab.background = null
tab.setTextColor(Color.rgb(170, 180, 195))
tab.typeface = Typeface.DEFAULT
}
}
}

private fun refreshHistory() {
Thread {
val entries = NotifiedLog.read(this)
runOnUiThread {
if (destroyed) return@runOnUiThread
historyList.removeAllViews()
if (entries.isEmpty()) {
historyList.addView(TextView(this).apply {
text = "まだ通知はありません"
textSize = 14f
setTextColor(Color.rgb(225, 230, 238))
setPadding(40, 20, 40, 20)
})
return@runOnUiThread
}
val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)
entries.take(50).forEach { entry ->
val block = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(40, 16, 40, 16)
}
block.addView(TextView(this).apply {
text = "${fmt.format(Date(entry.time))}  ${if (entry.locked) "🔒 " else ""}${entry.title}"
textSize = 14f
setTextColor(Color.WHITE)
typeface = Typeface.DEFAULT_BOLD
})
block.addView(TextView(this).apply {
text = entry.text
textSize = 13f
setTextColor(Color.rgb(200, 208, 220))
})
val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL}
val lockBtn = android.widget.Button(this).apply {
text = if (entry.locked) "🔓 ロック解除" else "🔒 ロック"
textSize = 11f
setOnClickListener {
NotifiedLog.setLocked(this@MainActivity, entry, !entry.locked)
refreshHistory()
}
}
val delBtn = android.widget.Button(this).apply {
text = "🗑 削除"
textSize = 11f
setOnClickListener {
NotifiedLog.delete(this@MainActivity, entry)
refreshHistory()
}
}
row.addView(lockBtn)
row.addView(delBtn)
block.addView(row)
historyList.addView(block)
}
}
}.start()
}

private fun startLoops() {
// Quake detail + tsunami, every minute.
Thread {
while (!destroyed) {
try {
detail = QuakeDetailSource.fetch()
tsunamiAreas = TsunamiSource.fetchAreas()
renderAll()
} catch (e: Exception) {
// keep looping
}
sleep(60_000)
}
}.start()
// Weather + warnings, every 5 minutes.
Thread {
while (!destroyed) {
try {
val loc = LocationStore.current(this@MainActivity)
locSnapshot = loc
weather = WeatherSource.fetch(loc.prefCode)
val wi = try {
WarningSource.fetch(loc.prefCode)
} catch (e: Exception) {
null
}
warnings = wi?.allNames ?: emptyList()
val city = loc.cityName
cityWarnings = if (wi != null && city != null) {
val names = AreaMaster.class20Names(this@MainActivity)
val prefix = loc.prefCode.substring(0, 2)
val myCode = names.entries.firstOrNull { it.key.startsWith(prefix) && it.value == city }?.key
?: names.entries.firstOrNull {
it.key.startsWith(prefix) && (it.value.contains(city) || city.contains(it.value))
}?.key
if (myCode != null) wi.byArea[myCode] ?: emptyList() else null
} else {
null
}
warningsLoaded = true
renderAll()
} catch (e: Exception) {
// keep looping
}
sleep(300_000)
}
}.start()
}

private fun sleep(ms: Long) {
try {
Thread.sleep(ms)
} catch (e: InterruptedException) {
// ignore
}
}

private fun weatherIcon(text: String): String = when {
text.contains("雷") -> "⛈️"
text.contains("雪") -> "❄️"
text.contains("雨") && text.contains("晴") -> "🌦️"
text.contains("雨") -> "🌧️"
text.contains("晴") -> "☀️"
text.contains("曇") || text.contains("くもり") -> "☁️"
else -> "🌡️"
}

private fun renderAll() {
val l = locSnapshot
val locText = if (l == null) {
"📍 現在地：取得中…"
} else {
"📍 現在地：${l.prefName}${l.cityName?.let { c -> "・$c" } ?: ""}（${if (l.fromGps) "GPS" else "位置情報オフのため愛知県"}）"
}
if (::locLabel.isInitialized) {
runOnUiThread { if (!destroyed) locLabel.text = locText }
}
val time = SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date())
val d = detail
// Home board
val sb = StringBuilder()
sb.append("■ 震源情報\n")
if (d == null) {
sb.append("取得中…\n")
} else {
sb.append(d.hypocenter).append('\n')
sb.append("M").append(String.format(Locale.JAPAN, "%.1f", d.magnitude))
.append("／最大震度 ").append(shindoText(d.maxScale))
.append("／深さ ").append(d.depthKm).append("km\n")
sb.append(d.tsunami).append('\n')
sb.append(d.timeText).append('\n')
}
if (tsunamiAreas.isNotEmpty()) {
sb.append("\n■ 津波情報\n")
tsunamiAreas.groupBy { it.grade}.forEach { (grade, areas) ->
sb.append(TsunamiSource.gradeText(grade)).append("：")
.append(areas.joinToString("、") { it.name}).append('\n')
}
}
val w = weather
sb.append("\n■ 天気情報（").append(w?.areaName ?: "").append("）\n")
if (w == null) {
sb.append("取得中…\n")
} else {
sb.append("今日：").append(weatherIcon(w.todayText)).append(" ").append(w.todayText)
.append("（").append(w.minTemp).append("〜").append(w.maxTemp).append("℃）\n")
sb.append("明日：").append(weatherIcon(w.tomorrowText)).append(" ").append(w.tomorrowText).append('\n')
}
sb.append("\n■ 注意報・警報（").append(locSnapshot?.prefName ?: "愛知県").append("）\n")
val cw = cityWarnings
val cn = locSnapshot?.cityName
if (cw != null && cn != null) {
sb.append("\uD83D\uDCCD").append(cn).append("：")
if (cw.isEmpty()) {
sb.append("発表中の注意報・警報はありません\n")
} else {
sb.append(cw.joinToString("、")).append('\n')
}
}
when {
!warningsLoaded -> sb.append("取得中…\n")
warnings.isEmpty() -> sb.append("発表中の注意報・警報はありません\n")
else -> warnings.forEach { sb.append("・").append(it).append('\n')}
}
sb.append("\n（").append(time).append(" 時点）")
val board = sb.toString()

// Quake page detail
val qd = StringBuilder()
if (d == null) {
qd.append("地震情報を取得中…")
} else {
qd.append("■ 詳しい震源情報\n")
qd.append("震源地：").append(d.hypocenter).append('\n')
qd.append("規模：M").append(String.format(Locale.JAPAN, "%.1f", d.magnitude))
.append("／深さ ").append(d.depthKm).append("km\n")
qd.append("最大震度：").append(shindoText(d.maxScale)).append('\n')
qd.append("発生時刻：").append(d.timeText).append('\n')
qd.append("津波：").append(d.tsunami).append("\n\n")
qd.append("■ 観測された揺れ（都道府県ごと）\n")
if (d.prefScales.isEmpty()) {
qd.append("観測情報はまだありません")
} else {
qd.append(
d.prefScales.entries.sortedByDescending { it.value}
.joinToString("、") { "${it.key}（震度${shindoText(it.value)}）"}
)
}
}
val quakeDetail = qd.toString()

val ts = StringBuilder()
ts.append("■ 津波情報\n")
if (tsunamiAreas.isEmpty()) {
ts.append("大津波警報・津波警報・津波注意報は発表されていません")
} else {
tsunamiAreas.groupBy { it.grade}.forEach { (grade, areas) ->
ts.append(TsunamiSource.gradeText(grade)).append('\n')
areas.forEach { a ->
val aichi = if (a.name.contains("愛知") || a.name.contains("伊勢") || a.name.contains("三河")) " ⚠愛知" else ""
ts.append("・").append(a.name).append(aichi).append('\n')
}
}
}
val tsunami = ts.toString()

runOnUiThread {
if (destroyed) return@runOnUiThread
boardText.text = board
quakeDetailText.text = quakeDetail
tsunamiText.text = tsunami
if (d!= null) {
mapView.prefScales = d.prefScales
mapView.hypoLat = d.latitude
mapView.hypoLon = d.longitude
mapView.invalidate()
}
}
}
}
