package com.maestudio

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.maestudio.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val sound = SoundPlayer()

    // ── Tabata ───────────────────────────────────────────────────────────────

    enum class Phase { IDLE, WORK, REST }

    private var phase            = Phase.IDLE
    private var workMins         = 5
    private var restMins         = 2
    private var totalCycles      = 5
    private var currentCycle     = 0
    private var remaining        = 0
    private var phaseDurationSec = 0
    private var phaseStartEpoch  = 0L

    private val tabataHandler = Handler(Looper.getMainLooper())
    private val tabataTicker  = object : Runnable {
        override fun run() {
            val elapsed = ((SystemClock.elapsedRealtime() - phaseStartEpoch) / 1000).toInt()
            remaining = (phaseDurationSec - elapsed).coerceAtLeast(0)
            updateRunningDisplay()
            if (remaining <= 0) advancePhase() else tabataHandler.postDelayed(this, 500)
        }
    }

    // ── Metronome ────────────────────────────────────────────────────────────

    private var bpm          = 120.0f
    private var startBpm     = 120.0f
    private var metroRunning = false
    private val metro        = MetronomePlayer()

    // ── Log ──────────────────────────────────────────────────────────────────

    private lateinit var logEntries: MutableList<LogEntry>
    private lateinit var logAdapter: LogAdapter

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupTabs()
        setupTabata()
        setupMetronome()
        setupLog()
        savedInstanceState?.let { restore(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (phase != Phase.IDLE) {
            outState.putInt("phase",         phase.ordinal)
            outState.putInt("workMins",      workMins)
            outState.putInt("restMins",      restMins)
            outState.putInt("totalCycles",   totalCycles)
            outState.putInt("currentCycle",  currentCycle)
            outState.putInt("phaseDuration", phaseDurationSec)
            outState.putLong("phaseStart",   phaseStartEpoch)
        }
    }

    private fun restore(state: Bundle) {
        val p = Phase.values().getOrNull(state.getInt("phase", -1)) ?: return
        if (p == Phase.IDLE) return
        workMins         = state.getInt("workMins",      workMins)
        restMins         = state.getInt("restMins",      restMins)
        totalCycles      = state.getInt("totalCycles",   totalCycles)
        currentCycle     = state.getInt("currentCycle",  1)
        phaseDurationSec = state.getInt("phaseDuration", 0)
        phaseStartEpoch  = state.getLong("phaseStart",   SystemClock.elapsedRealtime())
        phase            = p

        val elapsed = ((SystemClock.elapsedRealtime() - phaseStartEpoch) / 1000).toInt()
        remaining = (phaseDurationSec - elapsed).coerceAtLeast(0)

        b.configSection.visibility  = View.GONE
        b.runningSection.visibility = View.VISIBLE
        setTabataButton(stop = true)
        updateConfigDisplay()
        updateRunningDisplay()

        if (remaining > 0) tabataHandler.postDelayed(tabataTicker, 500)
        else advancePhase()
    }

    override fun onDestroy() {
        super.onDestroy()
        tabataHandler.removeCallbacks(tabataTicker)
        metro.stop()
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────

    private fun setupTabs() {
        b.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                b.focusContainer.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                b.logContainer.visibility   = if (tab.position == 1) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    // ── Log ──────────────────────────────────────────────────────────────────

    private fun setupLog() {
        logEntries = loadLog()
        logAdapter = LogAdapter(logEntries) { saveLog(logEntries) }
        b.rvLog.layoutManager = LinearLayoutManager(this)
        b.rvLog.adapter = logAdapter

        val touchHelper = ItemTouchHelper(LogDragCallback(logAdapter))
        touchHelper.attachToRecyclerView(b.rvLog)
        logAdapter.dragHelper = touchHelper

        b.fabAddLog.setOnClickListener {
            logEntries.add(LogEntry())
            logAdapter.notifyItemInserted(logEntries.size - 1)
            saveLog(logEntries)
            b.rvLog.scrollToPosition(logEntries.size - 1)
        }

        b.etSessionNotes.setText(loadSessionNotes())
        b.etSessionNotes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveSessionNotes(s?.toString() ?: "")
            }
        })
    }

    private fun saveSessionNotes(text: String) {
        getSharedPreferences("maestudio_log", Context.MODE_PRIVATE)
            .edit().putString("session_notes", text).apply()
    }

    private fun loadSessionNotes(): String =
        getSharedPreferences("maestudio_log", Context.MODE_PRIVATE)
            .getString("session_notes", "") ?: ""

    private fun saveLog(entries: List<LogEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("e", e.ejercicio)
                put("b", e.bpm)
                put("m", e.meta)
                put("n", e.notas)
            })
        }
        getSharedPreferences("maestudio_log", Context.MODE_PRIVATE)
            .edit().putString("entries", arr.toString()).apply()
    }

    private fun loadLog(): MutableList<LogEntry> {
        val json = getSharedPreferences("maestudio_log", Context.MODE_PRIVATE)
            .getString("entries", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                LogEntry(o.optString("e"), o.optString("b"), o.optString("m"), o.optString("n"))
            }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    // ── Tabata setup ──────────────────────────────────────────────────────────

    private fun setupTabata() {
        updateConfigDisplay()
        b.btnWorkMinus.setOnClickListener  { workMins    = (workMins    - 1).coerceAtLeast(1);  updateConfigDisplay() }
        b.btnWorkPlus.setOnClickListener   { workMins    = (workMins    + 1).coerceAtMost(60);  updateConfigDisplay() }
        b.btnRestMinus.setOnClickListener  { restMins    = (restMins    - 1).coerceAtLeast(1);  updateConfigDisplay() }
        b.btnRestPlus.setOnClickListener   { restMins    = (restMins    + 1).coerceAtMost(30);  updateConfigDisplay() }
        b.btnCycMinus.setOnClickListener   { totalCycles = (totalCycles - 1).coerceAtLeast(1);  updateConfigDisplay() }
        b.btnCycPlus.setOnClickListener    { totalCycles = (totalCycles + 1).coerceAtMost(20);  updateConfigDisplay() }
        b.btnTabata.setOnClickListener     { if (phase == Phase.IDLE) startTabata() else stopTabata() }
    }

    private fun updateConfigDisplay() {
        b.tvWorkVal.text   = workMins.toString()
        b.tvRestVal.text   = restMins.toString()
        b.tvCyclesVal.text = totalCycles.toString()
    }

    private fun startTabata() {
        phase            = Phase.WORK
        currentCycle     = 1
        phaseDurationSec = workMins * 60
        phaseStartEpoch  = SystemClock.elapsedRealtime()
        remaining        = phaseDurationSec
        b.configSection.visibility  = View.GONE
        b.runningSection.visibility = View.VISIBLE
        setTabataButton(stop = true)
        sound.playWork()
        updateRunningDisplay()
        tabataHandler.postDelayed(tabataTicker, 500)
    }

    private fun stopTabata() {
        tabataHandler.removeCallbacks(tabataTicker)
        phase = Phase.IDLE
        b.configSection.visibility  = View.VISIBLE
        b.runningSection.visibility = View.GONE
        setTabataButton(stop = false)
    }

    private fun advancePhase() {
        when (phase) {
            Phase.WORK -> {
                phase            = Phase.REST
                phaseDurationSec = restMins * 60
                phaseStartEpoch  = SystemClock.elapsedRealtime()
                remaining        = phaseDurationSec
                sound.playRest()
                updateRunningDisplay()
                tabataHandler.postDelayed(tabataTicker, 500)
            }
            Phase.REST -> {
                if (currentCycle >= totalCycles) {
                    sound.playComplete()
                    stopTabata()
                } else {
                    currentCycle++
                    phase            = Phase.WORK
                    phaseDurationSec = workMins * 60
                    phaseStartEpoch  = SystemClock.elapsedRealtime()
                    remaining        = phaseDurationSec
                    sound.playWork()
                    updateRunningDisplay()
                    tabataHandler.postDelayed(tabataTicker, 500)
                }
            }
            Phase.IDLE -> {}
        }
    }

    private fun updateRunningDisplay() {
        b.tvCountdown.text = "%02d:%02d".format(remaining / 60, remaining % 60)
        b.tvCycle.text     = "Ciclo $currentCycle / $totalCycles"
        val color = if (phase == Phase.WORK) getColor(R.color.work) else getColor(R.color.rest)
        b.tvPhase.text     = if (phase == Phase.WORK) "TRABAJO" else "DESCANSO"
        b.tvPhase.setTextColor(color)
        b.tvCountdown.setTextColor(color)
    }

    private fun setTabataButton(stop: Boolean) {
        b.btnTabata.text = if (stop) "DETENER" else "INICIAR"
        b.btnTabata.backgroundTintList = ColorStateList.valueOf(
            getColor(if (stop) R.color.btn_stop else R.color.btn_start)
        )
    }

    // ── Metronome setup ───────────────────────────────────────────────────────

    private fun setupMetronome() {
        b.tvBpm.text = "%.1f".format(bpm)

        b.btnBpm5Minus.setOnClickListener  { changeBpm(-5.0f) }
        b.btnBpm1Minus.setOnClickListener  { changeBpm(-1.0f) }
        b.btnBpmD1Minus.setOnClickListener { changeBpm(-0.1f) }
        b.btnBpm5Plus.setOnClickListener   { changeBpm(+5.0f) }
        b.btnBpm1Plus.setOnClickListener   { changeBpm(+1.0f) }
        b.btnBpmD1Plus.setOnClickListener  { changeBpm(+0.1f) }
        b.btnMetro.setOnClickListener      { if (metroRunning) stopMetro() else startMetro() }

        b.sliderVolume.value = 85f
        b.sliderVolume.addOnChangeListener { _, value, _ -> metro.volume = value / 100f }

        b.sliderAccel.value = 0f
        b.tvAccelVal.text = "0.0"
        b.sliderAccel.addOnChangeListener { _, value, _ ->
            val rounded = (value * 10).roundToInt() / 10.0f
            metro.accelerando = rounded
            b.tvAccelVal.text = "%.1f".format(rounded)
        }
    }

    private fun changeBpm(delta: Float) {
        bpm = ((bpm + delta) * 10).roundToInt() / 10.0f
        bpm = bpm.coerceIn(40.0f, 240.0f)
        b.tvBpm.text = "%.1f".format(bpm)
        metro.bpm = bpm
    }

    private fun startMetro() {
        startBpm = bpm
        metroRunning = true
        metro.bpm    = bpm
        metro.volume = b.sliderVolume.value / 100f
        metro.accelerando = b.sliderAccel.value
        metro.onBpmChanged = { newBpm ->
            bpm = newBpm
            b.tvBpm.text = "%.1f".format(newBpm)
        }
        metro.start()
        setMetroButton(stop = true)
    }

    private fun stopMetro() {
        metroRunning = false
        metro.stop()
        metro.onBpmChanged = null
        bpm = startBpm
        b.tvBpm.text = "%.1f".format(bpm)
        metro.bpm = bpm
        setMetroButton(stop = false)
    }

    private fun setMetroButton(stop: Boolean) {
        b.btnMetro.text = if (stop) "DETENER" else "INICIAR"
        b.btnMetro.backgroundTintList = ColorStateList.valueOf(
            getColor(if (stop) R.color.btn_stop else R.color.btn_start)
        )
    }
}
