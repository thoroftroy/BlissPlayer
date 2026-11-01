package com.blissplayer.music

import java.io.File

/** Private report storage. A pending report is consumed before displaying it. */
internal class CrashLogStore(directory: File) {
    private val pending = File(directory, "crash_last.txt") // also migrates old installs
    private val previous = File(directory, "crash_report.txt")

    fun record(log: String) = pending.writeText(log)

    fun read(): String? = when {
        pending.isFile -> pending.readText()
        previous.isFile -> previous.readText()
        else -> null
    }

    fun takePending(): String? {
        if (!pending.isFile) return null
        val log = pending.readText()
        previous.writeText(log)
        check(pending.delete()) { "Could not acknowledge crash report" }
        return log
    }

    fun clear() {
        pending.delete()
        previous.delete()
    }
}
