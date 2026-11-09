package com.maestudio

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Metronomo y Tabata a nivel de proceso. Viven fuera de MainActivity para que la
 * practica siga aunque Android destruya la pantalla o se cierre desde Recientes;
 * PracticeService mantiene vivo el proceso mientras haya algo activo.
 */
object PracticeEngine {

    enum class Phase { IDLE, WORK, REST }

    interface Listener {
        fun onPracticeChanged()
        fun onBpmChanged(bpm: Float)
    }

    var listener: Listener? = null

    val metro = MetronomePlayer()
    var metroRunning = false
        private set

    var phase = Phase.IDLE
        private set
    var currentCycle = 0
        private set
    var totalCycles = 0
        private set
    var phaseDurationSec = 0
        private set
    var phaseStart = 0L
        private set
    var workMins = 0
        private set
    var restMins = 0
        private set

    val active: Boolean
        get() = metroRunning || phase != Phase.IDLE

    private val remainingMs: Long
        get() = phaseDurationSec * 1000L - (SystemClock.elapsedRealtime() - phaseStart)

    /** Segundos que faltan, redondeados hacia arriba: la fase parte mostrando 05:00. */
    val remaining: Int
        get() = ((remainingMs.coerceAtLeast(0) + 999) / 1000).toInt()

    private lateinit var app: Context
    private val sound = SoundPlayer()
    private val handler = Handler(Looper.getMainLooper())
    private var serviceOn = false

    private val ticker = object : Runnable {
        override fun run() {
            val left = remainingMs
            if (left <= 0) {
                advancePhase()
                return
            }
            listener?.onPracticeChanged()
            // Se agenda justo al final de la fase para cambiarla sin retraso.
            handler.postDelayed(this, minOf(TICK_MS, left))
        }
    }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        metro.onBpmChanged = { bpm -> listener?.onBpmChanged(bpm) }
    }

    // ── Metronomo ────────────────────────────────────────────────────────────

    fun startMetro(bpm: Float, volume: Float, accelerando: Float) {
        metro.bpm = bpm
        metro.volume = volume
        metro.accelerando = accelerando
        metro.start()
        metroRunning = true
        changed()
    }

    fun stopMetro() {
        if (!metroRunning) return
        metro.stop()
        metroRunning = false
        // El BPM alcanzado se guarda aqui: si se detuvo desde la notificacion con la
        // app cerrada, la pantalla lo leera al abrirse en vez de volver a uno anterior.
        prefs().edit()
            .putFloat("bpm", metro.bpm)
            .apply()
        changed()
    }

    // ── Tabata ───────────────────────────────────────────────────────────────

    fun startTabata(work: Int, rest: Int, cycles: Int) {
        workMins = work
        restMins = rest
        totalCycles = cycles
        currentCycle = 1
        sound.playWork()
        beginPhase(Phase.WORK, workMins * 60, SystemClock.elapsedRealtime())
    }

    /**
     * Retoma un Tabata que quedo a medias porque Android cerro el proceso. Avanza
     * las fases que se cumplieron mientras la app estuvo cerrada, asi el contador
     * sigue donde deberia ir y no vuelve a empezar.
     */
    fun restoreTabata() {
        if (phase != Phase.IDLE) return
        val prefs = prefs()
        val saved = Phase.values().getOrNull(prefs.getInt(K_PHASE, -1))
        if (saved == null || saved == Phase.IDLE) return

        workMins = prefs.getInt(K_WORK, 5)
        restMins = prefs.getInt(K_REST, 2)
        totalCycles = prefs.getInt(K_CYCLES, 1)
        currentCycle = prefs.getInt(K_CYCLE, 1)
        var p: Phase = saved
        var duration = prefs.getInt(K_DURATION, 0)
        // Se guarda la hora del reloj porque elapsedRealtime vuelve a cero al reiniciar el telefono.
        val now = SystemClock.elapsedRealtime()
        var start = now - (System.currentTimeMillis() - prefs.getLong(K_START_WALL, 0L))

        while (p != Phase.IDLE && now - start >= duration * 1000L) {
            start += duration * 1000L
            when {
                p == Phase.WORK -> { p = Phase.REST; duration = restMins * 60 }
                currentCycle >= totalCycles -> p = Phase.IDLE
                else -> { currentCycle++; p = Phase.WORK; duration = workMins * 60 }
            }
        }
        if (p == Phase.IDLE) clearTabata() else beginPhase(p, duration, start)
    }

    fun stopTabata() {
        if (phase == Phase.IDLE) return
        handler.removeCallbacks(ticker)
        phase = Phase.IDLE
        clearTabata()
        changed()
    }

    fun stopAll() {
        stopMetro()
        stopTabata()
    }

    private fun beginPhase(p: Phase, durationSec: Int, start: Long) {
        phase = p
        phaseDurationSec = durationSec
        phaseStart = start
        saveTabata()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        changed()
    }

    private fun prefs() = app.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    private fun saveTabata() {
        val startWall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - phaseStart)
        prefs().edit()
            .putInt(K_PHASE, phase.ordinal)
            .putInt(K_WORK, workMins)
            .putInt(K_REST, restMins)
            .putInt(K_CYCLES, totalCycles)
            .putInt(K_CYCLE, currentCycle)
            .putInt(K_DURATION, phaseDurationSec)
            .putLong(K_START_WALL, startWall)
            .apply()
    }

    private fun clearTabata() {
        prefs().edit().remove(K_PHASE).apply()
    }

    private fun advancePhase() {
        when (phase) {
            Phase.WORK -> {
                sound.playRest()
                beginPhase(Phase.REST, restMins * 60, SystemClock.elapsedRealtime())
            }
            Phase.REST -> {
                if (currentCycle >= totalCycles) {
                    sound.playComplete()
                    stopTabata()
                } else {
                    currentCycle++
                    sound.playWork()
                    beginPhase(Phase.WORK, workMins * 60, SystemClock.elapsedRealtime())
                }
            }
            Phase.IDLE -> {}
        }
    }

    // ── Servicio ─────────────────────────────────────────────────────────────

    private fun changed() {
        updateService()
        listener?.onPracticeChanged()
    }

    private fun updateService() {
        if (active == serviceOn) return
        serviceOn = active
        val intent = Intent(app, PracticeService::class.java)
        if (serviceOn) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
    }

    const val SETTINGS = "maestudio_settings"
    private const val TICK_MS = 500L
    private const val K_PHASE = "tabataPhase"
    private const val K_WORK = "tabataWork"
    private const val K_REST = "tabataRest"
    private const val K_CYCLES = "tabataCycles"
    private const val K_CYCLE = "tabataCycle"
    private const val K_DURATION = "tabataDuration"
    private const val K_START_WALL = "tabataStartWall"
}
