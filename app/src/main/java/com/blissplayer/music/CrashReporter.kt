package com.blissplayer.music

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Last resort crash log. Writes the stack trace to internal storage and
 * lets the default handler kill the process. On the next launch the app
 * shows the log with a Copy button so it can be pasted to the developer.
 */
object CrashReporter {
    private const val FILE = "crash_last.txt"

    fun install(context: Context) {
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
                    .append("\nThread: ").append(t.name)
                    .append("\n").append(sw.toString())
                    .toString()
                app.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(info.toByteArray()) }
            }
            prev?.uncaughtException(t, e)
            if (prev == null) android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    fun read(context: Context): String? = runCatching {
        context.openFileInput(FILE).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { context.deleteFile(FILE) }
    }
}
