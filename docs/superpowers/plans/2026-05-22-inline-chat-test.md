# Inline Chat Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an in-app chat interface for testing LLM API connectivity without configuring any messaging channel.

**Architecture:** New `ChatActivity` launched from home page. Reuses `DefaultAgentService.executeTask()` directly with a custom `AgentCallback` that renders messages to chat bubbles, bypassing `TaskOrchestrator` and the entire Channel system. No changes to core Agent, Tool, or Channel code.

**Tech Stack:** Kotlin, Android SDK, RecyclerView, ConstraintLayout

---

### Task 1: Add Strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: Add English strings**

After the `menu_llm_config` string or at the end of LLM section, add:
```xml
    <!-- Inline Chat Test -->
    <string name="home_test_chat">Test Chat</string>
    <string name="chat_title">API Test</string>
    <string name="chat_input_hint">Type a message to test the AI...</string>
    <string name="chat_send">Send</string>
    <string name="chat_llm_not_configured">Please configure LLM first in Settings</string>
    <string name="chat_task_running">Waiting for current task to finish...</string>
```

- [ ] **Step 2: Add Chinese strings**

```xml
    <!-- 内嵌对话测试 -->
    <string name="home_test_chat">测试对话</string>
    <string name="chat_title">API 测试</string>
    <string name="chat_input_hint">输入消息测试 AI...</string>
    <string name="chat_send">发送</string>
    <string name="chat_llm_not_configured">请先在设置中配置 LLM</string>
    <string name="chat_task_running">请等待当前任务完成...</string>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml
git commit -m "feat: add inline chat test string resources"
```

---

### Task 2: Add Chat Message Data Model

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/ui/chat/ChatMessage.kt`

- [ ] **Step 1: Create ChatMessage data class**

```kotlin
package com.apk.claw.android.ui.chat

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/apk/claw/android/ui/chat/ChatMessage.kt
git commit -m "feat: add ChatMessage data model"
```

---

### Task 3: Create ChatCallback

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/ui/chat/ChatCallback.kt`

- [ ] **Step 1: Write ChatCallback**

```kotlin
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
                val merged = existing.copy(
                    content = existing.content + content
                )
                latestAssistantMsg = merged
                onMessageUpdate(merged)
            } else {
                val msg = ChatMessage(
                    role = "assistant",
                    content = content,
                    isStreaming = true
                )
                latestAssistantMsg = msg
                onMessageUpdate(msg)
            }
        }
    }

    override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
        mainHandler.post {
            onMessageUpdate(ChatMessage(
                role = "tool_log",
                content = "⚙ $toolName($parameters)"
            ))
        }
    }

    override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
        mainHandler.post {
            val status = if (result.isSuccess) "✓" else "✗"
            val data = (if (result.isSuccess) result.data else result.error) ?: ""
            val summary = if (data.length > 80) data.take(80) + "..." else data
            onMessageUpdate(ChatMessage(
                role = "tool_log",
                content = "  $status $summary"
            ))
        }
    }

    override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
        mainHandler.post {
            latestAssistantMsg?.let {
                latestAssistantMsg = it.copy(isStreaming = false)
                onMessageUpdate(it)
            }
            onMessageUpdate(ChatMessage(
                role = "tool_log",
                content = "✅ Task completed ($totalTokens tokens)"
            ))
            latestAssistantMsg = null
        }
    }

    override fun onError(round: Int, error: Exception, totalTokens: Int) {
        mainHandler.post {
            latestAssistantMsg?.let {
                latestAssistantMsg = it.copy(isStreaming = false)
                onMessageUpdate(it)
            }
            onMessageUpdate(ChatMessage(
                role = "assistant",
                content = "❌ Error: ${error.message}"
            ))
            latestAssistantMsg = null
        }
    }

    override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
        mainHandler.post {
            onMessageUpdate(ChatMessage(
                role = "tool_log",
                content = "⚠ System dialog blocked, needs manual handling"
            ))
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/apk/claw/android/ui/chat/ChatCallback.kt
git commit -m "feat: create ChatCallback bridging AgentCallback to UI"
```

---

### Task 4: Create Chat Message Adapter

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/ui/chat/ChatMessageAdapter.kt`

- [ ] **Step 1: Write ChatMessageAdapter**

```kotlin
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
        // If it's a streaming update, replace last assistant message
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/apk/claw/android/ui/chat/ChatMessageAdapter.kt
git commit -m "feat: create ChatMessageAdapter with three view types"
```

---

### Task 5: Create Chat Item Layouts

**Files:**
- Create: `app/src/main/res/layout/item_chat_user.xml`
- Create: `app/src/main/res/layout/item_chat_assistant.xml`
- Create: `app/src/main/res/layout/item_chat_tool_log.xml`

- [ ] **Step 1: User bubble (right-aligned, brand color)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="end"
    android:paddingHorizontal="16pt"
    android:paddingVertical="4pt">

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="48pt"
        android:background="@drawable/bg_chat_user_bubble"
        android:orientation="vertical"
        android:paddingHorizontal="14pt"
        android:paddingVertical="10pt">

        <TextView
            android:id="@+id/tvChatContent"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/colorTextInverse"
            android:textSize="15pt"
            android:lineSpacingExtra="2pt" />

        <TextView
            android:id="@+id/tvChatTime"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4pt"
            android:textColor="@color/colorTextFixedWhiteSecondary"
            android:textSize="11pt" />

    </LinearLayout>

</LinearLayout>
```

- [ ] **Step 2: Assistant bubble (left-aligned, white)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="start"
    android:paddingHorizontal="16pt"
    android:paddingVertical="4pt">

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginEnd="48pt"
        android:background="@drawable/bg_chat_assistant_bubble"
        android:orientation="vertical"
        android:paddingHorizontal="14pt"
        android:paddingVertical="10pt">

        <TextView
            android:id="@+id/tvChatContent"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/colorTextPrimary"
            android:textSize="15pt"
            android:lineSpacingExtra="2pt" />

        <TextView
            android:id="@+id/tvChatTime"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4pt"
            android:textColor="@color/colorTextTertiary"
            android:textSize="11pt" />

    </LinearLayout>

</LinearLayout>
```

- [ ] **Step 3: Tool log (small, centered, gray)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center"
    android:paddingHorizontal="24pt"
    android:paddingVertical="2pt">

    <TextView
        android:id="@+id/tvChatContent"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:background="@drawable/bg_chat_tool_log"
        android:paddingHorizontal="10pt"
        android:paddingVertical="4pt"
        android:textColor="@color/colorTextTertiary"
        android:textSize="12pt" />

</LinearLayout>
```

- [ ] **Step 4: Create bubble drawables**

**`res/drawable/bg_chat_user_bubble.xml`:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/colorBrandPrimary" />
    <corners
        android:topLeftRadius="16pt"
        android:topRightRadius="16pt"
        android:bottomLeftRadius="16pt"
        android:bottomRightRadius="4pt" />
</shape>
```

**`res/drawable/bg_chat_assistant_bubble.xml`:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/colorContainerBrighten" />
    <corners
        android:topLeftRadius="4pt"
        android:topRightRadius="16pt"
        android:bottomLeftRadius="16pt"
        android:bottomRightRadius="16pt" />
</shape>
```

**`res/drawable/bg_chat_tool_log.xml`:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/colorFillTertiary" />
    <corners android:radius="8pt" />
</shape>
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/item_chat_user.xml app/src/main/res/layout/item_chat_assistant.xml app/src/main/res/layout/item_chat_tool_log.xml app/src/main/res/drawable/bg_chat_user_bubble.xml app/src/main/res/drawable/bg_chat_assistant_bubble.xml app/src/main/res/drawable/bg_chat_tool_log.xml
git commit -m "feat: add chat bubble layouts and drawables"
```

---

### Task 6: Create Chat Activity Layout

**Files:**
- Create: `app/src/main/res/layout/activity_chat.xml`

- [ ] **Step 1: Write the layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/colorBgSecondary">

    <!-- Toolbar -->
    <com.apk.claw.android.widget.CommonToolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="56pt"
        app:layout_constraintTop_toTopOf="parent" />

    <!-- Input Area -->
    <androidx.cardview.widget.CardView
        android:id="@+id/cardInput"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_margin="12pt"
        app:cardBackgroundColor="@color/colorContainerBrighten"
        app:cardCornerRadius="16pt"
        app:cardElevation="2pt"
        app:layout_constraintBottom_toBottomOf="parent">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingHorizontal="12pt"
            android:paddingVertical="8pt">

            <EditText
                android:id="@+id/etMessage"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:background="@null"
                android:hint="@string/chat_input_hint"
                android:imeOptions="actionSend"
                android:inputType="text"
                android:maxLines="4"
                android:minHeight="36pt"
                android:textColor="@color/colorTextPrimary"
                android:textColorHint="@color/colorTextTertiary"
                android:textSize="15pt" />

            <ImageButton
                android:id="@+id/btnSend"
                android:layout_width="40pt"
                android:layout_height="40pt"
                android:layout_marginStart="8pt"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/chat_send"
                android:src="@android:drawable/ic_menu_send"
                android:tint="@color/colorBrandPrimary" />

        </LinearLayout>

    </androidx.cardview.widget.CardView>

    <!-- Messages -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvMessages"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:clipToPadding="false"
        android:paddingTop="8pt"
        android:paddingBottom="8pt"
        app:layout_constraintBottom_toTopOf="@id/cardInput"
        app:layout_constraintTop_toBottomOf="@id/toolbar" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/layout/activity_chat.xml
git commit -m "feat: add chat activity layout"
```

---

### Task 7: Create ChatActivity

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/ui/chat/ChatActivity.kt`

- [ ] **Step 1: Write ChatActivity**

```kotlin
package com.apk.claw.android.ui.chat

import android.os.Bundle
import android.view.View
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

        // Auto-reset layout when keyboard opens
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

        // Add user message
        adapter.addMessage(ChatMessage(role = "user", content = text))
        rvMessages.smoothScrollToPosition(adapter.itemCount - 1)

        // Disable input while task runs
        setInputEnabled(false)

        // Execute Agent task with ChatCallback
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/apk/claw/android/ui/chat/ChatActivity.kt
git commit -m "feat: create ChatActivity for inline API testing"
```

---

### Task 8: Update Home Page with Test Chat Button

**Files:**
- Modify: `app/src/main/res/layout/activity_home.xml`
- Modify: `app/src/main/java/com/apk/claw/android/ui/home/HomeActivity.kt`

- [ ] **Step 1: Add button to layout**

After the `btnCancelTask` button (after line 83), add:
```xml
    <!-- 测试对话 -->
    <com.apk.claw.android.widget.KButton
        android:id="@+id/btnTestChat"
        android:layout_width="match_parent"
        android:layout_height="48pt"
        android:layout_marginTop="12pt"
        android:text="@string/home_test_chat"
        android:textSize="16sp"
        app:btnBackground="@color/colorBrandPrimary"
        app:btnTextColor="@color/colorBrandOnPrimary"
        app:layout_constraintTop_toBottomOf="@id/btnCancelTask" />
```

Also remove `android:paddingBottom` on the cardStorage so there's room for two buttons... Actually, the layout uses `ConstraintLayout` with top-to-bottom constraints, so this should just work naturally.

- [ ] **Step 2: Add click handler in HomeActivity**

In the `initViews()` method, after the `btnCancelTask` block, add:
```kotlin
        // 测试对话
        findViewById<KButton>(R.id.btnTestChat).setOnClickListener {
            if (KVUtils.hasLlmConfig()) {
                startActivity(Intent(this, ChatActivity::class.java))
            } else {
                Toast.makeText(this, R.string.chat_llm_not_configured, Toast.LENGTH_SHORT).show()
            }
        }
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/activity_home.xml app/src/main/java/com/apk/claw/android/ui/home/HomeActivity.kt
git commit -m "feat: add test chat button to home page"
```

---

### Task 9: Verify Build

- [ ] **Step 1: Build the debug APK**

```bash
cd D:\project\ApkClaw
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

If errors occur, fix them and repeat step 1.

- [ ] **Step 2: Commit any build fixes**

```bash
git add -A
git commit -m "fix: build errors from inline chat implementation"
```

---

### Task 10: Push to Fork

- [ ] **Step 1: Push all commits**

```bash
git push origin main
```

Expected: All commits pushed to `SQW114514/HamsterClaw`.
