package com.blissplayer.music

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Last resort crash log. Writes the stack trace to internal storage and
 * lets the default handler kill the process. On the next launch the app
 * shows the log with a working Copy button and also saves a copy into the
 * Download folder, so the log can always be found and sent on.
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

    /**
     * Copy the saved crash into the Download folder.
     * Returns the file name on success, null when it could not be saved.
     */
    fun saveToDownloads(context: Context): String? {
        val log = read(context) ?: return null
        return try {
            val name = "BlissPlayer-crash.txt"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(log.toByteArray())
                } ?: return null
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, name).writeText(log)
            }
            name
        } catch (e: Exception) {
            null
        }
    }
}
