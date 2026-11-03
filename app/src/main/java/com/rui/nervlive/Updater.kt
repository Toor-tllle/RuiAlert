package com.rui.nervlive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Self-update via GitHub: version.json on the repo, APK from GitHub Releases. */
object Updater {
    private const val VERSION_URL =
        "https://raw.githubusercontent.com/Toor-tllle/RuiAlert/main/version.json"

    data class Info(val versionCode: Int, val versionName: String, val apkUrl: String)

    fun fetchLatest(): Info? {
        return try {
            val json = Http.get("$VERSION_URL?t=${System.currentTimeMillis()}")
            val o = JSONObject(json)
            Info(
                versionCode = o.getInt("versionCode"),
                versionName = o.optString("versionName"),
                apkUrl = o.getString("apkUrl")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun currentVersionCode(context: Context): Long {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (e: Exception) {
            0
        }
    }

    /** Downloads the APK into the app cache. Returns the file, or null on failure. */
    fun downloadApk(context: Context, url: String): File? {
        return try {
            val dest = File(context.cacheDir, "ruialert-update.apk")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "RuiAlert-Updater")
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    input.copyTo(out, 64 * 1024)
                }
            }
            if (dest.length() > 100_000) dest else null
        } catch (e: Exception) {
            null
        }
    }

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName)
        )

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context, "com.rui.nervlive.fileprovider", apk
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
