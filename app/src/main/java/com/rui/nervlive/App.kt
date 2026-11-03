package com.rui.nervlive

import android.app.Application
import android.content.Context
import java.io.File

/** Startup diagnostics: breadcrumbs + a watchdog that dumps every thread if the first frame never arrives. */
object Diag {
    @Volatile
    var firstFrame: Boolean = false

    private const val LOG = "startup_log.txt"
    private const val DUMP = "last_crash.txt"

    fun crumb(context: Context, tag: String) {
        try {
            val line = "${System.currentTimeMillis()} $tag\n"
            File(context.filesDir, LOG).appendText(line)
        } catch (e: Exception) {
            // best effort
        }
    }

    fun readLog(context: Context): String? = try {
        val f = File(context.filesDir, LOG)
        if (f.exists() && f.length() > 0) f.readText() else null
    } catch (e: Exception) {
        null
    }

    fun dumpAllThreads(context: Context, reason: String) {
        try {
            val sb = StringBuilder()
            sb.append("=== THREAD DUMP: ").append(reason).append(" ===\n")
            for ((thread, stack) in Thread.getAllStackTraces()) {
                sb.append("\n--- \"").append(thread.name).append("\" ").append(thread.state).append(" ---\n")
                for (el in stack) sb.append("  at ").append(el.toString()).append('\n')
            }
            File(context.filesDir, DUMP).writeText(sb.toString())
        } catch (e: Exception) {
            // best effort
        }
    }
}

/** Records the last uncaught exception so the next launch can show what went wrong. */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val dir = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = android.util.Log.getStackTraceString(error)
                File(dir, FILE).writeText(text)
            } catch (e: Exception) {
                // best effort
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = try {
        val f = File(context.filesDir, FILE)
        if (f.exists() && f.length() > 0) f.readText() else null
    } catch (e: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE).delete()
        } catch (e: Exception) {
            // ignore
        }
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Diag.crumb(this, "app.onCreate")
        // Watchdog: if the first frame has not been drawn after 8 seconds,
        // record where every thread is stuck so the next launch can show it.
        Thread {
            try {
                Thread.sleep(8_000)
                if (!Diag.firstFrame) {
                    Diag.dumpAllThreads(this@App, "first frame not drawn within 8s")
                }
            } catch (e: Exception) {
                // ignore
            }
        }.start()
    }
}
