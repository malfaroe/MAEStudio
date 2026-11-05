package com.maestudio

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.maestudio.databinding.ItemHistorySessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Sesiones guardadas, de la mas reciente a la mas antigua. */
class HistoryAdapter(
    val sessions: MutableList<PracticeSession>,
    private val onChanged: () -> Unit,
    private val onRemoved: (Int, PracticeSession) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale("es", "CL"))

    inner class VH(val b: ItemHistorySessionBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemHistorySessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val session = sessions[position]
        val older = sessions.subList(position + 1, sessions.size)
        val b = holder.b

        b.tvDate.text = dateFormat.format(Date(session.timestamp))
        b.tvBody.text = session.entries
            .filter { it.ejercicio.isNotBlank() || it.bpm.isNotBlank() }
            .joinToString("\n") { describe(it, older) }
        b.tvNotes.text = session.notes
        b.tvNotes.visibility = if (session.notes.isBlank()) View.GONE else View.VISIBLE
    }

    override fun getItemCount() = sessions.size

    // El avance de cada fila depende de sus vecinas: se redibuja la lista completa.
    @SuppressLint("NotifyDataSetChanged")
    fun removeItem(pos: Int) {
        val session = sessions.removeAt(pos)
        notifyDataSetChanged()
        onChanged()
        onRemoved(pos, session)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun restoreItem(pos: Int, session: PracticeSession) {
        sessions.add(pos.coerceAtMost(sessions.size), session)
        notifyDataSetChanged()
        onChanged()
    }

    private fun describe(entry: LogEntry, older: List<PracticeSession>): String {
        val name = entry.ejercicio.trim().ifBlank { "—" }
        val text = StringBuilder("$name: ${entry.bpm.ifBlank { "—" }}")
        if (entry.meta.isNotBlank()) {
            text.append(" / ${entry.meta} BPM (${percentText(entry.bpm, entry.meta)})")
        } else {
            text.append(" BPM")
        }
        progress(entry, name, older)?.let { text.append("  $it") }
        return text.toString()
    }

    private fun progress(entry: LogEntry, name: String, older: List<PracticeSession>): String? {
        val current = parseBpm(entry.bpm) ?: return null
        val previous = older.firstNotNullOfOrNull { session ->
            session.entries
                .firstOrNull { it.ejercicio.trim().equals(name, ignoreCase = true) }
                ?.let { parseBpm(it.bpm) }
        } ?: return null
        val delta = current - previous
        return when {
            abs(delta) < 0.05f -> "="
            delta > 0f -> "↑ +%.1f".format(Locale.US, delta)
            else -> "↓ %.1f".format(Locale.US, delta)
        }
    }
}

class HistorySwipeCallback(private val adapter: HistoryAdapter) :
    ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    override fun onMove(
        rv: RecyclerView,
        vh: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ) = false

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
        val pos = vh.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) adapter.removeItem(pos)
    }
}
