package com.vector.assistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vector.assistant.databinding.ItemChatMessageBinding
import com.vector.assistant.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.ChatViewHolder>(DiffCallback) {

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChatViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChatMessage) {
            if (item.isUser) {
                binding.layoutUserBubble.visibility = View.VISIBLE
                binding.layoutVectorBubble.visibility = View.GONE
                binding.tvUserText.text = item.text
                val source = if (item.isVoice) "Voice" else "Typed"
                val timeStr = synchronized(timeFmt) { timeFmt.format(Date(item.timestamp)) }
                binding.tvUserMeta.text = "$source • $timeStr"
            } else {
                binding.layoutUserBubble.visibility = View.GONE
                binding.layoutVectorBubble.visibility = View.VISIBLE
                binding.tvVectorText.text = item.text
                if (!item.badge.isNullOrBlank()) {
                    binding.tvVectorBadge.visibility = View.VISIBLE
                    binding.tvVectorBadge.text = item.badge
                } else {
                    binding.tvVectorBadge.visibility = View.GONE
                }
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem == newItem
    }
}

