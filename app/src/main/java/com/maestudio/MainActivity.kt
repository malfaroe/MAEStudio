package com.maestudio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.maestudio.PracticeEngine.Phase
import com.maestudio.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), PracticeEngine.Listener {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: PracticeStore

    // El metronomo y el Tabata corren en PracticeEngine; esta pantalla solo los
    // muestra y les da ordenes, asi siguen aunque Android la cierre.
    private val engine = PracticeEngine
    private val metro get() = engine.metro

    // ── Tabata (configuracion) ───────────────────────────────────────────────

    private var workMins    = 5
    private var restMins    = 2
    private var totalCycles = 5

    // ── Metronome ────────────────────────────────────────────────────────────

    private var bpm            = 120.0f
    private var startBpm       = 120.0f
    private var volume         = 85f
    private var accel          = 0f
    private var selectedFigura = ""   // empty = libre (no accent)

    private val figuras = mapOf(
        "4/4" to Pair(4, setOf(0)),
        "3/4" to Pair(3, setOf(0)),
        "6/8" to Pair(6, setOf(0, 3)),
        "5/4" to Pair(5, setOf(0))
    )

    // ── Log e historial ──────────────────────────────────────────────────────

    private lateinit var logEntries: MutableList<LogEntry>
    private lateinit var logAdapter: LogAdapter
    private lateinit var sessions: MutableList<PracticeSession>
    private lateinit var historyAdapter: HistoryAdapter

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { exportTo(it) }
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { confirmImport(it) }
        }

    // ── Segundo plano ────────────────────────────────────────────────────────

    private var askedNotifications = false
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askBatteryExemption() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = PracticeStore(this)
        engine.init(this)
        loadSettings()
        // Si la practica siguio sin pantalla, se retoma el BPM que lleva sonando.
        if (engine.metroRunning) bpm = metro.bpm
        setupTabs()
        setupTabata()
        setupMetronome()
        setupLog()
        setupHistory()
        savedInstanceState?.let { restore(it) }
        engine.listener = this
        renderPractice()
    }

    override fun onPracticeChanged() = renderPractice()

    override fun onBpmChanged(bpm: Float) {
        this.bpm = bpm
        b.tvBpm.text = "%.1f".format(bpm)
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
        store.saveEntries(logEntries)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (engine.phase != Phase.IDLE) {
            outState.putInt("phase",         engine.phase.ordinal)
            outState.putInt("workMins",      engine.workMins)
            outState.putInt("restMins",      engine.restMins)
            outState.putInt("totalCycles",   engine.totalCycles)
            outState.putInt("currentCycle",  engine.currentCycle)
            outState.putInt("phaseDuration", engine.phaseDurationSec)
            outState.putLong("phaseStart",   engine.phaseStart)
        }
    }

    // Solo actua si Android cerro el proceso: si no, el Tabata sigue vivo en el motor.
    private fun restore(state: Bundle) {
        val p = Phase.values().getOrNull(state.getInt("phase", -1)) ?: return
        engine.resumeTabata(
            p,
            state.getInt("workMins",      workMins),
            state.getInt("restMins",      restMins),
            state.getInt("totalCycles",   totalCycles),
            state.getInt("currentCycle",  1),
            state.getInt("phaseDuration", 0),
            state.getLong("phaseStart",   SystemClock.elapsedRealtime())
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // No se detiene nada: la practica sigue hasta que se pare aqui o en la notificacion.
        if (engine.listener === this) engine.listener = null
    }

    // ── Ajustes ──────────────────────────────────────────────────────────────

    private fun loadSettings() {
        val prefs = getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        bpm            = prefs.getFloat("bpm", bpm).coerceIn(MIN_BPM, MAX_BPM)
        startBpm       = bpm
        volume         = prefs.getFloat("volume", volume).roundToInt().coerceIn(0, 100).toFloat()
        accel          = (prefs.getFloat("accel", accel).coerceIn(0f, 2f) * 10).roundToInt() / 10.0f
        selectedFigura = (prefs.getString("figura", "") ?: "").takeIf { it in figuras } ?: ""
        workMins       = prefs.getInt("workMins", workMins).coerceIn(1, 60)
        restMins       = prefs.getInt("restMins", restMins).coerceIn(1, 30)
        totalCycles    = prefs.getInt("totalCycles", totalCycles).coerceIn(1, 20)
    }

    private fun saveSettings() {
        getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
            .putFloat("bpm", bpm)
            .putFloat("volume", b.sliderVolume.value)
            .putFloat("accel", b.sliderAccel.value)
            .putString("figura", selectedFigura)
            .putInt("workMins", workMins)
            .putInt("restMins", restMins)
            .putInt("totalCycles", totalCycles)
            .apply()
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────

    private fun setupTabs() {
        b.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                b.focusContainer.visibility   = if (tab.position == 0) View.VISIBLE else View.GONE
                b.logContainer.visibility     = if (tab.position == 1) View.VISIBLE else View.GONE
                b.historyContainer.visibility = if (tab.position == 2) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    // ── Log ──────────────────────────────────────────────────────────────────

    private fun setupLog() {
        logEntries = store.loadEntries()
        logAdapter = LogAdapter(
            items       = logEntries,
            onChanged   = { store.saveEntries(logEntries) },
            onRemoved   = { pos, entry -> offerLogUndo(pos, entry) },
            onUseBpm    = { entry -> useBpmFrom(entry) },
            onRecordBpm = { pos -> recordBpmIn(pos) }
        )
        b.rvLog.layoutManager = LinearLayoutManager(this)
        b.rvLog.adapter = logAdapter

        val touchHelper = ItemTouchHelper(LogDragCallback(logAdapter))
        touchHelper.attachToRecyclerView(b.rvLog)
        logAdapter.dragHelper = touchHelper

        b.fabAddLog.setOnClickListener {
            logEntries.add(LogEntry())
            logAdapter.notifyItemInserted(logEntries.size - 1)
            store.saveEntries(logEntries)
            b.rvLog.scrollToPosition(logEntries.size - 1)
        }
        b.btnSaveSession.setOnClickListener { saveSession() }

        b.etSessionNotes.setText(store.loadNotes())
        b.etSessionNotes.doAfterTextChanged { text -> store.saveNotes(text?.toString() ?: "") }
    }

    private fun offerLogUndo(pos: Int, entry: LogEntry) {
        Snackbar.make(b.root, "Ejercicio eliminado", Snackbar.LENGTH_LONG)
            .setAction("DESHACER") { logAdapter.restoreItem(pos, entry) }
            .show()
    }

    private fun useBpmFrom(entry: LogEntry) {
        val value = parseBpm(entry.bpm)
        if (value == null) {
            showMessage("Este ejercicio no tiene BPM")
            return
        }
        setBpm(value)
        b.tabLayout.getTabAt(0)?.select()
    }

    private fun recordBpmIn(pos: Int) {
        logEntries[pos].bpm = String.format(Locale.US, "%.1f", bpm)
        logAdapter.notifyItemChanged(pos)
        store.saveEntries(logEntries)
    }

    private fun saveSession() {
        val entries = logEntries
            .filter { it.ejercicio.isNotBlank() || it.bpm.isNotBlank() }
            .map { it.copy() }
        val notes = b.etSessionNotes.text.toString().trim()
        if (entries.isEmpty() && notes.isEmpty()) {
            showMessage("No hay datos para guardar")
            return
        }
        historyAdapter.restoreItem(0, PracticeSession(System.currentTimeMillis(), notes, entries))
        showMessage("Sesión guardada en el historial")
    }

    private fun showMessage(text: String) {
        Snackbar.make(b.root, text, Snackbar.LENGTH_SHORT).show()
    }

    // ── Historial y respaldo ─────────────────────────────────────────────────

    private fun setupHistory() {
        sessions = store.loadSessions()
        historyAdapter = HistoryAdapter(
            sessions  = sessions,
            onChanged = {
                store.saveSessions(sessions)
                updateHistoryEmpty()
            },
            onRemoved = { pos, session ->
                Snackbar.make(b.root, "Sesión eliminada", Snackbar.LENGTH_LONG)
                    .setAction("DESHACER") { historyAdapter.restoreItem(pos, session) }
                    .show()
            }
        )
        b.rvHistory.layoutManager = LinearLayoutManager(this)
        b.rvHistory.adapter = historyAdapter
        ItemTouchHelper(HistorySwipeCallback(historyAdapter)).attachToRecyclerView(b.rvHistory)
        updateHistoryEmpty()

        b.btnExport.setOnClickListener {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            exportLauncher.launch("maestudio-respaldo-$date.json")
        }
        b.btnImport.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
    }

    private fun updateHistoryEmpty() {
        b.tvHistoryEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        b.rvHistory.visibility      = if (sessions.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun exportTo(uri: Uri) {
        try {
            val json = store.exportJson(logEntries, b.etSessionNotes.text.toString(), sessions)
            val out = contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("No se pudo abrir el archivo")
            out.use { it.write(json.toByteArray()) }
            showMessage("Respaldo guardado")
        } catch (_: Exception) {
            showMessage("No se pudo guardar el respaldo")
        }
    }

    private fun confirmImport(uri: Uri) {
        val backup = try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("No se pudo abrir el archivo")
            store.parseBackup(input.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            showMessage("El archivo no es un respaldo válido")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Importar respaldo")
            .setMessage(
                "Se reemplazarán el log, las notas y el historial actuales por " +
                    "${backup.entries.size} ejercicio(s) y ${backup.sessions.size} sesión(es)."
            )
            .setNegativeButton("CANCELAR", null)
            .setPositiveButton("REEMPLAZAR") { _, _ -> applyBackup(backup) }
            .show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyBackup(backup: BackupData) {
        logEntries.clear()
        logEntries.addAll(backup.entries)
        logAdapter.notifyDataSetChanged()
        store.saveEntries(logEntries)

        sessions.clear()
        sessions.addAll(backup.sessions)
        historyAdapter.notifyDataSetChanged()
        store.saveSessions(sessions)
        updateHistoryEmpty()

        b.etSessionNotes.setText(backup.notes)
        showMessage("Respaldo importado")
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
        b.btnTabata.setOnClickListener {
            if (engine.phase == Phase.IDLE) {
                engine.startTabata(workMins, restMins, totalCycles)
                askBackgroundPermissions()
            } else {
                engine.stopTabata()
            }
        }
    }

    private fun updateConfigDisplay() {
        b.tvWorkVal.text   = workMins.toString()
        b.tvRestVal.text   = restMins.toString()
        b.tvCyclesVal.text = totalCycles.toString()
    }

    // ── Estado de la practica ────────────────────────────────────────────────

    private fun renderPractice() {
        val tabataOn = engine.phase != Phase.IDLE
        b.configSection.visibility  = if (tabataOn) View.GONE else View.VISIBLE
        b.runningSection.visibility = if (tabataOn) View.VISIBLE else View.GONE
        setTabataButton(stop = tabataOn)
        if (tabataOn) updateRunningDisplay()

        setMetroButton(stop = engine.metroRunning)
        // Al detener se conserva el BPM alcanzado; si cambio por accelerando, se ofrece volver.
        if (!engine.metroRunning && bpm != startBpm) {
            b.tvBpmReset.text = "↺ %.1f".format(startBpm)
            b.tvBpmReset.visibility = View.VISIBLE
        } else {
            b.tvBpmReset.visibility = View.GONE
        }
    }

    private fun updateRunningDisplay() {
        val phase     = engine.phase
        val remaining = engine.remaining
        b.tvCountdown.text = "%02d:%02d".format(remaining / 60, remaining % 60)
        b.tvCycle.text     = "Ciclo ${engine.currentCycle} / ${engine.totalCycles}"
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
        metro.bpm = bpm

        b.btnBpm5Minus.setOnClickListener  { setBpm(bpm - 5.0f) }
        b.btnBpm1Minus.setOnClickListener  { setBpm(bpm - 1.0f) }
        b.btnBpmD1Minus.setOnClickListener { setBpm(bpm - 0.1f) }
        b.btnBpm5Plus.setOnClickListener   { setBpm(bpm + 5.0f) }
        b.btnBpm1Plus.setOnClickListener   { setBpm(bpm + 1.0f) }
        b.btnBpmD1Plus.setOnClickListener  { setBpm(bpm + 0.1f) }
        b.btnMetro.setOnClickListener      { if (engine.metroRunning) stopMetro() else startMetro() }
        b.tvBpmReset.setOnClickListener    { setBpm(startBpm) }

        b.sliderVolume.value = volume
        metro.volume = volume / 100f
        b.sliderVolume.addOnChangeListener { _, value, _ -> metro.volume = value / 100f }

        b.sliderAccel.value = accel
        metro.accelerando = accel
        b.tvAccelVal.text = "%.1f".format(accel)
        b.sliderAccel.addOnChangeListener { _, value, _ ->
            val rounded = (value * 10).roundToInt() / 10.0f
            metro.accelerando = rounded
            b.tvAccelVal.text = "%.1f".format(rounded)
        }

        b.btn44.setOnClickListener { toggleFigura("4/4") }
        b.btn34.setOnClickListener { toggleFigura("3/4") }
        b.btn68.setOnClickListener { toggleFigura("6/8") }
        b.btn54.setOnClickListener { toggleFigura("5/4") }
        applyFigura()
    }

    private fun toggleFigura(sig: String) {
        selectedFigura = if (selectedFigura == sig) "" else sig
        applyFigura()
    }

    private fun applyFigura() {
        val figura = figuras[selectedFigura]
        metro.beatsPerBar = figura?.first ?: 4
        metro.accentBeats = figura?.second ?: emptySet()
        updateFiguraButtons()
    }

    private fun updateFiguraButtons() {
        val map = mapOf(
            "4/4" to b.btn44,
            "3/4" to b.btn34,
            "6/8" to b.btn68,
            "5/4" to b.btn54
        )
        map.forEach { (sig, btn) ->
            val active = sig == selectedFigura
            btn.backgroundTintList = ColorStateList.valueOf(
                if (active) getColor(R.color.work) else android.graphics.Color.TRANSPARENT
            )
            btn.setTextColor(
                if (active) getColor(R.color.text_primary) else getColor(R.color.text_secondary)
            )
        }
    }

    private fun setBpm(value: Float) {
        bpm = ((value * 10).roundToInt() / 10.0f).coerceIn(MIN_BPM, MAX_BPM)
        b.tvBpm.text = "%.1f".format(bpm)
        metro.bpm = bpm
        // Un ajuste manual pasa a ser el nuevo punto de partida.
        startBpm = bpm
        b.tvBpmReset.visibility = View.GONE
    }

    private fun startMetro() {
        startBpm = bpm
        engine.startMetro(bpm, b.sliderVolume.value / 100f, b.sliderAccel.value)
        askBackgroundPermissions()
    }

    private fun stopMetro() {
        engine.stopMetro()
        metro.bpm = bpm
    }

    private fun setMetroButton(stop: Boolean) {
        b.btnMetro.text = if (stop) "DETENER" else "INICIAR"
        b.btnMetro.backgroundTintList = ColorStateList.valueOf(
            getColor(if (stop) R.color.btn_stop else R.color.btn_start)
        )
    }

    // ── Segundo plano ────────────────────────────────────────────────────────

    // Primero la notificacion (lleva el boton Detener) y luego la bateria, para no
    // abrir los dos avisos a la vez.
    private fun askBackgroundPermissions() {
        val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needsNotifications) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askBatteryExemption()
        }
    }

    // Algunas marcas (Samsung, Xiaomi, Huawei...) cierran apps en segundo plano aun
    // con el servicio activo; quedar fuera del ahorro de bateria lo evita. Se pide una vez.
    private fun askBatteryExemption() {
        val prefs = getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        val power = getSystemService(PowerManager::class.java)
        if (prefs.getBoolean("askedBattery", false) || power.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("askedBattery", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle("Práctica sin cortes")
            .setMessage(
                "Para que el metrónomo y el Tabata no se detengan con la pantalla apagada, " +
                    "permite que MAEStudio funcione sin restricciones de batería."
            )
            .setNegativeButton("AHORA NO", null)
            .setPositiveButton("PERMITIR") { _, _ -> openBatterySettings() }
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                showMessage("Busca MAEStudio en Ajustes > Batería y quita las restricciones")
            }
        }
    }

    private companion object {
        const val SETTINGS = "maestudio_settings"
        const val MIN_BPM = 40.0f
        const val MAX_BPM = 240.0f
    }
}
