package com.apk.claw.android.ui.chat

import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.agent.AgentServiceFactory
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.CommonToolbar

class ChatActivity : BaseActivity() {

    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var adapter: ChatMessageAdapter
    private val messages = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        if (!KVUtils.hasLlmConfig()) {
            Toast.makeText(this, R.string.chat_llm_not_configured, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initViews()
    }

    private fun initViews() {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.chat_title))
            showBackButton(true) { finish() }
        }

        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)

        adapter = ChatMessageAdapter(messages)
        rvMessages.adapter = adapter
        rvMessages.layoutManager = LinearLayoutManager(this)

        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        btnSend.setOnClickListener { sendMessage() }
        etMessage.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return

        etMessage.setText("")
        etMessage.clearFocus()

        adapter.addMessage(ChatMessage(role = "user", content = text))
        rvMessages.smoothScrollToPosition(adapter.itemCount - 1)

        setInputEnabled(false)

        val chatCallback = ChatCallback { msg ->
            adapter.addMessage(msg)
            rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
        }

        try {
            val agentConfig = ClawApplication.appViewModelInstance.getAgentConfig()
            val agentService = AgentServiceFactory.create()
            agentService.initialize(agentConfig)
            agentService.executeTask(text, object : com.apk.claw.android.agent.AgentCallback by chatCallback {
                override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                    chatCallback.onComplete(round, finalAnswer, totalTokens)
                    runOnUiThread { setInputEnabled(true) }
                }

                override fun onError(round: Int, error: Exception, totalTokens: Int) {
                    chatCallback.onError(round, error, totalTokens)
                    runOnUiThread { setInputEnabled(true) }
                }
            })
        } catch (e: Exception) {
            adapter.addMessage(ChatMessage(
                role = "assistant",
                content = "❌ Error: ${e.message}"
            ))
            rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
            setInputEnabled(true)
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        etMessage.isEnabled = enabled
        btnSend.isEnabled = enabled
    }
}
