package com.blissplayer.music

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Last resort crash log. Writes the stack trace to internal storage and
 * lets the default handler kill the process. On the next launch the app
 * offers the log once. Nothing is written to shared storage without the
 * user choosing an export destination.
 */
object CrashReporter {
    private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val v = runCatching {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull() ?: "?"
                val info = StringBuilder()
                    .append("BlissPlayer ").append(v)
                    .append(" API ").append(Build.VERSION.SDK_INT)
                    .append(' ').append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append("\nTime: ").append(java.time.Instant.now())
                    .append("\nThread: ").append(t.name)
                    .append("\n").append(sw.toString())
                    .toString()
                CrashLogStore(app.filesDir).record(info)
            }
            prev?.uncaughtException(t, e)
            if (prev == null) android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    fun read(context: Context): String? = runCatching {
        CrashLogStore(context.filesDir).read()
    }.getOrNull()

    fun takePending(context: Context): String? = runCatching {
        CrashLogStore(context.filesDir).takePending()
    }.getOrNull()

    fun clear(context: Context) = runCatching { CrashLogStore(context.filesDir).clear() }
}
