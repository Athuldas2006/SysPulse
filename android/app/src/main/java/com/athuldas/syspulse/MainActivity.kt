package com.athuldas.syspulse

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

// ============================================================
// THEME
// ============================================================

private val Bg = Color(0xFF0C0F0D)
private val CardBg = Color(0xFF171C19)
private val Green = Color(0xFF8CF5B5)
private val Muted = Color(0xFF9BA8A0)
private val Blue = Color(0xFF8AB4FF)
private val Warning = Color(0xFFFFC66D)
private val Danger = Color(0xFFFF8585)

// ============================================================
// DATA MODELS
// ============================================================

data class BatteryDiagnostics(
    val health: String,
    val temperatureC: Double?,
    val technology: String,
    val voltageMv: Int?,
    val chargeCounterMah: Int?
)

data class HealthAssessment(
    val score: Int,
    val status: String,
    val findings: List<String>,
    val recommendations: List<String>
)

data class DeviceSnapshot(
    val battery: Int,
    val charging: String,
    val batteryDiagnostics: BatteryDiagnostics,
    val usedRam: Double,
    val totalRam: Double,
    val freeRam: Double,
    val usedStorage: Double,
    val totalStorage: Double,
    val freeStorage: Double,
    val lowMemory: Boolean
)

// ============================================================
// ACTIVITY
// ============================================================

class MainActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.rgb(12, 15, 13)
        window.navigationBarColor = android.graphics.Color.rgb(12, 15, 13)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Bg,
                    surface = CardBg,
                    primary = Green,
                    onPrimary = Bg
                )
            ) {
                SysPulseApp()
            }
        }
    }
}

// ============================================================
// BATTERY HELPER
// ============================================================

@Suppress("DEPRECATION")
private fun readBatteryDiagnostics(
    context: Context,
    batteryIntent: Intent? = try {
        context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
    } catch (_: Exception) {
        null
    }
): BatteryDiagnostics {
    val rawHealth = batteryIntent?.getIntExtra(
        BatteryManager.EXTRA_HEALTH, -1
    ) ?: -1

    val health = when (rawHealth) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE ->
            "Unspecified failure"
        else -> "Not reported"
    }

    val rawTemperature = batteryIntent?.getIntExtra(
        BatteryManager.EXTRA_TEMPERATURE, -1
    ) ?: -1

    val temperature = if (rawTemperature >= 0) {
        rawTemperature / 10.0
    } else {
        null
    }

    val technology = batteryIntent
        ?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
        ?.takeIf { it.isNotBlank() } ?: "Not reported"

    val voltage = batteryIntent?.getIntExtra(
        BatteryManager.EXTRA_VOLTAGE, -1
    )?.takeIf { it > 0 }

    val manager = try {
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    } catch (_: Exception) {
        null
    }

    val charge = try {
        manager?.getIntProperty(
            BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
        )?.takeIf { it > 0 }?.div(1000)
    } catch (_: Exception) {
        null
    }

    return BatteryDiagnostics(
        health = health,
        temperatureC = temperature,
        technology = technology,
        voltageMv = voltage,
        chargeCounterMah = charge
    )
}

// ============================================================
// APP NAVIGATION
// ============================================================

@Composable
fun SysPulseApp() {
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var reportText by remember { mutableStateOf("") }

    var showScreenTest by remember { mutableStateOf(false) }

    val createPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                if (reportText.isBlank()) {
                    error("Generate a report first.")
                }

                writeReportPdf(context, uri, reportText)

                Toast.makeText(
                    context,
                    "Health report saved",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "PDF error: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(3.seconds)
            refreshTick++
        }
    }

    val batteryManager = try {
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    } catch (_: Exception) {
        null
    }

    val battery = try {
        batteryManager?.getIntProperty(
            BatteryManager.BATTERY_PROPERTY_CAPACITY
        )?.coerceIn(0, 100) ?: 0
    } catch (_: Exception) {
        0
    }

    val batteryIntent = try {
        context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
    } catch (_: Exception) {
        null
    }

    val charging = when (
        batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    ) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_FULL -> "Fully charged"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }

    val batteryDiagnostics = remember(refreshTick) {
        readBatteryDiagnostics(context, batteryIntent)
    }

    val (usedRam, totalRam, freeRam) = remember(refreshTick) {
        try {
            val activityManager = context.getSystemService(
                Context.ACTIVITY_SERVICE
            ) as? ActivityManager

            val memory = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memory)

            val total = memory.totalMem / (1024.0 * 1024 * 1024)
            val free = memory.availMem / (1024.0 * 1024 * 1024)
            val used = (total - free).coerceAtLeast(0.0)
            Triple(used, total, free)
        } catch (_: Exception) {
            Triple(0.0, 0.0, 0.0)
        }
    }

    val (usedStorage, totalStorage, freeStorage) = remember(refreshTick) {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes / (1024.0 * 1024 * 1024.0)
            val free = stat.availableBytes / (1024.0 * 1024 * 1024.0)
            val used = (total - free).coerceAtLeast(0.0)
            Triple(used, total, free)
        } catch (_: Exception) {
            Triple(0.0, 0.0, 0.0)
        }
    }

    val isLowMemory = try {
        val activityManager = context.getSystemService(
            Context.ACTIVITY_SERVICE
        ) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        memory.lowMemory
    } catch (_: Exception) {
        false
    }

    val snapshot = DeviceSnapshot(
        battery = battery,
        charging = charging,
        batteryDiagnostics = batteryDiagnostics,
        usedRam = usedRam,
        totalRam = totalRam,
        freeRam = freeRam,
        usedStorage = usedStorage,
        totalStorage = totalStorage,
        freeStorage = freeStorage,
        lowMemory = isLowMemory
    )

    val assessment = assessHealth(snapshot)

    fun generateReport() {
        reportText = buildHealthReport(snapshot, assessment)
        selectedTab = 3
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(
                containerColor = CardBg,
                contentColor = Green
            ) {
                val tabs = listOf(
                    "Home", "System", "Tests", "Report", "About"
                )
                val symbols = listOf("⌂", "▦", "✓", "♥", "ⓘ")

                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            refreshTick++
                        },
                        icon = {
                            Text(
                                symbols[index],
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        label = { Text(label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Green,
                            selectedTextColor = Green,
                            indicatorColor = Color(0xFF263B2D),
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    snapshot = snapshot,
                    onRefresh = { refreshTick++ },
                    onGenerateReport = { generateReport() }
                )

                1 -> SystemScreen(
                    context = context,
                    refreshTick = refreshTick
                )

                2 -> DiagnosticsScreen(
                    snapshot = snapshot,
                    showScreenTest = { showScreenTest = true }
                )

                3 -> HealthReportScreen(
                    snapshot = snapshot,
                    assessment = assessment,
                    onGenerate = {
                        reportText = buildHealthReport(snapshot, assessment)
                    },
                    onExportPdf = {
                        reportText = buildHealthReport(snapshot, assessment)
                        createPdfLauncher.launch("SysPulse_Health_Report.pdf")
                    }
                )

                4 -> AboutScreen()
            }
        }
    }

    if (showScreenTest) {
        ScreenColorTest(
            onExit = { showScreenTest = false },
            onResult = { passed ->
                showScreenTest = false
                Toast.makeText(
                    context,
                    if (passed) "Screen test marked as passed"
                    else "Screen test marked for attention",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }
}

// ============================================================
// DASHBOARD
// ============================================================

@Composable
private fun DashboardScreen(
    snapshot: DeviceSnapshot,
    onRefresh: () -> Unit,
    onGenerateReport: () -> Unit
) {
    Text(
        "SysPulse",
        color = Color.White,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold
    )
    Text("YOUR DEVICE, AT A GLANCE", color = Muted, fontSize = 11.sp)

    Panel("BATTERY LEVEL") {
        Text(
            "${snapshot.battery}%",
            color = Green,
            fontSize = 45.sp,
            fontWeight = FontWeight.Bold
        )
        Meter(snapshot.battery / 100f, Green)
        InfoRow("Charging", snapshot.charging)
        InfoRow("Reported health", snapshot.batteryDiagnostics.health)
        InfoRow(
            "Temperature",
            snapshot.batteryDiagnostics.temperatureC?.let {
                "${it.fmt()} °C"
            } ?: "Not reported"
        )
        InfoRow(
            "Voltage",
            snapshot.batteryDiagnostics.voltageMv?.let { "$it mV" }
                ?: "Not reported"
        )
        InfoRow("Technology", snapshot.batteryDiagnostics.technology)
        InfoRow(
            "Remaining charge counter",
            snapshot.batteryDiagnostics.chargeCounterMah?.let { "$it mAh" }
                ?: "Not available"
        )
    }

    Panel("SYSTEM OVERVIEW") {
        InfoRow("Used RAM", "${snapshot.usedRam.fmt()} GB")
        InfoRow("Total RAM", "${snapshot.totalRam.fmt()} GB")
        Meter(
            if (snapshot.totalRam > 0) {
                (snapshot.usedRam / snapshot.totalRam).toFloat()
            } else 0f,
            Green
        )
        InfoRow("Available RAM", "${snapshot.freeRam.fmt()} GB")

        HorizontalDivider(color = Color(0xFF303832))

        InfoRow("Used storage", "${snapshot.usedStorage.fmt()} GB")
        InfoRow("Total storage", "${snapshot.totalStorage.fmt()} GB")
        Meter(
            if (snapshot.totalStorage > 0) {
                (snapshot.usedStorage / snapshot.totalStorage).toFloat()
            } else 0f,
            Blue
        )
        InfoRow("Free storage", "${snapshot.freeStorage.fmt()} GB")
    }

    Panel("DEVICE INFORMATION") {
        InfoRow("Model", Build.MODEL)
        InfoRow("Manufacturer", Build.MANUFACTURER)
        InfoRow("Android", Build.VERSION.RELEASE)
        InfoRow("SDK", Build.VERSION.SDK_INT.toString())
    }

    Button(
        onClick = onRefresh,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Green,
            contentColor = Bg
        )
    ) {
        Text("Refresh now")
    }

    OutlinedButton(
        onClick = onGenerateReport,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Generate Health Report")
    }
}

// ============================================================
// DEVICE DIAGNOSTICS
// ============================================================

@Composable
private fun DiagnosticsScreen(
    snapshot: DeviceSnapshot,
    showScreenTest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sensorManager = remember {
        try {
            context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        } catch (_: Exception) {
            null
        }
    }

    val sensors = remember {
        try {
            sensorManager?.getSensorList(Sensor.TYPE_ALL) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    val accelerometer = remember {
        try {
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } catch (_: Exception) {
            null
        }
    }

    var sensorValues by remember { mutableStateOf("Waiting for readings…") }
    var vibrationResult by remember { mutableStateOf("Not tested") }
    var speakerResult by remember { mutableStateOf("Not tested") }
    var microphoneResult by remember { mutableStateOf("Not tested") }
    var micRunning by remember { mutableStateOf(false) }
    var speakerRunning by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch {
                micRunning = true
                microphoneResult = "Recording a short sample…"

                microphoneResult = try {
                    val peak = withContext(Dispatchers.IO) {
                        measureMicrophone()
                    }
                    if (peak >= 0) {
                        "Sample recorded. Peak level: $peak"
                    } else {
                        "Could not read microphone input"
                    }
                } catch (e: Exception) {
                    "Test failed: ${e.localizedMessage ?: "Audio error"}"
                }

                micRunning = false
            }
        } else {
            microphoneResult = "Permission denied"
        }
    }

    DisposableEffect(accelerometer, sensorManager) {
        if (accelerometer != null && sensorManager != null) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    try {
                        sensorValues = "X: ${event.values[0].fmt()}   " +
                                "Y: ${event.values[1].fmt()}   " +
                                "Z: ${event.values[2].fmt()}"
                    } catch (_: Exception) {
                    }
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int
                ) = Unit
            }

            try {
                sensorManager.registerListener(
                    listener,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_UI
                )
            } catch (_: Exception) {
                sensorValues = "Accelerometer unavailable"
            }

            onDispose {
                try {
                    sensorManager.unregisterListener(listener)
                } catch (_: Exception) {
                }
            }
        } else {
            sensorValues = "No accelerometer detected"
            onDispose { }
        }
    }

    Text(
        "Device Diagnostics",
        color = Color.White,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold
    )
    Text(
        "Run individual checks on your phone's hardware.",
        color = Muted,
        fontSize = 13.sp
    )

    Panel("BATTERY CHECK") {
        InfoRow("Charge", "${snapshot.battery}%")
        InfoRow("Charging", snapshot.charging)
        InfoRow("Reported health", snapshot.batteryDiagnostics.health)
        InfoRow(
            "Temperature",
            snapshot.batteryDiagnostics.temperatureC?.let {
                "${it.fmt()} °C"
            } ?: "Not reported"
        )

        Text(
            "These are Android-reported readings, not a full battery-capacity test.",
            color = Muted,
            fontSize = 11.sp
        )
    }

    Panel("STORAGE CHECK") {
        InfoRow("Total", "${snapshot.totalStorage.fmt()} GB")
        InfoRow("Used", "${snapshot.usedStorage.fmt()} GB")
        InfoRow("Available", "${snapshot.freeStorage.fmt()} GB")

        val fraction = if (snapshot.totalStorage > 0) {
            snapshot.usedStorage / snapshot.totalStorage
        } else 0.0

        Meter(fraction.toFloat(), if (fraction >= 0.9) Warning else Green)

        Text(
            if (fraction >= 0.9) {
                "Storage is nearly full. Consider freeing up space."
            } else {
                "Storage is below the app's high-usage threshold."
            },
            color = if (fraction >= 0.9) Warning else Green,
            fontSize = 12.sp
        )
    }

    Panel("SENSOR CHECK") {
        InfoRow("Available sensors", sensors.size.toString())
        InfoRow(
            "Accelerometer",
            if (accelerometer != null) "Available" else "Not available"
        )

        Text("Live accelerometer readings", color = Green, fontWeight = FontWeight.SemiBold)
        Text(sensorValues, color = Color.White, fontSize = 13.sp)
    }

    Panel("VIBRATION TEST") {
        InfoRow("Result", vibrationResult)

        Button(
            onClick = {
                try {
                    val vibrator: Vibrator? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val manager = context.getSystemService(
                                Context.VIBRATOR_MANAGER_SERVICE
                            ) as? VibratorManager
                            manager?.defaultVibrator
                        } else {
                            @Suppress("DEPRECATION")
                            context.getSystemService(
                                Context.VIBRATOR_SERVICE
                            ) as? Vibrator
                        }

                    if (vibrator?.hasVibrator() == true) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(
                                VibrationEffect.createOneShot(
                                    300,
                                    VibrationEffect.DEFAULT_AMPLITUDE
                                )
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(300)
                        }
                        vibrationResult = "Test triggered — confirm below"
                    } else {
                        vibrationResult = "No vibrator reported"
                    }
                } catch (_: Exception) {
                    vibrationResult = "Unable to start vibration"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Green,
                contentColor = Bg
            )
        ) {
            Text("Vibrate once")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vibrationResult = "Passed (user confirmed)" },
                modifier = Modifier.weight(1f)
            ) { Text("I felt it") }

            OutlinedButton(
                onClick = { vibrationResult = "Needs attention (user reported)" },
                modifier = Modifier.weight(1f)
            ) { Text("Didn't feel it") }
        }
    }

    Panel("SPEAKER TEST") {
        InfoRow("Result", speakerResult)

        Button(
            enabled = !speakerRunning,
            onClick = {
                scope.launch {
                    speakerRunning = true
                    speakerResult = "Playing short test tone…"

                    try {
                        withContext(Dispatchers.IO) {
                            playTestTone()
                        }
                        speakerResult = "Tone played — did you hear it?"
                    } catch (_: Exception) {
                        speakerResult = "Could not play test tone"
                    }

                    speakerRunning = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Green,
                contentColor = Bg
            )
        ) {
            Text(if (speakerRunning) "Playing…" else "Play test tone")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { speakerResult = "Passed (user heard tone)" },
                modifier = Modifier.weight(1f)
            ) { Text("I heard it") }

            OutlinedButton(
                onClick = { speakerResult = "Needs attention (user reported)" },
                modifier = Modifier.weight(1f)
            ) { Text("No sound") }
        }

        Text(
            "Keep your media volume at a comfortable level.",
            color = Muted,
            fontSize = 11.sp
        )
    }

    Panel("MICROPHONE TEST") {
        InfoRow("Result", microphoneResult)

        Button(
            enabled = !micRunning,
            onClick = {
                if (context.checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    scope.launch {
                        micRunning = true
                        microphoneResult = "Recording a short sample…"

                        microphoneResult = try {
                            val peak = withContext(Dispatchers.IO) {
                                measureMicrophone()
                            }
                            if (peak >= 0) {
                                "Sample captured. Peak level: $peak"
                            } else {
                                "No usable microphone data received"
                            }
                        } catch (e: Exception) {
                            "Test failed: ${e.localizedMessage ?: "Audio error"}"
                        }

                        micRunning = false
                    }
                } else {
                    micPermissionLauncher.launch(
                        Manifest.permission.RECORD_AUDIO
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Green,
                contentColor = Bg
            )
        ) {
            Text(if (micRunning) "Recording…" else "Record 1-second sample")
        }

        Text(
            "The sample is analyzed locally and is not saved as an audio file. " +
                    "Speak during the test. Android may show a microphone privacy indicator.",
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }

    Panel("SCREEN TEST") {
        Text(
            "Display solid colors to help spot dead pixels, discoloration, " +
                    "uneven areas, or visible lines.",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Button(
            onClick = showScreenTest,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Green,
                contentColor = Bg
            )
        ) {
            Text("Start screen color test")
        }
    }

    Text(
        "Results are user-assisted checks, not manufacturer-certified hardware diagnostics.",
        color = Muted,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
}

// ============================================================
// SPEAKER TONE HELPER
// ============================================================

private fun playTestTone() {
    try {
        val sampleRate = 44100
        val durationSeconds = 1.0
        val numSamples = (durationSeconds * sampleRate).toInt()
        val samples = ShortArray(numSamples)
        val frequency = 800.0

        for (i in 0 until numSamples) {
            val angle = 2.0 * kotlin.math.PI * i * frequency / sampleRate
            samples[i] = (kotlin.math.sin(angle) * Short.MAX_VALUE * 0.85).toInt().toShort()
        }

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, samples.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            audioTrack.write(samples, 0, samples.size)
            audioTrack.play()
            Thread.sleep(1000)
            audioTrack.stop()
        } finally {
            audioTrack.release()
        }
    } catch (_: Exception) {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
        try {
            tone.startTone(ToneGenerator.TONE_DTMF_0, 1000)
            Thread.sleep(1100)
        } finally {
            tone.release()
        }
    }
}

// ============================================================
// MICROPHONE MEASUREMENT
// ============================================================

@android.annotation.SuppressLint("MissingPermission")
private fun measureMicrophone(): Int {
    val sampleRate = 16000

    val minBuffer = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    if (minBuffer <= 0) {
        throw IllegalStateException("Microphone configuration unavailable")
    }

    val recorder = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuffer, sampleRate * 2)
    )

    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release()
        throw IllegalStateException("Microphone could not initialize")
    }

    val buffer = ShortArray(maxOf(1024, minBuffer / 2))
    var peak = 0

    try {
        recorder.startRecording()

        val start = SystemClock.elapsedRealtime()

        while (SystemClock.elapsedRealtime() - start < 1000) {
            val count = recorder.read(buffer, 0, buffer.size)

            if (count > 0) {
                for (i in 0 until count) {
                    peak = maxOf(peak, abs(buffer[i].toInt()))
                }
            } else if (count < 0) {
                throw IllegalStateException("Microphone read failed: $count")
            }
        }
    } finally {
        try {
            recorder.stop()
        } catch (_: Exception) {
        }
        recorder.release()
    }

    return peak
}

// ============================================================
// FULL-SCREEN COLOR TEST
// ============================================================

@Composable
private fun ScreenColorTest(
    onExit: () -> Unit,
    onResult: (Boolean) -> Unit
) {
    val colors = listOf(
        Color.White,
        Color.Black,
        Color.Red,
        Color.Green,
        Color.Blue,
        Color(0xFF808080)
    )

    val names = listOf(
        "White", "Black", "Red", "Green", "Blue", "Gray"
    )

    var index by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors[index])
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Screen Test: ${names[index]} (${index + 1}/${colors.size})",
                color = if (index == 0 || index == 3) Color.Black else Color.White,
                fontWeight = FontWeight.Bold
            )

            Text(
                "Inspect the entire screen for spots, lines, or uneven color.",
                color = if (index == 0 || index == 3) Color.Black else Color.White,
                fontSize = 13.sp
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { index = (index + 1) % colors.size },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Next color")
                }

                Button(
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Exit test")
                }

                Button(
                    onClick = { onResult(true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Screen looks good")
                }

                Button(
                    onClick = { onResult(false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("I noticed a problem")
                }
            }
        }
    }
}

// ============================================================
// SYSTEM SCREEN
// ============================================================

@Composable
private fun SystemScreen(
    context: Context,
    refreshTick: Int
) {
    var appCpu by remember { mutableStateOf<Double?>(null) }
    var sampled by remember { mutableStateOf(false) }

    val cores = Runtime.getRuntime().availableProcessors()

    val diagnostics = remember(refreshTick) {
        readBatteryDiagnostics(context)
    }

    LaunchedEffect(Unit) {
        var previousCpu = try { Process.getElapsedCpuTime() } catch (_: Exception) { 0L }
        var previousWall = SystemClock.elapsedRealtime()

        while (true) {
            delay(3.seconds)

            try {
                val currentCpu = Process.getElapsedCpuTime()
                val currentWall = SystemClock.elapsedRealtime()

                val wallDelta = currentWall - previousWall
                val cpuDelta = currentCpu - previousCpu

                if (wallDelta > 0 && cpuDelta >= 0) {
                    appCpu = (
                            cpuDelta.toDouble() / wallDelta * 100.0
                            ).coerceIn(0.0, cores * 100.0)

                    sampled = true
                }

                previousCpu = currentCpu
                previousWall = currentWall
            } catch (_: Exception) {
            }
        }
    }

    val manager = try {
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    } catch (_: Exception) {
        null
    }

    val memory = ActivityManager.MemoryInfo()
    try {
        manager?.getMemoryInfo(memory)
    } catch (_: Exception) {
    }

    Text(
        "System",
        color = Color.White,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold
    )
    Text("Hardware and app activity", color = Muted)

    Panel("APP CPU ACTIVITY") {
        Text(
            if (sampled) "${(appCpu ?: 0.0).fmt()}%" else "Sampling…",
            color = Blue,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Meter(
            ((appCpu ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
            Blue
        )
        Text(
            "Measures SysPulse's CPU time, not total system-wide CPU usage. " +
                    "100% represents one fully occupied CPU core.",
            color = Muted,
            fontSize = 11.sp
        )
    }

    Panel("PROCESSOR INFORMATION") {
        InfoRow("Available cores", cores.toString())
        InfoRow("Hardware", Build.HARDWARE)
        InfoRow(
            "Primary ABI",
            Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"
        )
        InfoRow("Supported ABIs", Build.SUPPORTED_ABIS.joinToString())
        InfoRow("SoC model", getSocModel())
        InfoRow(
            "CPU 0 current frequency",
            readCpuFrequency(
                listOf(
                    "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
                    "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_cur_freq"
                )
            ) ?: "Not accessible"
        )
        InfoRow(
            "CPU 0 maximum frequency",
            readCpuFrequency(
                listOf(
                    "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq",
                    "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"
                )
            ) ?: "Not accessible"
        )
    }

    Panel("BATTERY DIAGNOSTICS") {
        InfoRow("Reported health", diagnostics.health)
        InfoRow(
            "Temperature",
            diagnostics.temperatureC?.let { "${it.fmt()} °C" }
                ?: "Not reported"
        )
        InfoRow(
            "Voltage",
            diagnostics.voltageMv?.let { "$it mV" } ?: "Not reported"
        )
        InfoRow("Technology", diagnostics.technology)
    }

    Panel("DEVICE INFORMATION") {
        InfoRow("Model", Build.MODEL)
        InfoRow("Manufacturer", Build.MANUFACTURER)
        InfoRow("Brand", Build.BRAND)
        InfoRow("Device code", Build.DEVICE)
        InfoRow("Android", Build.VERSION.RELEASE)
        InfoRow("SDK", Build.VERSION.SDK_INT.toString())
        InfoRow("Build ID", Build.DISPLAY)
        InfoRow("Security patch", Build.VERSION.SECURITY_PATCH)
    }

    Panel("MEMORY") {
        InfoRow(
            "Total RAM",
            "${(memory.totalMem / (1024.0 * 1024 * 1024)).fmt()} GB"
        )
        InfoRow(
            "Available RAM",
            "${(memory.availMem / (1024.0 * 1024 * 1024)).fmt()} GB"
        )
        InfoRow("Low-memory state", if (memory.lowMemory) "Yes" else "No")
    }
}

private fun getSocModel(): String {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf {
                it.isNotBlank() && it != Build.UNKNOWN
            } ?: "Not reported"
        } else {
            "Not exposed by this Android version"
        }
    } catch (_: Exception) {
        "Not reported"
    }
}

private fun readCpuFrequency(paths: List<String>): String? {
    for (path in paths) {
        val frequency = try {
            File(path).readText().trim().toLongOrNull()
        } catch (_: Exception) {
            null
        }

        if (frequency != null && frequency > 0) {
            return String.format(
                Locale.US,
                "%.2f GHz",
                frequency / 1_000_000.0
            )
        }
    }

    return null
}

// ============================================================
// HEALTH ASSESSMENT
// ============================================================

private fun assessHealth(snapshot: DeviceSnapshot): HealthAssessment {
    var score = 100
    val findings = mutableListOf<String>()
    val recommendations = mutableListOf<String>()

    val ramRatio = if (snapshot.totalRam > 0) {
        snapshot.usedRam / snapshot.totalRam
    } else 0.0

    val storageRatio = if (snapshot.totalStorage > 0) {
        snapshot.usedStorage / snapshot.totalStorage
    } else 0.0

    when {
        snapshot.battery < 10 -> {
            score -= 20
            findings.add("Battery charge is critically low.")
            recommendations.add("Charge your phone soon.")
        }

        snapshot.battery < 20 -> {
            score -= 10
            findings.add("Battery charge is low.")
            recommendations.add("Consider charging your phone.")
        }

        else -> findings.add("Battery charge is ${snapshot.battery}%.")
    }

    val temperature = snapshot.batteryDiagnostics.temperatureC

    if (temperature != null && temperature >= 45) {
        score -= 15
        findings.add("Battery temperature is high (${temperature.fmt()} °C).")
        recommendations.add("Let the phone cool down.")
    } else if (temperature != null && temperature >= 40) {
        findings.add("Battery temperature is elevated.")
        recommendations.add("Monitor temperature during heavy use.")
    }

    when (snapshot.batteryDiagnostics.health) {
        "Overheating" -> {
            score -= 20
            findings.add("Android reports battery overheating.")
            recommendations.add("Stop demanding tasks and let the phone cool.")
        }

        "Dead", "Over voltage", "Unspecified failure" -> {
            score -= 25
            findings.add(
                "Android reports a battery warning: " +
                        snapshot.batteryDiagnostics.health
            )
            recommendations.add("Have the battery checked by a service provider.")
        }

        "Good" -> findings.add("Android reports battery health as Good.")
        else -> findings.add("Battery health is not fully reported.")
    }

    when {
        storageRatio >= 0.95 -> {
            score -= 25
            findings.add("Internal storage is almost full.")
            recommendations.add("Free up storage space.")
        }

        storageRatio >= 0.90 -> {
            score -= 15
            findings.add("Internal storage usage is high.")
            recommendations.add("Review large files and unused apps.")
        }

        storageRatio >= 0.80 -> {
            score -= 8
            findings.add("Internal storage is getting full.")
            recommendations.add("Monitor remaining storage.")
        }

        else -> findings.add("Storage usage is within the normal range.")
    }

    when {
        snapshot.lowMemory -> {
            score -= 15
            findings.add("Android reports memory pressure.")
            recommendations.add("Close demanding apps if performance slows.")
        }

        ramRatio >= 0.90 -> {
            score -= 12
            findings.add("System RAM usage is very high.")
            recommendations.add("Check memory usage again after closing unused apps.")
        }

        ramRatio >= 0.80 -> {
            score -= 6
            findings.add("System RAM usage is elevated.")
            recommendations.add("Monitor performance.")
        }

        else -> findings.add("RAM availability is within the normal range.")
    }

    score = score.coerceIn(0, 100)

    val status = when {
        score >= 85 -> "Good"
        score >= 65 -> "Fair"
        score >= 40 -> "Needs attention"
        else -> "Critical"
    }

    if (recommendations.isEmpty()) {
        recommendations.add("No immediate action is suggested by these readings.")
    }

    return HealthAssessment(score, status, findings, recommendations)
}

// ============================================================
// REPORT TEXT
// ============================================================

private fun buildHealthReport(
    snapshot: DeviceSnapshot,
    assessment: HealthAssessment
): String {
    val date = SimpleDateFormat(
        "dd MMM yyyy, hh:mm a",
        Locale.getDefault()
    ).format(Date())

    val d = snapshot.batteryDiagnostics

    return buildString {
        appendLine("SYSPULSE - DEVICE HEALTH REPORT")
        appendLine("Generated: $date")
        appendLine("========================================")
        appendLine()
        appendLine("OVERALL ASSESSMENT")
        appendLine("Score: ${assessment.score}/100")
        appendLine("Status: ${assessment.status}")
        appendLine("This is a rule-based estimate, not a complete hardware diagnosis.")
        appendLine()
        appendLine("BATTERY")
        appendLine("Charge: ${snapshot.battery}%")
        appendLine("Charging: ${snapshot.charging}")
        appendLine("Health: ${d.health}")
        appendLine("Temperature: ${d.temperatureC?.let { "${it.fmt()} °C" } ?: "Not reported"}")
        appendLine("Technology: ${d.technology}")
        appendLine("Voltage: ${d.voltageMv?.let { "$it mV" } ?: "Not reported"}")
        appendLine("Remaining charge counter: ${d.chargeCounterMah?.let { "$it mAh" } ?: "Not available"}")
        appendLine("Battery wear percentage: Not available")
        appendLine()
        appendLine("MEMORY")
        appendLine("Total RAM: ${snapshot.totalRam.fmt()} GB")
        appendLine("Used RAM: ${snapshot.usedRam.fmt()} GB")
        appendLine("Available RAM: ${snapshot.freeRam.fmt()} GB")
        appendLine()
        appendLine("INTERNAL STORAGE")
        appendLine("Total: ${snapshot.totalStorage.fmt()} GB")
        appendLine("Used: ${snapshot.usedStorage.fmt()} GB")
        appendLine("Available: ${snapshot.freeStorage.fmt()} GB")
        appendLine()
        appendLine("DEVICE")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Android: ${Build.VERSION.RELEASE}")
        appendLine("SDK: ${Build.VERSION.SDK_INT}")
        appendLine("Hardware: ${Build.HARDWARE}")
        appendLine("SoC model: ${getSocModel()}")
        appendLine("CPU cores: ${Runtime.getRuntime().availableProcessors()}")
        appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Security patch: ${Build.VERSION.SECURITY_PATCH}")
        appendLine()
        appendLine("FINDINGS")
        assessment.findings.forEachIndexed { i, item ->
            appendLine("${i + 1}. $item")
        }
        appendLine()
        appendLine("RECOMMENDATIONS")
        assessment.recommendations.forEachIndexed { i, item ->
            appendLine("${i + 1}. $item")
        }
        appendLine()
        appendLine("LIMITATIONS")
        appendLine("- Android may restrict CPU frequency and hardware readings.")
        appendLine("- Battery health does not represent battery wear percentage.")
        appendLine("- RAM usage alone does not prove a hardware fault.")
        appendLine("- Some diagnostic tests require manual confirmation.")
        appendLine()
        appendLine("Generated locally by SysPulse Mobile.")
    }
}

// ============================================================
// REPORT SCREEN
// ============================================================

@Composable
private fun HealthReportScreen(
    snapshot: DeviceSnapshot,
    assessment: HealthAssessment,
    onGenerate: () -> Unit,
    onExportPdf: () -> Unit
) {
    Text(
        "Health Report",
        color = Color.White,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold
    )
    Text("Device overview and recommendations", color = Muted)

    Panel("OVERALL ASSESSMENT") {
        Text(
            "${assessment.score}/100",
            color = when {
                assessment.score >= 85 -> Green
                assessment.score >= 65 -> Warning
                else -> Danger
            },
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold
        )
        Text(assessment.status, color = Color.White)
        Meter(assessment.score / 100f, Green)
        Text(
            "Rule-based estimate from available readings.",
            color = Muted,
            fontSize = 11.sp
        )
    }

    Panel("BATTERY") {
        InfoRow("Charge", "${snapshot.battery}%")
        InfoRow("Charging", snapshot.charging)
        InfoRow("Reported health", snapshot.batteryDiagnostics.health)
        InfoRow(
            "Temperature",
            snapshot.batteryDiagnostics.temperatureC?.let {
                "${it.fmt()} °C"
            } ?: "Not reported"
        )
    }

    Panel("MEMORY") {
        InfoRow("Total RAM", "${snapshot.totalRam.fmt()} GB")
        InfoRow("Used RAM", "${snapshot.usedRam.fmt()} GB")
        InfoRow("Available RAM", "${snapshot.freeRam.fmt()} GB")
    }

    Panel("STORAGE") {
        InfoRow("Total", "${snapshot.totalStorage.fmt()} GB")
        InfoRow("Used", "${snapshot.usedStorage.fmt()} GB")
        InfoRow("Available", "${snapshot.freeStorage.fmt()} GB")
    }

    Panel("DEVICE") {
        InfoRow("Model", Build.MODEL)
        InfoRow("Manufacturer", Build.MANUFACTURER)
        InfoRow("Android", Build.VERSION.RELEASE)
        InfoRow("Hardware", Build.HARDWARE)
    }

    Panel("FINDINGS") {
        assessment.findings.forEachIndexed { i, item ->
            Text("${i + 1}. $item", color = Color.White, fontSize = 12.sp)
        }
    }

    Panel("RECOMMENDATIONS") {
        assessment.recommendations.forEachIndexed { i, item ->
            Text("${i + 1}. $item", color = Green, fontSize = 12.sp)
        }
    }

    Button(
        onClick = onGenerate,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Green,
            contentColor = Bg
        )
    ) {
        Text("Refresh assessment")
    }

    Button(
        onClick = onExportPdf,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Export Report as PDF")
    }
}

// ============================================================
// PDF EXPORT
// ============================================================

private fun writeReportPdf(
    context: Context,
    uri: Uri,
    reportText: String
) {
    val document = PdfDocument()

    val pageWidth = 595
    val pageHeight = 842
    val margin = 40f
    val lineHeight = 16f

    val titlePaint = Paint().apply {
        color = android.graphics.Color.rgb(30, 120, 70)
        textSize = 17f
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    val bodyPaint = Paint().apply {
        color = android.graphics.Color.rgb(35, 40, 36)
        textSize = 10.5f
        isAntiAlias = true
    }

    val headingPaint = Paint().apply {
        color = android.graphics.Color.rgb(30, 120, 70)
        textSize = 12f
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    val maxWidth = pageWidth - margin * 2
    val wrappedLines = mutableListOf<String>()

    reportText.lines().forEach { line ->
        if (line.isBlank()) {
            wrappedLines.add("")
        } else {
            var current = ""

            line.split(Regex("\\s+")).forEach { word ->
                val candidate = if (current.isEmpty()) {
                    word
                } else {
                    "$current $word"
                }

                if (bodyPaint.measureText(candidate) > maxWidth &&
                    current.isNotEmpty()
                ) {
                    wrappedLines.add(current)
                    current = word
                } else {
                    current = candidate
                }
            }

            if (current.isNotEmpty()) wrappedLines.add(current)
        }
    }

    val headings = setOf(
        "OVERALL ASSESSMENT", "BATTERY", "MEMORY",
        "INTERNAL STORAGE", "DEVICE", "FINDINGS",
        "RECOMMENDATIONS", "LIMITATIONS"
    )

    var pageNumber = 1
    var page = document.startPage(
        PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
    )
    var canvas = page.canvas
    var y = 48f

    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.drawText("SYSPULSE HEALTH REPORT", margin, y, titlePaint)
    y += 30f

    for (line in wrappedLines) {
        if (y > pageHeight - 45f) {
            document.finishPage(page)
            pageNumber++

            page = document.startPage(
                PdfDocument.PageInfo.Builder(
                    pageWidth, pageHeight, pageNumber
                ).create()
            )
            canvas = page.canvas
            canvas.drawColor(android.graphics.Color.WHITE)
            y = 48f
            canvas.drawText(
                "SYSPULSE HEALTH REPORT - CONTINUED",
                margin, y, titlePaint
            )
            y += 30f
        }

        val trimmed = line.trim()
        val paint = if (trimmed in headings ||
            trimmed == "SYSPULSE - DEVICE HEALTH REPORT"
        ) {
            headingPaint
        } else {
            bodyPaint
        }

        if (trimmed.isNotEmpty()) {
            canvas.drawText(trimmed, margin, y, paint)
        }

        y += if (trimmed.isEmpty()) 8f else lineHeight
    }

    document.finishPage(page)

    try {
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("Unable to open selected file")

        output.use { document.writeTo(it) }
    } finally {
        document.close()
    }
}

// ============================================================
// ABOUT
// ============================================================

@Composable
private fun AboutScreen() {
    Panel("SYSPULSE MOBILE") {
        Text(
            "Your device information in one simple dashboard.",
            color = Color.White,
            fontSize = 16.sp
        )

        HorizontalDivider(color = Color(0xFF303832))

        listOf(
            "Battery level and diagnostics",
            "RAM and internal storage overview",
            "App CPU activity monitoring",
            "Processor and device information",
            "Rule-based health assessment",
            "PDF health report export",
            "Sensor inventory and accelerometer readings",
            "Vibration test",
            "Speaker test",
            "Microphone input test",
            "Screen color test"
        ).forEach {
            Text("• $it", color = Color.White, fontSize = 13.sp)
        }

        Text(
            "",
            color = Muted,
            fontSize = 12.sp
        )
    }
}

// ============================================================
// REUSABLE COMPONENTS
// ============================================================

@Composable
private fun Panel(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Text(
            title,
            color = Green,
            fontSize = 11.sp,
            letterSpacing = 1.3.sp,
            fontWeight = FontWeight.Bold
        )

        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )

        Spacer(Modifier.width(12.dp))

        Text(
            value.ifBlank { "Not reported" },
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1.2f),
            maxLines = 4
        )
    }
}

@Composable
private fun Meter(progress: Float, color: Color) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
        color = color,
        trackColor = Color(0xFF303832)
    )
}

private fun Double.fmt(): String =
    String.format(Locale.US, "%.1f", this)

private fun Float.fmt(): String =
    String.format(Locale.US, "%.1f", this)