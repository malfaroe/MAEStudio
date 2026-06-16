package com.maestudio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.*

class MetronomePlayer {

    @Volatile var bpm         = 120.0f
    @Volatile var volume      = 0.85f
    @Volatile var accelerando = 0.0f
    @Volatile var beatsPerBar = 4
    @Volatile var accentBeats: Set<Int> = emptySet()   // beat indices (0-based) that get accent click

    var onBpmChanged: ((Float) -> Unit)? = null

    @Volatile private var running = false

    private val rate        = 44100
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
        Thread(::audioLoop, "metro-audio").apply { priority = Thread.MAX_PRIORITY }.start()
    }

    fun stop() {
        running = false
    }

    private fun audioLoop() {
        val minBuf    = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunkSize = maxOf(minBuf, 512)

        val track = AudioTrack.Builder()
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
            .setBufferSizeInBytes(chunkSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.play()

        val chunk       = ShortArray(chunkSize)
        var phase       = rate * 60.0 / bpm
        var clickPos    = -1
        var beatCount   = 0L
        var beatInBar   = 0
        var activeBuf   = clickBuf

        while (running) {
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

            track.write(chunk, 0, chunkSize)
        }

        track.stop()
        track.release()
    }
}
