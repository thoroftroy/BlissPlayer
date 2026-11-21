package com.blissplayer.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*

/** Bliss Player's Settings and About in the same style as the rest of the Wonderdesk apps. Its own music settings stay in the More tab. */
class SuiteActivity : WonderActivity() {
    @Composable override fun Content() { SuiteScreen(onBack = { finish() }) }
}

private val SUITE = listOf(
    Triple("Launcher", "dev.wonderdesk.launcher", "The Aqua home screen"), Triple("Beacon", "dev.wonderdesk.beacon", "Search and all your apps"),
    Triple("Memories", "dev.wonderdesk.memories", "Photos, videos and screen recorder"),
    Triple("Scribble", "dev.wonderdesk.scribble", "Notes and sketches"), Triple("Ripple", "dev.wonderdesk.ripple", "Your computer's terminal and power"),
    Triple("Wayfinder", "dev.wonderdesk.wayfinder", "Files and network folders"), Triple("Vault", "dev.wonderdesk.vault", "Passwords and 2FA"),
    Triple("Polaris", "dev.wonderdesk.polaris", "The web browser"), Triple("Control Room", "dev.wonderdesk.controlroom", "Look and feel for every app"),
)

private fun launch(c: Context, pkg: String): Boolean {
    val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
    return runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

@Composable
private fun SuiteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val toast: (String) -> Unit = { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    val prefs = remember { context.getSharedPreferences("bliss", Context.MODE_PRIVATE) }
    var tick by remember { mutableIntStateOf(0) }
    WonderApp(appName = "Bliss Player", about = "A music player with even loudness, themes and a retro deck. It is part of the Wonderdesk suite, so updates, feedback and the look come from the suite's apps.", title = "Settings and about", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(title = "About") {
                ListRow("Bliss Player $version", subtitle = "Part of the Wonderdesk suite", icon = WonderIcons.Info, tint = Color(0xFF1E7FE8)); PanelDivider()
                ListRow("What's new", subtitle = "Release notes for every version", icon = WonderIcons.History, tint = Color(0xFF7A7F87), chevron = true,
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thoroftroy/BlissPlayer/releases")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }); PanelDivider()
                ListRow("Check for updates", subtitle = "Opens Wonderdesk App Hub", icon = WonderIcons.SystemUpdate, tint = Color(0xFF2E9E44), chevron = true,
                    help = "App Hub keeps every Wonderdesk app up to date. It lists new versions and installs them for you.",
                    onClick = { if (!launch(context, "dev.wonderdesk.apphub")) toast("Install Wonderdesk App Hub to get updates") })
            }
            Panel(title = "Help us improve it") {
                ListRow("Send feedback or report a problem", subtitle = "Opens Pulse's feedback form", icon = WonderIcons.Flag, tint = Color(0xFFE67E22), chevron = true,
                    help = "Tell us what is wrong or missing. Your message goes to the Wonderdesk developer with your app versions, never your music.",
                    onClick = { if (!launch(context, "dev.wonderdesk.pulse")) toast("Install Wonderdesk Pulse to send feedback") })
            }
            Panel(title = "Look and feel") {
                ListRow("Appearance for all Wonderdesk apps", subtitle = "Aqua, Bliss or Super, set in Control Room", icon = WonderIcons.Palette, tint = Color(0xFF8E44AD), chevron = true,
                    help = "Bliss Player follows the look you pick in Control Room: its buttons, window and colours change to match.",
                    onClick = { if (!launch(context, "dev.wonderdesk.controlroom")) toast("Install Wonderdesk Control Room to change the look") })
            }
            Panel(title = "Playing in the background", footer = "Android can stop music apps that run in the background to save battery. Allowing Bliss Player to run without limits keeps the music going with the screen off.") {
                val pm = context.getSystemService(PowerManager::class.java)
                val free = remember(tick) { pm.isIgnoringBatteryOptimizations(context.packageName) }
                ListRow(if (free) "Battery limits are off for Bliss Player" else "Allow background playback", subtitle = if (free) "Music keeps playing with the screen off" else "Recommended", icon = WonderIcons.BatteryFull, tint = Color(0xFF2E9E44), chevron = !free,
                    help = "Without this, some phones pause music after a while when the screen is off.",
                    onClick = { if (!free) { runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; tick++ } })
                val why = com.blissplayer.music.playback.FocusPolicy.describe(prefs.getString("last_pause_reason", null))
                if (why != null) { PanelDivider(); ListRow("Last time the music stopped by itself", subtitle = why, icon = WonderIcons.Info, tint = Color(0xFF7A7F87)) }
                prefs.getString("last_playback_error", null)?.let { PanelDivider(); ListRow("Last playback problem", subtitle = it, icon = WonderIcons.Info, tint = Color(0xFFE5483C)) }
            }
            Panel(title = "If it ever closes on its own", footer = "The crash log is only text about what went wrong, never your music.") {
                ListRow("Copy the last crash log", icon = WonderIcons.ContentPaste, tint = Color(0xFF7A7F87), onClick = {
                    val log = CrashReporter.read(context)
                    if (log.isNullOrBlank()) toast("No crash recorded") else { context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("crash", log)); toast("Crash log copied") }
                })
            }
            Panel(title = "More from Wonderdesk") {
                val installed = remember { SUITE.filter { context.packageManager.getLaunchIntentForPackage(it.second) != null } }
                if (installed.isEmpty()) ListRow("No other Wonderdesk apps installed", subtitle = "Get them from Wonderdesk App Hub", icon = WonderIcons.Apps, tint = Color(0xFF7A7F87))
                installed.forEachIndexed { i, (name, pkg, blurb) -> if (i > 0) PanelDivider(); ListRow(name, subtitle = blurb, leading = { context.suiteArt(pkg, 72)?.let { androidx.compose.foundation.Image(it, null, Modifier.size(32.dp)) } }, chevron = true, onClick = { launch(context, pkg) }) }
            }
        }
    }
}
