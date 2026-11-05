package com.maestudio

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.maestudio.databinding.ItemLogEntryBinding
import java.util.Collections

class LogAdapter(
    val items: MutableList<LogEntry>,
    private val onChanged: () -> Unit,
    private val onRemoved: (Int, LogEntry) -> Unit,
    private val onUseBpm: (LogEntry) -> Unit,
    private val onRecordBpm: (Int) -> Unit
) : RecyclerView.Adapter<LogAdapter.VH>() {

    var dragHelper: ItemTouchHelper? = null

    inner class VH(val b: ItemLogEntryBinding) : RecyclerView.ViewHolder(b.root) {
        // Evita tratar como edicion del usuario el texto que se carga al reciclar la fila.
        var loading = false

        init {
            watch(b.etEjercicio) { entry, text -> entry.ejercicio = text }
            watch(b.etBpm) { entry, text -> entry.bpm = text }
            watch(b.etMeta) { entry, text -> entry.meta = text }
            watch(b.etNotas) { entry, text -> entry.notas = text }

            b.tvUseBpm.setOnClickListener {
                items.getOrNull(bindingAdapterPosition)?.let(onUseBpm)
            }
            b.tvRecordBpm.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRecordBpm(pos)
            }
        }

        private fun watch(field: EditText, update: (LogEntry, String) -> Unit) {
            field.doAfterTextChanged { text ->
                val entry = items.getOrNull(bindingAdapterPosition)
                if (!loading && entry != null) {
                    update(entry, text?.toString() ?: "")
                    b.tvPercent.text = percentText(entry.bpm, entry.meta)
                    onChanged()
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemLogEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val b = holder.b

        holder.loading = true
        b.etEjercicio.setText(entry.ejercicio)
        b.etBpm.setText(entry.bpm)
        b.etMeta.setText(entry.meta)
        b.etNotas.setText(entry.notas)
        holder.loading = false
        b.tvPercent.text = percentText(entry.bpm, entry.meta)

        b.ivDrag.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                dragHelper?.startDrag(holder)
            }
            false
        }
    }

    override fun getItemCount() = items.size

    fun onItemMove(from: Int, to: Int) {
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
        onChanged()
    }

    fun removeItem(pos: Int) {
        val entry = items.removeAt(pos)
        notifyItemRemoved(pos)
        onChanged()
        onRemoved(pos, entry)
    }

    fun restoreItem(pos: Int, entry: LogEntry) {
        val target = pos.coerceAtMost(items.size)
        items.add(target, entry)
        notifyItemInserted(target)
        onChanged()
    }
}

class LogDragCallback(private val adapter: LogAdapter) : ItemTouchHelper.Callback() {
    override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
        makeMovementFlags(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        )

    override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
        adapter.onItemMove(vh.bindingAdapterPosition, target.bindingAdapterPosition)
        return true
    }

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
        val pos = vh.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) adapter.removeItem(pos)
    }

    override fun isLongPressDragEnabled() = false
}
