package dev.wonderdesk.shared

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

fun batteryStatusLabel(status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
    BatteryManager.BATTERY_STATUS_FULL -> "Full"
    else -> "Unavailable"
}

fun batteryPowerSourceLabel(plugged: Int): String {
    if (plugged < 0) return "Unavailable"
    if (plugged == 0) return "Not plugged in"
    val sources = listOf(
        BatteryManager.BATTERY_PLUGGED_AC to "AC",
        BatteryManager.BATTERY_PLUGGED_USB to "USB",
        BatteryManager.BATTERY_PLUGGED_WIRELESS to "Wireless",
        BatteryManager.BATTERY_PLUGGED_DOCK to "Dock"
    ).filter { (flag, _) -> plugged and flag != 0 }.map { it.second }
    return sources.joinToString(" / ").ifEmpty { "Other" }
}

fun formatUptime(millis: Long): String {
    val totalMinutes = (millis.coerceAtLeast(0L) / 60_000L).coerceAtMost(999_999L)
    val days = totalMinutes / 1_440L
    val hours = (totalMinutes % 1_440L) / 60L
    val minutes = totalMinutes % 60L
    return if (days > 0) "${days}d ${hours}h ${minutes}m" else "${hours}h ${minutes}m"
}

/** Total and idle jiffies from the first /proc/stat line. Pure for testing. */
fun parseCpuStat(line: String): Pair<Long, Long>? {
    val parts = line.trim().split(Regex("\\s+"))
    if (parts.firstOrNull() != "cpu" || parts.size < 5) return null
    val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
    if (nums.size < 4) return null
    val idle = nums[3] + nums.getOrElse(4) { 0 }
    return Pair(nums.sum(), idle)
}

fun parseMeminfoKb(text: String): Pair<Long, Long>? {
    var total = -1L
    var available = -1L
    text.lineSequence().forEach { line ->
        when {
            line.startsWith("MemTotal:") -> total = line.replace(Regex("[^0-9]"), "").toLongOrNull() ?: -1L
            line.startsWith("MemAvailable:") -> available = line.replace(Regex("[^0-9]"), "").toLongOrNull() ?: -1L
        }
    }
    return if (total > 0 && available > 0) total to available else null
}

data class CpuMem(val cpuPercent: Float, val memUsedKb: Long, val memTotalKb: Long, val netRxBytes: Long, val netTxBytes: Long, val cpuTotal: Long = 0L, val cpuIdle: Long = 0L)

fun collectCpuMem(prev: CpuMem?): CpuMem {
    var cpu = 0f
    var cpuTotalNow = 0L
    var cpuIdleNow = 0L
    val stat = runCatching { java.io.File("/proc/stat").readText() }.getOrNull()
    if (stat != null) {
        val now = parseCpuStat(stat.lines().first())
        if (now != null) {
            cpuTotalNow = now.first
            cpuIdleNow = now.second
            if (prev != null && prev.cpuTotal > 0) {
                val dTotal = cpuTotalNow - prev.cpuTotal
                val dIdle = cpuIdleNow - prev.cpuIdle
                if (dTotal > 0) cpu = ((dTotal - dIdle) * 100f / dTotal).coerceIn(0f, 100f)
            }
        }
    }
    var memUsed = 0L
    var memTotal = 0L
    runCatching { java.io.File("/proc/meminfo").readText() }.getOrNull()?.let {
        parseMeminfoKb(it)?.let { (t, a) -> memTotal = t; memUsed = t - a }
    }
    var rx = prev?.netRxBytes ?: 0L
    var tx = prev?.netTxBytes ?: 0L
    runCatching { java.io.File("/proc/net/dev").readText() }.getOrNull()?.let { text ->
        var totalRx = 0L
        var totalTx = 0L
        text.lineSequence().drop(2).forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size > 9 && !parts[0].startsWith("lo")) {
                totalRx += parts[1].toLongOrNull() ?: 0L
                totalTx += parts[9].toLongOrNull() ?: 0L
            }
        }
        rx = totalRx
        tx = totalTx
    }
    return CpuMem(cpu, memUsed, memTotal, rx, tx, cpuTotalNow, cpuIdleNow)
}

data class RunningProcess(val pkg: String, val name: String, val pssKb: Long)

fun runningProcesses(context: Context, limit: Int = 20): List<RunningProcess> {
    val am = context.getSystemService(ActivityManager::class.java)
    return am.runningAppProcesses.orEmpty()
        .sortedByDescending { it.pid }
        .asSequence()
        .map { p ->
            val pss = runCatching {
                val info = am.getProcessMemoryInfo(intArrayOf(p.pid))[0]
                info.totalPss.toLong()
            }.getOrDefault(0L)
            RunningProcess(p.processName, labelForPackage(context, p.processName), pss)
        }
        .sortedByDescending { it.pssKb }
        .take(limit)
        .toList()
}

private fun labelForPackage(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    val info = pm.getApplicationInfo(pkg, 0)
    pm.getApplicationLabel(info).toString()
}.getOrDefault(pkg.substringAfterLast('.', pkg))

fun batteryAll(context: Context): Map<String, String> {
    val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return emptyMap()
    fun i(key: String) = b.getIntExtra(key, Int.MIN_VALUE)
    val level = i(BatteryManager.EXTRA_LEVEL)
    val scale = i(BatteryManager.EXTRA_SCALE)
    val temp = i(BatteryManager.EXTRA_TEMPERATURE)
    val volt = i(BatteryManager.EXTRA_VOLTAGE)
    val out = mutableMapOf<String, String>()
    if (level >= 0 && scale > 0) out["level"] = "${level * 100 / scale}%"
    if (temp != Int.MIN_VALUE) out["temperature"] = "${temp / 10.0} °C"
    if (volt != Int.MIN_VALUE) out["voltage"] = "${volt} mV"
    out["status"] = batteryStatusLabel(i(BatteryManager.EXTRA_STATUS))
    out["source"] = batteryPowerSourceLabel(i(BatteryManager.EXTRA_PLUGGED))
    out["health"] = when (i(BatteryManager.EXTRA_HEALTH)) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unavailable"
    }
    out["technology"] = b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unavailable"
    return out
}

fun monitorReport(context: Context): String {
    val memory = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
    val storage = StatFs(Environment.getDataDirectory().absolutePath)
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
    val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
    val cpu = collectCpuMem(null)
    fun bytes(value: Long) = Formatter.formatFileSize(context, value)
    val processes = runningProcesses(context).take(15).joinToString("\n") { "- ${it.name} (${it.pkg}): ${bytes(it.pssKb * 1024L)}" }
    return """
        Device: ${Build.MANUFACTURER} ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
        CPU cores available to app: ${Runtime.getRuntime().availableProcessors()}
        Device uptime: ${formatUptime(android.os.SystemClock.elapsedRealtime())}

        System memory total: ${bytes(memory.totalMem)}
        System memory available: ${bytes(memory.availMem)}
        Android low-memory state: ${memory.lowMemory}
        This app PSS: ${bytes(Debug.getPss() * 1024L)}

        Storage total: ${bytes(storage.totalBytes)}
        Storage available: ${bytes(storage.availableBytes)}

        Battery: ${if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "Unavailable"}
        Battery status: ${batteryStatusLabel(status)}
        Power source: ${batteryPowerSourceLabel(plugged)}
        Battery temperature: ${if (temperature != Int.MIN_VALUE) "${temperature / 10.0} °C (reported by Android)" else "Unavailable"}

        Biggest processes:
        ${processes.ifBlank { "- unavailable" }}
    """.trimIndent()
}

@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier, maxHint: Float? = null) {
    Canvas(modifier.background(color.copy(alpha = 0.10f), RoundedCornerShape(6.dp))) {
        if (values.size < 2) return@Canvas
        val max = maxHint ?: values.max().coerceAtLeast(1f)
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - (v / max * size.height).coerceIn(0f, size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
    }
}

@Composable
fun MonitorScreen() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val op = rememberOperation()
    var report by remember { mutableStateOf("Reading Android statistics…") }
    var active by remember { mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var export by remember { mutableStateOf("") }
    var cpu by remember { mutableStateOf(listOf<Float>()) }
    var memPct by remember { mutableStateOf(listOf<Float>()) }
    var battery by remember { mutableStateOf(mapOf<String, String>()) }
    var processes by remember { mutableStateOf(listOf<RunningProcess>()) }
    var netRx by remember { mutableStateOf(0L) }
    var netTx by remember { mutableStateOf(0L) }
    var memUsed by remember { mutableStateOf(0L) }
    var memTotal by remember { mutableStateOf(0L) }
    var cpuNow by remember { mutableStateOf(0f) }
    var prevCpuMem by remember { mutableStateOf<CpuMem?>(null) }
    var stopTarget by remember { mutableStateOf<RunningProcess?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val snapshot = export
            op.run("Exporting the displayed diagnostic report", { context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(snapshot.toByteArray()) } }) { op.notice("Report saved locally. Nothing was uploaded.") }
        }
    }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, _ -> active = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active) {
        while (active) {
            withContext(Dispatchers.IO) {
                val now = collectCpuMem(prevCpuMem)
                prevCpuMem = now
                cpuNow = now.cpuPercent
                cpu = (cpu + now.cpuPercent).takeLast(60)
                memUsed = now.memUsedKb
                memTotal = now.memTotalKb
                memPct = (memPct + if (now.memTotalKb > 0) now.memUsedKb * 100f / now.memTotalKb else 0f).takeLast(60)
                battery = batteryAll(context)
                processes = runningProcesses(context)
                val rx = (now.netRxBytes - (prevCpuMem?.netRxBytes ?: now.netRxBytes)).coerceAtLeast(0)
                val tx = (now.netTxBytes - (prevCpuMem?.netTxBytes ?: now.netTxBytes)).coerceAtLeast(0)
                netRx = rx
                netTx = tx
                report = monitorReport(context)
            }
            delay(2000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Live readings refresh every 2 seconds. Unavailable does not mean zero.", style = MaterialTheme.typography.bodySmall)
        // Battery card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Battery ${battery["level"] ?: "…"}", style = MaterialTheme.typography.titleMedium)
                Text("${battery["status"] ?: ""} · ${battery["source"] ?: ""}")
                Text("Health ${battery["health"] ?: "…"} · ${battery["temperature"] ?: "…"} · ${battery["voltage"] ?: "…"} · ${battery["technology"] ?: "…"}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { (battery["level"]?.removeSuffix("%")?.toFloatOrNull() ?: 0f) / 100f }, Modifier.fillMaxWidth())
            }
        }
        // CPU + memory graphs
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(10.dp)) {
                    Text("CPU ${"%.0f".format(cpuNow)}%", style = MaterialTheme.typography.titleSmall)
                    Sparkline(cpu, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(64.dp), maxHint = 100f)
                }
            }
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(10.dp)) {
                    Text("RAM ${"%.0f".format(if (memTotal > 0) memUsed * 100f / memTotal else 0f)}%", style = MaterialTheme.typography.titleSmall)
                    Text("${Formatter.formatShortFileSize(context, memUsed * 1024L)} / ${Formatter.formatShortFileSize(context, memTotal * 1024L)}", style = MaterialTheme.typography.bodySmall)
                    Sparkline(memPct, MaterialTheme.colorScheme.tertiary, Modifier.fillMaxWidth().height(64.dp), maxHint = 100f)
                }
            }
        }
        Text("Network since last tick: ↓ ${Formatter.formatShortFileSize(context, netRx)}/2s · ↑ ${Formatter.formatShortFileSize(context, netTx)}/2s", style = MaterialTheme.typography.bodySmall)
        // Running processes
        Text("Running processes", style = MaterialTheme.typography.titleMedium)
        processes.take(12).forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name)
                        Text("${p.pkg} · ${Formatter.formatShortFileSize(context, p.pssKb * 1024L)}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { stopTarget = p }) { Text("Stop") }
                }
            }
        }
        AquaSecondaryButton(onClick = { export = report; save.launch("Wonderdesk-device-report.txt") }) { Text("Export displayed report") }
        OperationStatus(op)
        Text(report, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
    }
    stopTarget?.let { p -> AlertDialog(onDismissRequest = { stopTarget = null }, title = { Text("Force stop ${p.name}?") }, text = {
        Text("Android will kill this app and its background work. Unsaved data in it may be lost.")
    }, confirmButton = {
        TextButton(onClick = {
            val am = context.getSystemService(ActivityManager::class.java)
            val target = stopTarget
            stopTarget = null
            op.run("Stopping ${target?.name}", { runCatching { am.killBackgroundProcesses(target?.pkg ?: "") } }) { op.notice("Stop requested.") }
        }) { Text("Force stop") }
    }, dismissButton = { TextButton(onClick = { stopTarget = null }) { Text("Cancel") } })
}
}
