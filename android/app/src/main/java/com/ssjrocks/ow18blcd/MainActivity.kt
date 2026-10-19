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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ssjrocks.appreports.AppReports

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

    private fun refreshPermissions() {
        permissionsGranted = permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppReports.attach("meter") { "status=${vm.status} connected=${vm.connected} demo=${vm.demo} last=${vm.reading?.packet}" }
        refreshPermissions()
        if (!permissionsGranted && !vm.demo) askPermissions.launch(permissions)
        vm.checkForUpdate(quiet = true)
        setContent {
            AppTheme {
                Screen(vm, permissionsGranted, onAllow = ::allowBluetooth)
                LaunchedEffect(vm.connected, vm.keepScreenOn) {
                    if (vm.connected && vm.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun allowBluetooth() {
        if (permissions.any { shouldShowRequestPermissionRationale(it) } || !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("asked", false)) {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("asked", true).apply()
            askPermissions.launch(permissions)
        } else {
            // Denied for good: only the app's settings page can change it now.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        refreshPermissions()
        if (permissionsGranted || vm.demo) vm.start()
    }

    override fun onStop() {
        super.onStop()
        visible = false
        // Let go of the meter so its own app (or the desktop) can connect while this one is hidden.
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

@Composable
private fun Screen(vm: MainViewModel, permissionsGranted: Boolean, onAllow: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
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
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        @Composable
                        fun toggle(label: String, on: Boolean, action: () -> Unit) = DropdownMenuItem(
                            text = { Text(label) },
                            trailingIcon = { if (on) Icon(Icons.Default.Check, null) },
                            onClick = { menu = false; action() },
                        )
                        toggle("Trend graph", vm.showGraph, vm::toggleGraph)
                        toggle("Keep screen on", vm.keepScreenOn, vm::toggleKeepScreenOn)
                        toggle("Demo mode", vm.demo, vm::toggleDemo)
                        DropdownMenuItem(text = { Text("Clear graph") }, onClick = { menu = false; vm.trend.clear() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Check for updates") }, onClick = { menu = false; vm.checkForUpdate(quiet = false) })
                        DropdownMenuItem(text = { Text("Report a problem") }, onClick = {
                            menu = false
                            AppReports.showReportDialog(context, mapOf("status" to vm.status, "demo" to vm.demo, "last packet" to vm.reading?.packet))
                        })
                        DropdownMenuItem(text = { Text("About") }, onClick = { menu = false; about = true })
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.update?.let { UpdateCard(vm, it) }
            if (!permissionsGranted && !vm.demo) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("OW18B LCD needs permission to find and connect to Bluetooth devices to read the meter.")
                        Button(onClick = onAllow) { Text("Allow Bluetooth") }
                    }
                }
            }
            val graphModifier = Modifier.clip(RoundedCornerShape(8.dp))
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Lcd(vm.reading, vm.connected, Modifier.fillMaxWidth()) }
                    if (vm.showGraph) TrendGraph(vm.trend, graphModifier.weight(1f).fillMaxSize())
                }
            } else {
                Lcd(vm.reading, vm.connected, Modifier.fillMaxWidth())
                if (vm.showGraph) TrendGraph(vm.trend, graphModifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp))
            }
        }
    }

    vm.updateMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.updateMessage = null },
            confirmButton = { TextButton(onClick = { vm.updateMessage = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
            title = { Text("OW18B LCD ${BuildConfig.VERSION_NAME}") },
            text = {
                Text("Live readings from an OWON OW18B multimeter over Bluetooth.\n\ngithub.com/ssjrocks/ow18b-lcd")
            },
        )
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
            if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodyMedium)
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
