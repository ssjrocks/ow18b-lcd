package com.ssjrocks.ow18blcd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun duration(seconds: Long): String {
    val d = seconds / 86400; val h = seconds % 86400 / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
    return listOfNotNull(d.takeIf { it > 0 }?.let { "$it d" }, h.takeIf { it > 0 }?.let { "$it h" },
        m.takeIf { it > 0 }?.let { "$it min" }, s.takeIf { it > 0 && d == 0L }?.let { "$it s" }).joinToString(" ").ifEmpty { "0 s" }
}

@Composable
fun InfoDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var info by remember { mutableStateOf(vm.info) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshInfo { i, e -> info = i; note = e?.let { "Couldn't read the meter: $it" } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Meter") },
        text = {
            val i = info
            Text(listOfNotNull(
                "Name: ${vm.meterName}",
                "Address: ${vm.meter.address ?: "–"}",
                i?.let { "Model: ${it.model}" }, i?.let { "Firmware: ${it.firmware}" },
                i?.let { if (it.battery != null) "Battery: ${it.battery} %" else "Battery: not reported" },
                i?.let { if (it.recording) "Offline log: recording" else "Offline log: not recording" },
                note,
            ).joinToString("\n"))
        },
    )
}

@Composable
fun RenameDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(vm.meterName.takeIf { it != Protocol.NAME } ?: "") }
    val ok = Protocol.NAME_CHARS.matches(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = ok, onClick = { vm.rename(name); onDismiss() }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rename the meter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The name is stored in the meter and is what it advertises over Bluetooth. Letters, digits and underscores, up to 15.")
                OutlinedTextField(name, { name = it.take(15) }, singleLine = true, isError = name.isNotEmpty() && !ok,
                    placeholder = { Text("e.g. Bench_1") })
            }
        },
    )
}

@Composable
fun ChooseMeterDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var found by remember { mutableStateOf<List<Meter.Found>?>(null) }
    var scan by remember { mutableIntStateOf(0) }
    LaunchedEffect(scan) {
        found = null
        if (vm.demo) found = listOf(Meter.Found("00:00:00:00:00:00", "BDM (demo)", -50))
        else vm.meter.scanDevices(6) { found = it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(enabled = found != null, onClick = { scan++ }) { Text("Scan again") } },
        title = { Text("Choose a meter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = found
                if (list == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text("Looking for Bluetooth devices…")
                    }
                } else if (list.isEmpty()) {
                    Text("No devices found.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(list) { d ->
                            Column(Modifier.fillMaxWidth().clickable { vm.useMeter(d.address); onDismiss() }.padding(vertical = 10.dp)) {
                                Text(d.name, style = MaterialTheme.typography.titleMedium)
                                Text(d.address + (if (d.address == vm.meter.address) "   (this meter)" else "") +
                                    (if (d.rssi != 0) "   ${d.rssi} dBm" else ""), style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Text("OW18B meters advertise as “BDM” unless renamed. A meter only shows here when its Bluetooth is on and no other app is connected to it.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
fun AlarmDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val unit = vm.reading?.baseUnit?.takeIf { it.isNotEmpty() } ?: vm.alarm.unit
    val same = vm.alarm.unit == unit
    fun show(v: Double?) = v?.let { Si.format(it, unit).replace(" ", "").removeSuffix(unit) } ?: ""
    var enabled by remember { mutableStateOf(vm.alarm.enabled || !same) }
    var low by remember { mutableStateOf(if (same) show(vm.alarm.low) else "") }
    var high by remember { mutableStateOf(if (same) show(vm.alarm.high) else "") }
    fun parse(t: String): Result<Double?> = if (t.isBlank()) Result.success(null) else runCatching { Si.parse(t, unit) }
    val lowV = parse(low); val highV = parse(high)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = lowV.isSuccess && highV.isSuccess, onClick = {
                vm.saveAlarm(Alarm(enabled, unit, lowV.getOrNull(), highV.getOrNull())); onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Alarm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sounds, vibrates and shows a warning when a reading goes outside the limits, in $unit for the function the meter is on now. You can type prefixes: 450m, 1.2k. Leave a limit empty to not check it.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alarm on", Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }
                OutlinedTextField(low, { low = it }, label = { Text("Low limit ($unit)") }, singleLine = true, isError = lowV.isFailure)
                OutlinedTextField(high, { high = it }, label = { Text("High limit ($unit)") }, singleLine = true, isError = highV.isFailure)
            }
        },
    )
}

@Composable
fun OfflineDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var interval by remember { mutableStateOf("1") }
    var count by remember { mutableStateOf("600") }
    val i = interval.toIntOrNull()?.takeIf { it in 1..86400 }
    val n = count.toIntOrNull()?.takeIf { it in 1..Protocol.MAX_RECORDS }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = i != null && n != null, onClick = { vm.startOffline(i!!, n!!); onDismiss() }) { Text("Start recording") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Record on the meter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The meter logs readings into its own memory, up to 10,000, with nothing connected. It replaces the log already in the meter.\n\n" +
                    "The meter turns Bluetooth off as it starts. To read the log, turn it back on with a long press of Hz/Duty △/ᛒ, then tap Read log. Keep the dial where it is.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text("Interval (seconds)") }, singleLine = true,
                    isError = i == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(count, { count = it.filter(Char::isDigit) }, label = { Text("Readings (up to 10,000)") }, singleLine = true,
                    isError = n == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (i != null && n != null) {
                    val secs = i.toLong() * n
                    val end = LocalDateTime.now().plusSeconds(secs).format(DateTimeFormatter.ofPattern("EEE HH:mm"))
                    Text("Takes ${duration(secs)}, until about $end", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}
