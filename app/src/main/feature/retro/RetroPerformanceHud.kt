package com.winlator.cmod.feature.retro

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.runtime.system.CPUStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object RetroPerformanceHudState {
    data class Snapshot(
        val fps: Float = 0f,
        val frametimeMs: Float = 0f,
        val gpuLoad: Int = -1,
        val cpuPercent: Int = -1,
        val ramPercent: Int = -1,
        val batteryWatts: Float = -1f,
        val tempC: Int = -1,
        val console: String = "",
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun setVisible(v: Boolean) {
        _visible.value = v
    }

    fun updateValues(snapshot: Snapshot) {
        _state.value = snapshot
    }
}

class RetroStatsCollector(
    private val activity: Activity,
    private val console: String,
) {
    private val frameCount = AtomicInteger(0)
    private val lastFrameNano = AtomicLong(0L)
    private var prevCpuSample: CPUStatus.AppCpuSample? = null
    private var lastGoodGpu = -1
    private var lastGoodGpuTime = 0L
    private var job: Job? = null

    fun onFrame() {
        frameCount.incrementAndGet()
        lastFrameNano.set(System.nanoTime())
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job =
            scope.launch(Dispatchers.Default) {
                var lastTick = System.nanoTime()
                while (isActive) {
                    delay(1000)
                    val now = System.nanoTime()
                    val frames = frameCount.getAndSet(0)
                    val dtSec = (now - lastTick) / 1e9f
                    lastTick = now
                    val stalled = now - lastFrameNano.get() > 1_500_000_000L
                    val fps = if (stalled || dtSec <= 0f) 0f else frames / dtSec
                    val frametime = if (fps > 0f) 1000f / fps else 0f
                    RetroPerformanceHudState.updateValues(
                        RetroPerformanceHudState.Snapshot(
                            fps = fps,
                            frametimeMs = frametime,
                            gpuLoad = readGpuLoad(),
                            cpuPercent = readCpuPercent(),
                            ramPercent = readRamPercent(),
                            batteryWatts = readBatteryWatts(),
                            tempC = readBatteryTempC(),
                            console = console,
                        ),
                    )
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun readCpuPercent(): Int =
        try {
            val sample = CPUStatus.readAppCpuSample()
            val previous = prevCpuSample
            prevCpuSample = sample
            if (sample != null && previous != null) sample.percentSince(previous) else -1
        } catch (_: Exception) {
            -1
        }

    private fun readGpuLoad(): Int {
        val value = readGpuLoadRaw()
        val now = SystemClock.elapsedRealtime()
        if (value >= 0) {
            lastGoodGpu = value
            lastGoodGpuTime = now
            return value
        }
        return if (lastGoodGpu >= 0 && now - lastGoodGpuTime < 5000) lastGoodGpu else -1
    }

    private fun readGpuLoadRaw(): Int {
        val simpleFiles =
            arrayOf(
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                "/sys/class/misc/mali0/device/utilisation",
                "/sys/kernel/gpu/gpu_busy",
            )
        for (path in simpleFiles) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    BufferedReader(FileReader(file)).use { reader ->
                        val line = reader.readLine()
                        if (line != null) {
                            val digits = line.trim().replace(Regex("[^0-9]"), "")
                            if (digits.isNotEmpty()) return digits.toInt()
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        val gpubusy = File("/sys/class/kgsl/kgsl-3d0/gpubusy")
        if (gpubusy.exists() && gpubusy.canRead()) {
            try {
                BufferedReader(FileReader(gpubusy)).use { reader ->
                    val parts = reader.readLine()?.trim()?.split(Regex("\\s+"))
                    if (parts != null && parts.size >= 2) {
                        val busy = parts[0].toLong()
                        val total = parts[1].toLong()
                        if (total != 0L) return ((100 * busy) / total).toInt()
                    }
                }
            } catch (_: Exception) {
            }
        }
        return -1
    }

    private fun readRamPercent(): Int =
        try {
            val mi = ActivityManager.MemoryInfo()
            (activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            (((mi.totalMem - mi.availMem) * 100) / mi.totalMem).toInt()
        } catch (_: Exception) {
            -1
        }

    private fun batteryIntent(): Intent? =
        activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun readBatteryWatts(): Float =
        try {
            val bm = activity.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            var currentRaw = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L
            if (currentRaw == 0L || currentRaw == Long.MIN_VALUE) {
                currentRaw = readSysFsLong("/sys/class/power_supply/battery/current_now")
            }
            if (currentRaw == 0L || currentRaw == Long.MIN_VALUE) {
                -1f
            } else {
                val currentAbs = Math.abs(currentRaw)
                val amps = if (currentAbs < 20000) currentAbs / 1000f else currentAbs / 1000000f
                val mv = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                if (mv > 0 && amps > 0f) (mv / 1000f) * amps else -1f
            }
        } catch (_: Exception) {
            -1f
        }

    private fun readBatteryTempC(): Int =
        try {
            val temp = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            if (temp > 0) temp / 10 else -1
        } catch (_: Exception) {
            -1
        }

    private fun readSysFsLong(path: String): Long =
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                BufferedReader(FileReader(file)).use { it.readLine()?.trim()?.toLong() ?: 0L }
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
}

private val HudAccent = Color(0xFF1A9FFF)
private val HudGood = Color(0xFF35D0BA)
private val HudWarn = Color(0xFFFFB020)
private val HudBad = Color(0xFFFF5A5A)
private val HudText = Color(0xFFF0F4FF)
private val HudSub = Color(0xFF7A8FA8)
private val HudTrack = Color(0x33FFFFFF)

private data class RetroGaugeSpec(
    val label: String,
    val value: String,
    val fraction: Float,
    val color: Color,
    val sublabel: String? = null,
    val sublabelColor: Color = HudSub,
)

@Composable
fun RetroPerformanceHudOverlay(modifier: Modifier = Modifier) {
    val s by RetroPerformanceHudState.state.collectAsState()
    val gauges = ArrayList<RetroGaugeSpec>(6)
    gauges.add(RetroGaugeSpec("FPS", s.fps.toInt().toString(), s.fps / 120f, HudAccent))
    gauges.add(RetroGaugeSpec("GPU", pctText(s.gpuLoad), pctFraction(s.gpuLoad), loadColor(maxOf(s.gpuLoad, 0))))
    gauges.add(
        RetroGaugeSpec(
            "CPU",
            pctText(maxOf(s.cpuPercent, 0)),
            pctFraction(maxOf(s.cpuPercent, 0)),
            loadColor(maxOf(s.cpuPercent, 0)),
        ),
    )
    gauges.add(RetroGaugeSpec("RAM", pctText(s.ramPercent), pctFraction(s.ramPercent), loadColor(maxOf(s.ramPercent, 0))))
    gauges.add(
        RetroGaugeSpec(
            "ms",
            String.format("%.1f", s.frametimeMs),
            1f - (s.frametimeMs / 33.3f),
            HudGood,
        ),
    )
    gauges.add(
        RetroGaugeSpec(
            "WATT",
            if (s.batteryWatts >= 0f) String.format("%.1f", s.batteryWatts) else "N/A",
            if (s.batteryWatts >= 0f) s.batteryWatts / 12f else 0f,
            HudAccent,
            sublabel = if (s.tempC >= 0) "${s.tempC}°C" else null,
            sublabelColor = if (s.tempC >= 0) tempColor(s.tempC) else HudSub,
        ),
    )
    val showConsole = s.console.isNotEmpty()
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xF00A0D13)),
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = if (showConsole) 48.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            gauges.chunked(3).forEach { rowGauges ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
                    rowGauges.forEach { g ->
                        RetroHudGauge(g.label, g.value, g.fraction, g.color, g.sublabel, g.sublabelColor)
                    }
                }
            }
        }
        if (showConsole) {
            Text(
                s.console,
                color = HudAccent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0x1A1A9FFF))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

private fun pctText(v: Int): String = if (v >= 0) "$v%" else "N/A"

private fun pctFraction(v: Int): Float = if (v >= 0) v / 100f else 0f

private fun loadColor(pct: Int): Color = if (pct >= 90) HudBad else if (pct >= 70) HudWarn else HudGood

private fun tempColor(c: Int): Color = if (c >= 45) HudBad else if (c >= 40) HudWarn else HudGood

@Composable
private fun RetroHudGauge(
    label: String,
    valueText: String,
    fraction: Float,
    accent: Color,
    sublabel: String? = null,
    sublabelColor: Color = HudSub,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(86.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = HudTrack,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = accent,
                    startAngle = 135f,
                    sweepAngle = 270f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Text(valueText, color = HudText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, color = HudSub, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (sublabel != null) {
            Text(sublabel, color = sublabelColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
