package com.ssjrocks.ow18blcd

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var reading by mutableStateOf<Protocol.Reading?>(null)
        private set
    var status by mutableStateOf("")
        private set
    var connected by mutableStateOf(false)
        private set
    val trend = Trend()

    var showGraph by mutableStateOf(prefs.getBoolean("graph", true))
        private set
    var keepScreenOn by mutableStateOf(prefs.getBoolean("keep_screen_on", true))
        private set
    var demo by mutableStateOf(prefs.getBoolean("demo", false))
        private set

    var update by mutableStateOf<UpdateChecker.Update?>(null)
    var updateMessage by mutableStateOf<String?>(null)
    var downloadPercent by mutableStateOf<Int?>(null)
    val updater = UpdateChecker(app)

    private val meter = Meter(app, ::onReading) { s, c -> setStatus(s, c) }
    private var started = false
    private var demoJob: Job? = null

    private fun onReading(r: Protocol.Reading) {
        reading = r
        trend.add(r)
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

    fun stop() {
        if (!started) return
        started = false
        demoJob?.cancel()
        demoJob = null
        meter.stop()
        setStatus("", false)
    }

    fun toggleGraph() { showGraph = !showGraph; prefs.edit().putBoolean("graph", showGraph).apply() }
    fun toggleKeepScreenOn() { keepScreenOn = !keepScreenOn; prefs.edit().putBoolean("keep_screen_on", keepScreenOn).apply() }

    fun toggleDemo() {
        val wasStarted = started
        stop()
        demo = !demo
        prefs.edit().putBoolean("demo", demo).apply()
        trend.clear()
        if (wasStarted) start()
    }

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
        stop()
    }
}
