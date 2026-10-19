package com.ssjrocks.ow18blcd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ssjrocks.appreports.AppReports
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var permissionsGranted by mutableStateOf(false)
    private var visible = false

    private val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
        if (permissionsGranted && visible) vm.start()
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun refreshPermissions() {
        permissionsGranted = permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Recording and alarm notifications need this on Android 13+; asked for when first useful. */
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppReports.attach("meter") {
            "status=${vm.status} connected=${vm.connected} demo=${vm.demo} info=${vm.info} last=${vm.reading?.packet} recording=${vm.recording?.samples?.size}"
        }
        refreshPermissions()
        if (!permissionsGranted && !vm.demo) askPermissions.launch(permissions)
        vm.checkForUpdate(quiet = true)
        setContent {
            AppTheme {
                Screen(vm, permissionsGranted, onAllow = ::allowBluetooth, askNotifications = ::askForNotifications)
                LaunchedEffect(vm.connected, vm.keepScreenOn) {
                    if (vm.connected && vm.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun allowBluetooth() {
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        if (permissions.any { shouldShowRequestPermissionRationale(it) } || !settings.getBoolean("asked", false)) {
            settings.edit().putBoolean("asked", true).apply()
            askPermissions.launch(permissions)
        } else {
            // Denied for good: only the app's settings page can change it now.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        vm.foreground = true
        refreshPermissions()
        if (permissionsGranted || vm.demo) vm.start()
    }

    override fun onStop() {
        super.onStop()
        visible = false
        vm.foreground = false
        // Let go of the meter so its own app (or the desktop) can connect while this one is hidden,
        // unless a recording is running.
        vm.stop()
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Dialog { NONE, INFO, RENAME, CHOOSE, ALARM, OFFLINE, ABOUT }

@Composable
private fun Screen(vm: MainViewModel, permissionsGranted: Boolean, onAllow: () -> Unit, askNotifications: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(Dialog.NONE) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    var saving by remember { mutableStateOf<Series?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val s = saving
        if (uri != null && s != null) vm.saveTo(uri, s)
        saving = null
    }
    val share: (Series) -> Unit = { s -> vm.shareIntent(s)?.let { context.startActivity(it) } }
    val save: (Series) -> Unit = { s -> saving = s; saveAs.launch(vm.fileName(s)) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OW18B LCD")
                        val status = if (!permissionsGranted && !vm.demo) "Bluetooth permission needed" else vm.status
                        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (!vm.demo) {
                        IconButton(onClick = vm::toggleConnection) {
                            Icon(if (vm.meter.paused) Icons.Default.BluetoothDisabled else Icons.Default.Bluetooth,
                                if (vm.meter.paused) "Connect to the meter" else "Disconnect from the meter")
                        }
                    }
                    IconButton(onClick = {
                        if (vm.recording == null) { askNotifications(); tab = 1 }
                        vm.toggleRecording()
                    }) {
                        if (vm.recording != null) Icon(Icons.Default.Stop, "Stop recording", tint = Color(0xFFD32F2F))
                        else Icon(Icons.Default.FiberManualRecord, "Record readings")
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        @Composable
                        fun item(label: String, action: () -> Unit) = DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                        @Composable
                        fun toggle(label: String, on: Boolean, action: () -> Unit) = DropdownMenuItem(
                            text = { Text(label) }, trailingIcon = { if (on) Icon(Icons.Default.Check, null) },
                            onClick = { menu = false; action() },
                        )
                        item("Choose meter…") { dialog = Dialog.CHOOSE }
                        item("Meter info…") { dialog = Dialog.INFO }
                        item("Rename meter…") { dialog = Dialog.RENAME }
                        HorizontalDivider()
                        item("Alarm…") { askNotifications(); dialog = Dialog.ALARM }
                        toggle("Speak readings", vm.speak, vm::toggleSpeak)
                        item("Reset min/max", vm::resetStats)
                        toggle("Trend graph", vm.showGraph, vm::toggleGraph)
                        toggle("Keep screen on", vm.keepScreenOn, vm::toggleKeepScreenOn)
                        toggle("Demo mode", vm.demo, vm::toggleDemo)
                        HorizontalDivider()
                        item("Check for updates") { vm.checkForUpdate(quiet = false) }
                        item("Report a problem") {
                            AppReports.showReportDialog(context, mapOf("status" to vm.status, "demo" to vm.demo, "info" to vm.info?.toString(), "last packet" to vm.reading?.packet))
                        }
                        item("About") { dialog = Dialog.ABOUT }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.update?.let { UpdateCard(vm, it) }
            if (!permissionsGranted && !vm.demo) {
                Banner("OW18B LCD needs permission to find and connect to Bluetooth devices to read the meter.") {
                    Button(onClick = onAllow) { Text("Allow Bluetooth") }
                }
            }
            Banners(vm) { tab = 2 }
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { MeterPanel(vm) }
                    Column(Modifier.weight(1f)) { Pages(vm, tab, { tab = it }, share, save) { dialog = Dialog.OFFLINE } }
                }
            } else {
                MeterPanel(vm)
                Pages(vm, tab, { tab = it }, share, save) { dialog = Dialog.OFFLINE }
            }
        }
    }

    val close = { dialog = Dialog.NONE }
    when (dialog) {
        Dialog.INFO -> InfoDialog(vm, close)
        Dialog.RENAME -> RenameDialog(vm, close)
        Dialog.CHOOSE -> ChooseMeterDialog(vm, close)
        Dialog.ALARM -> AlarmDialog(vm, close)
        Dialog.OFFLINE -> OfflineDialog(vm, close)
        Dialog.ABOUT -> AlertDialog(
            onDismissRequest = close,
            confirmButton = { TextButton(onClick = close) { Text("OK") } },
            title = { Text("OW18B LCD ${BuildConfig.VERSION_NAME}") },
            text = { Text("Live readings, remote keys, recording and offline logs for the OWON OW18B multimeter.\n\ngithub.com/ssjrocks/ow18b-lcd") },
        )
        Dialog.NONE -> {}
    }
    vm.updateMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.updateMessage = null },
            confirmButton = { TextButton(onClick = { vm.updateMessage = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun Banner(text: String, error: Boolean = false, actions: @Composable () -> Unit = {}) {
    val colors = if (error) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

@Composable
private fun Banners(vm: MainViewModel, showOffline: () -> Unit) {
    val time = DateTimeFormatter.ofPattern("EEE HH:mm")
    val pending = vm.pendingOffline
    when {
        vm.connected && vm.info?.recording == true -> Banner("The meter is recording an offline log. Stop it to read it now, or disconnect to let it carry on.") {
            Button(onClick = vm::stopOffline) { Text("Stop recording") }
            OutlinedButton(onClick = vm::toggleConnection) { Text("Disconnect") }
        }
        pending != null && !vm.connected -> {
            val done = if (pending.end.isAfter(java.time.LocalDateTime.now())) "is due to finish" else "finished"
            Banner("Offline log started ${pending.started.format(time)}, $done ${pending.end.format(time)}. Turn the meter's Bluetooth on (hold Hz/Duty △/ᛒ) to read it.")
        }
        pending != null && vm.connected -> Banner("The offline log on the meter is ready to read.") {
            Button(onClick = { showOffline(); vm.readOffline() }) { Text("Read log") }
        }
    }
    val pct = vm.info?.battery
    if (vm.reading?.lowBattery == true || (pct != null && pct <= 20)) {
        Banner("The meter's battery is low" + (pct?.let { " ($it %)" } ?: "") + ": readings and offline logs may be wrong. Fit a new 9 V battery.", error = true)
    }
    vm.alarmMessage?.let { msg ->
        Banner("Alarm: $msg", error = true) {
            TextButton(onClick = { vm.saveAlarm(vm.alarm.copy(enabled = false)) }) { Text("Turn alarm off") }
        }
    }
}

@Composable
private fun ColumnScope.MeterPanel(vm: MainViewModel) {
    Lcd(vm.reading, vm.connected, Modifier.fillMaxWidth(), alarm = vm.alarmMessage != null, recording = vm.recording != null)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Key("Select", Modifier.weight(1f), vm.connected) { long -> if (!long) vm.press(Protocol.KEY_SELECT) }
        Key("Range", Modifier.weight(1f), vm.connected, "hold: auto") { long -> vm.press(Protocol.KEY_RANGE, long) }
        Key("Hold ☀", Modifier.weight(1f), vm.connected, "hold: light") { long -> vm.press(Protocol.KEY_HOLD, long) }
        Key("Hz/Duty △", Modifier.weight(1f), vm.connected) { long -> if (!long) vm.press(Protocol.KEY_HZ) }
    }
    Text(vm.statsText, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Key(label: String, modifier: Modifier, enabled: Boolean, longHint: String? = null, onPress: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.height(52.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(enabled = enabled, onClick = { onPress(false) }, onLongClick = { onPress(true) }),
        color = if (enabled) colors.secondaryContainer else colors.surfaceVariant,
        contentColor = if (enabled) colors.onSecondaryContainer else colors.onSurfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (longHint != null) Text(longHint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun ColumnScope.Pages(vm: MainViewModel, tab: Int, setTab: (Int) -> Unit, share: (Series) -> Unit, save: (Series) -> Unit, startOffline: () -> Unit) {
    TabRow(selectedTabIndex = tab) {
        listOf("Live", "Recording", "Offline log").forEachIndexed { i, t ->
            Tab(selected = tab == i, onClick = { setTab(i) }, text = { Text(t) })
        }
    }
    val graph = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
    when (tab) {
        0 -> if (vm.showGraph) TrendGraph(vm.trend, vm.markers, graph.weight(1f).heightIn(min = 140.dp))
        else Text("The trend graph is off (menu → Trend graph).", Modifier.padding(16.dp))
        1 -> {
            val s = vm.recording ?: vm.lastRecording
            val version by vm.recordingVersion
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s?.title ?: "Tap ⏺ to record readings", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                if (s != null && s.samples.isNotEmpty()) {
                    TextButton(onClick = { share(s) }) { Text("Share") }
                    TextButton(onClick = { save(s) }) { Text("Save as") }
                }
            }
            SeriesPage(s, version, vm.markers, follow = if (vm.recording != null) vm.reading?.function else null, graph)
        }
        else -> {
            val s = vm.offline
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = vm.connected && vm.offlineBusy == null, onClick = startOffline) { Text("Record on meter…") }
                OutlinedButton(enabled = vm.connected && vm.offlineBusy == null, onClick = vm::readOffline) { Text("Read log") }
                if (s != null && s.samples.isNotEmpty()) {
                    TextButton(onClick = { share(s) }) { Text("Share") }
                    TextButton(onClick = { save(s) }) { Text("Save as") }
                }
            }
            vm.offlineBusy?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(s?.title ?: "The meter can log up to 10,000 readings on its own, with nothing connected.", style = MaterialTheme.typography.titleSmall)
            SeriesPage(s, s?.samples?.size ?: 0, vm.markers, follow = null, graph)
        }
    }
}

@Composable
private fun ColumnScope.SeriesPage(s: Series?, version: Int, markers: List<Double>, follow: Int?, graph: Modifier) {
    val functions = remember(s, version) {
        s?.samples?.map { it.reading.function }?.distinct() ?: emptyList()
    }
    var chosen by remember(s) { mutableStateOf<Int?>(null) }
    val function = follow ?: chosen ?: functions.lastOrNull()
    if (functions.size > 1 && follow == null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            functions.forEach { f ->
                val (mode, _) = Protocol.FUNCTIONS[f] ?: ("" to "")
                FilterChip(selected = f == function, onClick = { chosen = f },
                    label = { Text((Protocol.FUNCTION_NAMES[f] ?: "$f") + if (mode.isNotEmpty()) " ($mode)" else "") })
            }
        }
    }
    SeriesGraph(s, function, version, markers, graph.weight(1f).heightIn(min = 120.dp))
    val rows = remember(s, version) { s?.samples?.asReversed()?.take(500) ?: emptyList() }
    if (rows.isNotEmpty()) {
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
        LazyColumn(Modifier.fillMaxWidth().weight(0.6f)) {
            items(rows) { smp ->
                val r = smp.reading
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(Instant.ofEpochMilli(smp.time).atZone(ZoneId.systemDefault()).format(fmt), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("${r.digits} ${r.unit}".trim(), Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(r.name, style = MaterialTheme.typography.bodySmall)
                    Text(r.flags, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
        if (s != null && s.samples.size > rows.size) {
            Text("Newest 500 of ${s.samples.size} shown; Share or Save as has them all.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UpdateCard(vm: MainViewModel, u: UpdateChecker.Update) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Version ${u.versionName} is available", style = MaterialTheme.typography.titleMedium)
            Text(
                "Released ${u.published}" + if (u.size > 0) " · %.1f MB".format(u.size / 1e6) else "",
                style = MaterialTheme.typography.bodySmall,
            )
            if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val percent = vm.downloadPercent
            if (percent != null) {
                LinearProgressIndicator(progress = { percent / 100f }, Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        error = null
                        vm.downloadPercent = 0
                        vm.updater.download(u, { vm.downloadPercent = it }) { file, err ->
                            vm.downloadPercent = null
                            if (file != null) context.startActivity(vm.updater.installIntent(file)) else error = err
                        }
                    }) { Text("Install") }
                    TextButton(onClick = { vm.update = null }) { Text("Later") }
                }
            }
        }
    }
}
