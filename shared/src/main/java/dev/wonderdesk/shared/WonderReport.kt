package dev.wonderdesk.shared

/**
 * The one way anything in the suite reports to the developer: crashes, freezes and feedback.
 * Full reports (with the device and version details needed to fix things) go only to the developer's private inbox.
 * Anything that goes anywhere public, such as a GitHub issue, uses [publicText], which has no device or personal details.
 * The two are tied together by a short report id.
 */
object WonderReport {
    private const val INBOX = "https://ntfy.sh/wonderdesk-crash-062c8350c3af4072"

    fun newId(): String = java.util.UUID.randomUUID().toString().take(8)

    /** Takes out file paths, links and email addresses. */
    fun redact(s: String): String = s
        .replace(Regex("""/(storage|sdcard|mnt|data/user/\d+|data/data)/[^\s:'"),\]]+"""), "/…/[path]")
        .replace(Regex("""content://[^\s'"),\]]+"""), "content://[link]")
        .replace(Regex("""https?://[^\s'"),\]]+"""), "[web link]")
        .replace(Regex("""[\w.+-]+@[\w-]+\.[\w.]+"""), "[email]")

    /** Sends a full report to the private inbox. Returns true when it was accepted. */
    fun post(title: String, body: String, tags: String): Boolean = runCatching {
        (java.net.URL(INBOX).openConnection() as java.net.HttpURLConnection).run {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Title", title.replace(Regex("[^\\x20-\\x7E]"), " ").take(120)); setRequestProperty("Tags", tags)
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            val bytes = body.toByteArray(); outputStream.use { it.write(if (bytes.size > 3800) bytes.copyOf(3800) else bytes) }
            val code = responseCode; disconnect(); code in 200..299
        }
    }.getOrDefault(false)

    /** What may be shown in public: the app, its version and what the person wrote, with private details removed and the report id to match it up. */
    fun publicText(id: String, apps: String, versions: String, message: String): String =
        "App: $apps\nVersion: $versions\n\n${redact(message).trim()}\n\nReport ID: $id"
}
