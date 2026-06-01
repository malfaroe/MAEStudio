package com.mstudio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.*

class MetronomePlayer {

    @Volatile var bpm    = 120.0f
    @Volatile var volume = 0.85f

    @Volatile private var running = false

    private val rate     = 44100
    private val clickBuf = buildClick()

    // 1131 Hz (400 Hz × 2^1.5 = +1.5 octaves), 150 ms, exp(-30) decay ≈ 50 ms audible.
    // Amplitude ×4 with hard clip → louder perceived volume (+400%), slight square-wave saturation.
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

        val chunk    = ShortArray(chunkSize)
        // Start phase at spb so the very first sample triggers the first beat immediately
        var phase    = rate * 60.0 / bpm
        var clickPos = -1

        while (running) {
            val spb = rate * 60.0 / bpm   // samples per beat — read once per chunk
            val vol = volume

            for (i in chunk.indices) {
                if (phase >= spb) {
                    phase -= spb
                    clickPos = 0            // new beat: restart click
                }
                chunk[i] = if (clickPos in 0 until clickBuf.size) {
                    (clickBuf[clickPos++] * vol).toInt().toShort()
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
