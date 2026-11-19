package br.com.blackview.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import br.com.blackview.tv.data.RecordingFile
import br.com.blackview.tv.databinding.ItemRecordingBinding

class RecordingAdapter(
    private var items: List<RecordingFile>,
    private val onItemClick: (RecordingFile) -> Unit,
    private val onItemLongClick: (RecordingFile) -> Unit
) : RecyclerView.Adapter<RecordingAdapter.ViewHolder>() {

    fun updateData(newItems: List<RecordingFile>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemRecordingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecordingFile) {
            binding.tvItemCameraBadge.text = item.cameraName.uppercase()
            binding.tvItemDate.text = item.formattedRange
            val live = if (item.isActive) "● GRAVANDO  •  " else ""
            binding.tvItemDetails.text =
                "${live}Duração: ${item.formattedDuration}  •  ${item.formattedSize}  •  ${item.storageLabel}"

            binding.root.setOnClickListener {
                onItemClick(item)
            }

            binding.root.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }
}
