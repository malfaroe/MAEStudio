package com.maestudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BackupData(
    val entries: MutableList<LogEntry>,
    val notes: String,
    val sessions: MutableList<PracticeSession>
)

/** Guarda el log, las notas y el historial, y arma o lee los respaldos. */
class PracticeStore(context: Context) {

    private val prefs = context.getSharedPreferences("maestudio_log", Context.MODE_PRIVATE)

    fun loadEntries(): MutableList<LogEntry> = try {
        entriesFromJson(JSONArray(prefs.getString("entries", "[]") ?: "[]"))
    } catch (_: Exception) {
        mutableListOf()
    }

    fun saveEntries(entries: List<LogEntry>) {
        prefs.edit().putString("entries", entriesToJson(entries).toString()).apply()
    }

    fun loadNotes(): String = prefs.getString("session_notes", "") ?: ""

    fun saveNotes(text: String) {
        prefs.edit().putString("session_notes", text).apply()
    }

    fun loadSessions(): MutableList<PracticeSession> = try {
        sessionsFromJson(JSONArray(prefs.getString("sessions", "[]") ?: "[]"))
    } catch (_: Exception) {
        mutableListOf()
    }

    fun saveSessions(sessions: List<PracticeSession>) {
        prefs.edit().putString("sessions", sessionsToJson(sessions).toString()).apply()
    }

    fun exportJson(entries: List<LogEntry>, notes: String, sessions: List<PracticeSession>): String =
        JSONObject().apply {
            put("app", BACKUP_APP)
            put("version", 1)
            put("entries", entriesToJson(entries))
            put("notes", notes)
            put("sessions", sessionsToJson(sessions))
        }.toString(2)

    /** Lanza una excepcion si el texto no es un respaldo valido de MAEStudio. */
    fun parseBackup(text: String): BackupData {
        val root = JSONObject(text)
        require(root.optString("app") == BACKUP_APP) { "No es un respaldo de MAEStudio" }
        return BackupData(
            entries = entriesFromJson(root.getJSONArray("entries")),
            notes = root.optString("notes"),
            sessions = sessionsFromJson(root.getJSONArray("sessions"))
        )
    }

    private fun entriesToJson(entries: List<LogEntry>) = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject().apply {
                put("e", e.ejercicio)
                put("b", e.bpm)
                put("m", e.meta)
                put("n", e.notas)
            })
        }
    }

    private fun entriesFromJson(arr: JSONArray): MutableList<LogEntry> =
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            LogEntry(o.optString("e"), o.optString("b"), o.optString("m"), o.optString("n"))
        }.toMutableList()

    private fun sessionsToJson(sessions: List<PracticeSession>) = JSONArray().apply {
        sessions.forEach { s ->
            put(JSONObject().apply {
                put("t", s.timestamp)
                put("n", s.notes)
                put("entries", entriesToJson(s.entries))
            })
        }
    }

    private fun sessionsFromJson(arr: JSONArray): MutableList<PracticeSession> =
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PracticeSession(o.getLong("t"), o.optString("n"), entriesFromJson(o.getJSONArray("entries")))
        }.toMutableList()

    private companion object {
        const val BACKUP_APP = "MAEStudio"
    }
}
