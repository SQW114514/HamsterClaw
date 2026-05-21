package com.apk.claw.android.ui.chat

import android.os.Handler
import android.os.Looper
import com.apk.claw.android.agent.AgentCallback
import com.apk.claw.android.tool.ToolResult

class ChatCallback(
    private val onMessageUpdate: (ChatMessage) -> Unit
) : AgentCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var latestAssistantMsg: ChatMessage? = null

    override fun onLoopStart(round: Int) {}

    override fun onContent(round: Int, content: String) {
        mainHandler.post {
            val existing = latestAssistantMsg
            if (existing != null && existing.isStreaming) {
                val merged = existing.copy(content = existing.content + content)
                latestAssistantMsg = merged
                onMessageUpdate(merged)
            } else {
                val msg = ChatMessage(role = "assistant", content = content, isStreaming = true)
                latestAssistantMsg = msg
                onMessageUpdate(msg)
            }
        }
    }

    override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
        mainHandler.post {
            onMessageUpdate(ChatMessage(role = "tool_log", content = "⚙ $toolName($parameters)"))
        }
    }

    override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
        mainHandler.post {
            val status = if (result.isSuccess) "✓" else "✗"
            val data = (if (result.isSuccess) result.data else result.error) ?: ""
            val summary = if (data.length > 80) data.take(80) + "..." else data
            onMessageUpdate(ChatMessage(role = "tool_log", content = "  $status $summary"))
        }
    }

    override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
        mainHandler.post {
            latestAssistantMsg?.let {
                latestAssistantMsg = it.copy(isStreaming = false)
                onMessageUpdate(it)
            }
            onMessageUpdate(ChatMessage(role = "tool_log", content = "✅ Task completed ($totalTokens tokens)"))
            latestAssistantMsg = null
        }
    }

    override fun onError(round: Int, error: Exception, totalTokens: Int) {
        mainHandler.post {
            latestAssistantMsg?.let {
                latestAssistantMsg = it.copy(isStreaming = false)
                onMessageUpdate(it)
            }
            onMessageUpdate(ChatMessage(role = "assistant", content = "❌ Error: ${error.message}"))
            latestAssistantMsg = null
        }
    }

    override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
        mainHandler.post {
            onMessageUpdate(ChatMessage(role = "tool_log", content = "⚠ System dialog blocked, needs manual handling"))
        }
    }
}
