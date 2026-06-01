package com.mstudio

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.mstudio.databinding.ItemLogEntryBinding
import java.util.Collections
import kotlin.math.roundToInt

class LogAdapter(
    val items: MutableList<LogEntry>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<LogAdapter.VH>() {

    var dragHelper: ItemTouchHelper? = null

    inner class VH(val b: ItemLogEntryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemLogEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val b = holder.b

        b.etEjercicio.setOnFocusChangeListener(null)
        b.etBpm.setOnFocusChangeListener(null)
        b.etMeta.setOnFocusChangeListener(null)
        b.etNotas.setOnFocusChangeListener(null)

        b.etEjercicio.setText(entry.ejercicio)
        b.etBpm.setText(entry.bpm)
        b.etMeta.setText(entry.meta)
        b.etNotas.setText(entry.notas)
        b.tvPercent.text = calcPercent(entry.bpm, entry.meta)

        b.etEjercicio.setOnFocusChangeListener { _, has ->
            if (!has) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items[pos].ejercicio = b.etEjercicio.text.toString()
                    onChanged()
                }
            }
        }
        b.etBpm.setOnFocusChangeListener { _, has ->
            if (!has) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items[pos].bpm = b.etBpm.text.toString()
                    b.tvPercent.text = calcPercent(items[pos].bpm, items[pos].meta)
                    onChanged()
                }
            }
        }
        b.etMeta.setOnFocusChangeListener { _, has ->
            if (!has) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items[pos].meta = b.etMeta.text.toString()
                    b.tvPercent.text = calcPercent(items[pos].bpm, items[pos].meta)
                    onChanged()
                }
            }
        }
        b.etNotas.setOnFocusChangeListener { _, has ->
            if (!has) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items[pos].notas = b.etNotas.text.toString()
                    onChanged()
                }
            }
        }

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
        items.removeAt(pos)
        notifyItemRemoved(pos)
        onChanged()
    }

    private fun calcPercent(bpm: String, meta: String): String {
        val b = bpm.toFloatOrNull() ?: return "—"
        val m = meta.toFloatOrNull()?.takeIf { it > 0f } ?: return "—"
        return "${(b / m * 100).roundToInt()}%"
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
