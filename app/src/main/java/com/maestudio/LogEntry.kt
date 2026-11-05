package com.maestudio

import kotlin.math.roundToInt

data class LogEntry(
    var ejercicio: String = "",
    var bpm: String = "",
    var meta: String = "",
    var notas: String = ""
)

data class PracticeSession(
    val timestamp: Long,
    val notes: String,
    val entries: List<LogEntry>
)

fun parseBpm(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()

fun percentText(bpm: String, meta: String): String {
    val b = parseBpm(bpm) ?: return "—"
    val m = parseBpm(meta)?.takeIf { it > 0f } ?: return "—"
    return "${(b / m * 100).roundToInt()}%"
}
