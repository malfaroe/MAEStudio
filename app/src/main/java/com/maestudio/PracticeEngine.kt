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
        app.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
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

    /** Retoma un Tabata guardado por la pantalla cuando Android cerro el proceso. */
    fun resumeTabata(p: Phase, work: Int, rest: Int, cycles: Int, cycle: Int, durationSec: Int, start: Long) {
        if (p == Phase.IDLE || phase != Phase.IDLE) return
        workMins = work
        restMins = rest
        totalCycles = cycles
        currentCycle = cycle
        beginPhase(p, durationSec, start)
    }

    fun stopTabata() {
        if (phase == Phase.IDLE) return
        handler.removeCallbacks(ticker)
        phase = Phase.IDLE
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
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        changed()
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
}
