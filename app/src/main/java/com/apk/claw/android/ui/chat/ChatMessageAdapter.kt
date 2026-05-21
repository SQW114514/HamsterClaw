package com.apk.claw.android.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.apk.claw.android.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatMessageAdapter(
    private val messages: MutableList<ChatMessage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_TOOL_LOG = 2
    }

    override fun getItemViewType(position: Int): Int = when (messages[position].role) {
        "user" -> TYPE_USER
        "tool_log" -> TYPE_TOOL_LOG
        else -> TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserViewHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            TYPE_TOOL_LOG -> ToolLogViewHolder(inflater.inflate(R.layout.item_chat_tool_log, parent, false))
            else -> AssistantViewHolder(inflater.inflate(R.layout.item_chat_assistant, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is UserViewHolder -> holder.bind(msg)
            is AssistantViewHolder -> holder.bind(msg)
            is ToolLogViewHolder -> holder.bind(msg)
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(msg: ChatMessage) {
        if (msg.role == "assistant" && msg.isStreaming && messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            if (messages[lastIdx].role == "assistant") {
                messages[lastIdx] = msg
                notifyItemChanged(lastIdx)
                return
            }
        }
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvContent: TextView = itemView.findViewById(R.id.tvChatContent)
        private val tvTime: TextView = itemView.findViewById(R.id.tvChatTime)
        fun bind(msg: ChatMessage) {
            tvContent.text = msg.content
            tvTime.text = formatTime(msg.timestamp)
        }
    }

    class AssistantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvContent: TextView = itemView.findViewById(R.id.tvChatContent)
        private val tvTime: TextView = itemView.findViewById(R.id.tvChatTime)
        fun bind(msg: ChatMessage) {
            tvContent.text = msg.content
            tvTime.text = formatTime(msg.timestamp)
        }
    }

    class ToolLogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvContent: TextView = itemView.findViewById(R.id.tvChatContent)
        fun bind(msg: ChatMessage) {
            tvContent.text = msg.content
        }
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
