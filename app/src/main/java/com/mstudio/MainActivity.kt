package com.mstudio

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.mstudio.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val sound = SoundPlayer()

    // ── Tabata ───────────────────────────────────────────────────────────────

    enum class Phase { IDLE, WORK, REST }

    private var phase        = Phase.IDLE
    private var workMins     = 5
    private var restMins     = 2
    private var totalCycles  = 5
    private var currentCycle = 0
    private var remaining    = 0

    private val tabataHandler = Handler(Looper.getMainLooper())
    private val tabataTicker  = object : Runnable {
        override fun run() {
            remaining--
            updateRunningDisplay()
            if (remaining <= 0) advancePhase() else tabataHandler.postDelayed(this, 1000)
        }
    }

    // ── Metronome ────────────────────────────────────────────────────────────

    private var bpm          = 120.0f
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
    }

    private fun saveLog(entries: List<LogEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("e", e.ejercicio)
                put("b", e.bpm)
                put("n", e.notas)
            })
        }
        getSharedPreferences("mstudio_log", Context.MODE_PRIVATE)
            .edit().putString("entries", arr.toString()).apply()
    }

    private fun loadLog(): MutableList<LogEntry> {
        val json = getSharedPreferences("mstudio_log", Context.MODE_PRIVATE)
            .getString("entries", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                LogEntry(o.optString("e"), o.optString("b"), o.optString("n"))
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
        phase        = Phase.WORK
        currentCycle = 1
        remaining    = workMins * 60
        b.configSection.visibility  = View.GONE
        b.runningSection.visibility = View.VISIBLE
        setTabataButton(stop = true)
        sound.playWork()
        updateRunningDisplay()
        tabataHandler.postDelayed(tabataTicker, 1000)
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
                phase     = Phase.REST
                remaining = restMins * 60
                sound.playRest()
                updateRunningDisplay()
                tabataHandler.postDelayed(tabataTicker, 1000)
            }
            Phase.REST -> {
                if (currentCycle >= totalCycles) {
                    sound.playComplete()
                    stopTabata()
                } else {
                    currentCycle++
                    phase     = Phase.WORK
                    remaining = workMins * 60
                    sound.playWork()
                    updateRunningDisplay()
                    tabataHandler.postDelayed(tabataTicker, 1000)
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
    }

    private fun changeBpm(delta: Float) {
        bpm = ((bpm + delta) * 10).roundToInt() / 10.0f
        bpm = bpm.coerceIn(40.0f, 240.0f)
        b.tvBpm.text = "%.1f".format(bpm)
        metro.bpm = bpm
    }

    private fun startMetro() {
        metroRunning = true
        metro.bpm    = bpm
        metro.volume = b.sliderVolume.value / 100f
        metro.start()
        setMetroButton(stop = true)
    }

    private fun stopMetro() {
        metroRunning = false
        metro.stop()
        setMetroButton(stop = false)
    }

    private fun setMetroButton(stop: Boolean) {
        b.btnMetro.text = if (stop) "DETENER" else "INICIAR"
        b.btnMetro.backgroundTintList = ColorStateList.valueOf(
            getColor(if (stop) R.color.btn_stop else R.color.btn_start)
        )
    }
}
