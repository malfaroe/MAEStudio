package com.maestudio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import kotlin.math.*

class MetronomePlayer {

    @Volatile var bpm         = 120.0f
    @Volatile var volume      = 0.85f
    @Volatile var accelerando = 0.0f
    @Volatile var beatsPerBar = 4
    @Volatile var accentBeats: Set<Int> = emptySet()   // beat indices (0-based) that get accent click

    var onBpmChanged: ((Float) -> Unit)? = null

    @Volatile private var running = false

    // Cada start() abre una sesion nueva. Un hilo anterior que siga escribiendo
    // audio ve que la suya ya no es la vigente y termina, aunque running vuelva
    // a true por un doble toque rapido.
    @Volatile private var session = 0

    private val rate       = 44100
    private val clickBuf    = buildClick()
    private val accentBuf   = buildAccent()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Normal beat: 1131 Hz, fast decay.
    private fun buildClick(): ShortArray {
        val n      = rate * 150 / 1000
        val buf    = ShortArray(n)
        val attack = (rate * 0.002).toInt()
        val cap    = Short.MAX_VALUE.toDouble()
        for (i in 0 until n) {
            val env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-30.0 * i / n)
            val raw = env * sin(2.0 * PI * 1131.0 * i / rate) * cap * 4.0
            buf[i]  = raw.coerceIn(-cap, cap).toInt().toShort()
        }
        return buf
    }

    // Accent beat: 1800 Hz (higher pitch = clearly distinguishable downbeat).
    private fun buildAccent(): ShortArray {
        val n      = rate * 150 / 1000
        val buf    = ShortArray(n)
        val attack = (rate * 0.002).toInt()
        val cap    = Short.MAX_VALUE.toDouble()
        for (i in 0 until n) {
            val env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-25.0 * i / n)
            val raw = env * sin(2.0 * PI * 1800.0 * i / rate) * cap * 4.0
            buf[i]  = raw.coerceIn(-cap, cap).toInt().toShort()
        }
        return buf
    }

    fun start() {
        if (running) return
        running = true
        val id = session + 1
        session = id
        Thread({ audioLoop(id) }, "metro-audio").start()
    }

    fun stop() {
        running = false
        session += 1
    }

    private fun openTrack(bufferBytes: Int): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    } catch (_: Exception) {
        null
    }

    private fun audioLoop(id: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val minBuf    = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunkSize = maxOf(minBuf, 512)
        // Unos 0,4 s de audio en cola: cubren las pausas de CPU con la pantalla apagada.
        val bufferBytes = maxOf(chunkSize * 4, rate * 2 * 2 / 5)
        var track = openTrack(bufferBytes)

        val chunk       = ShortArray(chunkSize)
        var phase       = rate * 60.0 / bpm
        var clickPos    = -1
        var beatCount   = 0L
        var beatInBar   = 0
        var activeBuf   = clickBuf

        while (session == id) {
            val spb = rate * 60.0 / bpm
            val vol = volume

            for (i in chunk.indices) {
                if (phase >= spb) {
                    phase -= spb
                    // Choose accent or normal buffer for this beat
                    val accents = accentBeats
                    activeBuf = if (accents.isNotEmpty() && beatInBar in accents) accentBuf else clickBuf
                    clickPos = 0
                    beatCount++
                    val bpb = beatsPerBar.coerceAtLeast(1)
                    beatInBar = (beatInBar + 1) % bpb
                    if (accelerando > 0f && beatCount % bpb == 0L) {
                        val newBpm = (bpm + accelerando).coerceAtMost(240f)
                        bpm = newBpm
                        mainHandler.post { onBpmChanged?.invoke(newBpm) }
                    }
                }
                chunk[i] = if (clickPos in 0 until activeBuf.size) {
                    (activeBuf[clickPos++] * vol).toInt().toShort()
                } else {
                    clickPos = -1
                    0
                }
                phase += 1.0
            }

            // Si el sistema de audio se reinicia o cambia la salida, la pista muere
            // sin aviso: se abre una nueva para que el click no quede mudo.
            val current = track ?: openTrack(bufferBytes).also { track = it }
            if (current == null) {
                Thread.sleep(100)
            } else if (current.write(chunk, 0, chunkSize) < 0) {
                current.release()
                track = null
            }
        }

        // pause + flush corta al instante; stop dejaria sonar lo que queda en cola.
        track?.run {
            pause()
            flush()
            release()
        }
    }
}
