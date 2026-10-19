package com.ssjrocks.ow18blcd

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var reading by mutableStateOf<Protocol.Reading?>(null)
        private set
    var status by mutableStateOf("")
        private set
    var connected by mutableStateOf(false)
        private set
    var info by mutableStateOf<Protocol.DeviceInfo?>(null)
        private set
    val trend = Trend()
    private val stats = Stats()
    var statsText by mutableStateOf(stats.describe())
        private set

    var showGraph by mutableStateOf(prefs.getBoolean("graph", true))
        private set
    var keepScreenOn by mutableStateOf(prefs.getBoolean("keep_screen_on", true))
        private set
    var demo by mutableStateOf(prefs.getBoolean("demo", false))
        private set
    var speak by mutableStateOf(prefs.getBoolean("speak", false))
        private set
    var alarm by mutableStateOf(loadAlarm())
        private set
    var alarmMessage by mutableStateOf<String?>(null)
        private set

    var recording by mutableStateOf<Series?>(null)
        private set
    var lastRecording by mutableStateOf<Series?>(null)
        private set
    /** Bumped when the recording grows, so its graph and list redraw. */
    val recordingVersion = mutableIntStateOf(0)
    var offline by mutableStateOf<Series?>(null)
        private set
    var offlineBusy by mutableStateOf<String?>(null)
        private set
    var pendingOffline by mutableStateOf(loadPending())
        private set

    /** One-off messages for the snackbar. */
    var message by mutableStateOf<String?>(null)

    var update by mutableStateOf<UpdateChecker.Update?>(null)
    var updateMessage by mutableStateOf<String?>(null)
    var downloadPercent by mutableStateOf<Int?>(null)
    val updater = UpdateChecker(app)

    val meter = Meter(app, ::onReading, ::setStatus) { i ->
        info = i
        if (i.recording && pendingOffline == null) savePending(Pending(LocalDateTime.now(), 1, 1))
    }
    val meterName: String get() = if (demo) "BDM (demo)" else meter.name
    private var started = false
    private var demoJob: Job? = null
    var foreground = false

    // --- connection ---

    private fun onReading(r: Protocol.Reading) {
        reading = r
        trend.add(r)
        stats.add(r)
        statsText = stats.describe()
        recording?.let {
            it.add(r, System.currentTimeMillis())
            recordingVersion.intValue++
            RecordingService.update(app, it.samples.size)
        }
        checkAlarm(r)
        maybeSpeak(r)
    }

    private fun setStatus(s: String, c: Boolean) {
        status = s
        connected = c
        if (!c) reading = null
    }

    fun start() {
        if (started) return
        started = true
        if (demo) {
            setStatus("Demo (recorded readings)", true)
            info = Protocol.DEMO_INFO
            demoJob = viewModelScope.launch {
                while (true) for ((hex, seconds) in Protocol.DEMO) repeat(seconds * 2) {
                    Protocol.decode(Protocol.hex(hex))?.let(::onReading)
                    delay(500)
                }
            }
        } else {
            meter.start()
        }
    }

    /** Called when the app goes to the background: keeps the meter while recording. */
    fun stop(force: Boolean = false) {
        if (!started || (recording != null && !force)) return
        started = false
        demoJob?.cancel()
        demoJob = null
        meter.stop()
        setStatus("", false)
    }

    fun toggleConnection() {
        if (demo) return
        meter.setPaused(!meter.paused)
        if (!meter.paused) message = "Connecting…"
    }

    fun useMeter(address: String) {
        meter.useAddress(address)
    }

    // --- settings ---

    fun toggleGraph() { showGraph = !showGraph; prefs.edit().putBoolean("graph", showGraph).apply() }
    fun toggleKeepScreenOn() { keepScreenOn = !keepScreenOn; prefs.edit().putBoolean("keep_screen_on", keepScreenOn).apply() }

    fun toggleSpeak() {
        speak = !speak
        prefs.edit().putBoolean("speak", speak).apply()
        if (speak) tts()
    }

    fun toggleDemo() {
        val wasStarted = started
        if (recording != null) stopRecording()
        stop(force = true)
        demo = !demo
        prefs.edit().putBoolean("demo", demo).apply()
        trend.clear()
        if (wasStarted) start()
    }

    // --- keys and commands ---

    fun press(key: Int, long: Boolean = false) {
        if (!connected || demo) return
        meter.press(key, if (long) Protocol.LONG else Protocol.SHORT) { e -> if (e != null) message = "Key not sent: ${e.message}" }
    }

    fun refreshInfo(done: (Protocol.DeviceInfo?, String?) -> Unit) {
        if (demo) return done(Protocol.DEMO_INFO, null)
        meter.readInfo { i, e -> done(i ?: info, e?.message) }
    }

    fun rename(name: String) {
        if (demo) { message = "Demo mode: nothing to rename"; return }
        meter.rename(name) { e ->
            message = if (e == null) "Renamed to $name. The new name shows after the meter restarts Bluetooth." else "Couldn't rename: ${e.message}"
        }
    }

    fun resetStats() {
        stats.reset()
        statsText = stats.describe()
    }

    // --- alarm and speech ---

    fun saveAlarm(a: Alarm) {
        alarm = a
        prefs.edit().putBoolean("alarm_enabled", a.enabled).putString("alarm_unit", a.unit)
            .putString("alarm_low", a.low?.toString()).putString("alarm_high", a.high?.toString()).apply()
        if (!a.enabled) alarmMessage = null
    }

    private fun loadAlarm() = Alarm(
        prefs.getBoolean("alarm_enabled", false), prefs.getString("alarm_unit", "V") ?: "V",
        prefs.getString("alarm_low", null)?.toDoubleOrNull(), prefs.getString("alarm_high", null)?.toDoubleOrNull(),
    )

    val markers: List<Double>
        get() = if (alarm.enabled && reading?.baseUnit == alarm.unit) listOfNotNull(alarm.low, alarm.high) else emptyList()

    private var alarmSoundedAt = 0L

    private fun checkAlarm(r: Protocol.Reading) {
        val msg = alarm.check(r)
        if (msg != null && alarmMessage == null && SystemClock.elapsedRealtime() - alarmSoundedAt > 3000) {
            alarmSoundedAt = SystemClock.elapsedRealtime()
            runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600) }
            runCatching {
                app.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
            }
            if (speak) say("Alarm. ${msg.replace("µ", "micro")}")
            if (!foreground) notifyAlarm(msg)
        }
        alarmMessage = msg
    }

    private fun notifyAlarm(msg: String) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(ALARMS, "Alarms", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(app, 1, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, ALARMS).setSmallIcon(R.drawable.ic_stat_meter)
            .setContentTitle("OW18B LCD alarm").setContentText(msg).setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(2, n) }
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var spokenAt = 0L

    private fun tts(): TextToSpeech? {
        if (tts == null) tts = TextToSpeech(app) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        return tts
    }

    private fun maybeSpeak(r: Protocol.Reading) {
        val now = SystemClock.elapsedRealtime()
        if (speak && now - spokenAt >= 5000) {
            spokenAt = now
            say(r.spoken)
        }
    }

    private fun say(text: String) {
        val t = tts() ?: return
        if (ttsReady && !t.isSpeaking) t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reading")
    }

    // --- recording on the phone ---

    fun toggleRecording() = if (recording == null) startRecording() else stopRecording()

    private fun startRecording() {
        val s = Series("Recording, " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        recording = s
        recordingVersion.intValue++
        RecordingService.start(app)
    }

    fun stopRecording() {
        val s = recording ?: return
        recording = null
        RecordingService.stop(app)
        if (s.samples.isNotEmpty()) {
            lastRecording = s
            val f = autosave(s, "recording", s.samples.first().time)
            message = "${s.samples.size} readings saved" + (f?.let { " as ${it.name}" } ?: "")
        }
        if (!foreground) stop()
    }

    private fun stamp(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

    fun fileName(s: Series) = "${s.source}-${stamp(s.samples.firstOrNull()?.time ?: System.currentTimeMillis())}.csv"

    /** Saved copies live in the app's own folder (Android/data/…/files/recordings); Share or Save as get them out. */
    private fun autosave(s: Series, kind: String, startMs: Long): File? = runCatching {
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "recordings").apply { mkdirs() }
        File(dir, "$kind-${stamp(startMs)}.csv").also { f -> f.bufferedWriter().use { s.writeCsv(it) } }
    }.getOrNull()

    fun shareIntent(s: Series): Intent? = runCatching {
        val dir = File(app.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, fileName(s)).also { f -> f.bufferedWriter().use { s.writeCsv(it) } }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)
        Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, s.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share readings")
    }.onFailure { message = "Couldn't share: ${it.message}" }.getOrNull()

    fun saveTo(uri: Uri, s: Series) {
        runCatching {
            app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { s.writeCsv(it) } ?: error("can't write there")
        }.onSuccess { message = "Saved ${s.samples.size} readings" }.onFailure { message = "Couldn't save: ${it.message}" }
    }

    // --- offline log on the meter ---

    data class Pending(val started: LocalDateTime, val interval: Int, val count: Int) {
        val end: LocalDateTime get() = started.plusSeconds(interval.toLong() * count)
    }

    private fun loadPending(): Pending? = prefs.getString("offline_started", null)?.let {
        runCatching { Pending(LocalDateTime.parse(it), prefs.getInt("offline_interval", 1), prefs.getInt("offline_count", 1)) }.getOrNull()
    }

    private fun savePending(p: Pending?) {
        pendingOffline = p
        prefs.edit().apply {
            if (p == null) remove("offline_started") else putString("offline_started", p.started.toString())
                .putInt("offline_interval", p.interval).putInt("offline_count", p.count)
        }.apply()
    }

    fun startOffline(interval: Int, count: Int) {
        if (demo) {
            savePending(Pending(LocalDateTime.now(), interval, count))
            message = "Demo mode: pretending to record"
            return
        }
        meter.startRecording(interval, count) { e ->
            if (e == null) {
                savePending(Pending(LocalDateTime.now(), interval, count))
                message = "Recording on the meter. Its Bluetooth is now off."
            } else message = "Couldn't start: ${e.message}"
        }
    }

    fun stopOffline() {
        meter.stopRecording { e -> message = if (e == null) "Recording stopped. You can read the log now." else "Couldn't stop: ${e.message}" }
    }

    fun readOffline() {
        offlineBusy = "Reading the log from the meter…"
        val done = { log: Protocol.OfflineLog?, e: Throwable? ->
            offlineBusy = null
            if (log != null) {
                val s = Series.fromOffline(log)
                offline = s
                savePending(null)
                if (s.samples.isNotEmpty()) {
                    val f = autosave(s, "offline", s.samples.first().time)
                    message = "${s.samples.size} readings read" + (f?.let { ", saved as ${it.name}" } ?: "")
                } else message = "The meter's log is empty"
            } else message = "Couldn't read the log: ${e?.message}"
        }
        if (demo) return done(demoLog(), null)
        meter.readLog({ n -> offlineBusy = "Reading the log from the meter… $n bytes" }, done)
    }

    private fun demoLog(): Protocol.OfflineLog {
        val start = LocalDateTime.now().withNano(0).minusMinutes(10)
        return Protocol.OfflineLog(start, 5, (0 until 120).map { i ->
            i * 5L to Protocol.Reading(0, 4, 3, (1500 + 300 * kotlin.math.sin(i / 9.0)).toInt())
        })
    }

    // --- updates ---

    /** Looks for an update; [quiet] checks at most every 12 hours and says nothing when there's none. */
    fun checkForUpdate(quiet: Boolean) {
        val now = System.currentTimeMillis()
        if (quiet && now - prefs.getLong("update_checked_at", 0) < 12 * 3_600_000L) return
        prefs.edit().putLong("update_checked_at", now).apply()
        if (!quiet) updateMessage = "Checking for updates…"
        updater.check { u, error ->
            update = u
            updateMessage = when {
                quiet -> null
                error != null -> "Couldn't check for updates: $error"
                u == null -> "OW18B LCD ${BuildConfig.VERSION_NAME} is the latest version."
                else -> null
            }
        }
    }

    override fun onCleared() {
        stopRecording()
        stop(force = true)
        tts?.shutdown()
    }

    companion object {
        const val ALARMS = "alarms"
    }
}
