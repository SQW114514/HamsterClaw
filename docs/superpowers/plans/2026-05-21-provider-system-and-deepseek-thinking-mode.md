# Provider System & DeepSeek Thinking Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `OPENGODE_GO` and `DEEPSEEK` as selectable LLM providers, create `DeepSeekLlmClient` with reasoning_content (thinking mode) support, and add a provider selector + thinking mode toggle to the settings UI and LAN config page.

**Architecture:** The OpenCode Go and DeepSeek APIs both use OpenAI-compatible chat format. The existing `OpenAiLlmClient` cannot handle DeepSeek's proprietary `reasoning_content` field (required for thinking mode). A new `DeepSeekLlmClient` bypasses LangChain4j's ChatModel layer and makes direct HTTP calls via OkHttp, manually preserving `reasoning_content` across conversation turns. The provider list grows from 2 (`OPENAI`, `ANTHROPIC`) to 4 (`OPENAI`, `ANTHROPIC`, `OPENGODE_GO`, `DEEPSEEK`).

**Tech Stack:** Kotlin, Java, LangChain4j, OkHttp, MMKV, Android SDK, NanoHTTPD

---

### Task 1: Extend `LlmProvider` Enum

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/agent/AgentConfig.kt:3`

- [ ] **Step 1: Add new enum values**

```
enum class LlmProvider { OPENAI, ANTHROPIC, OPENGODE_GO, DEEPSEEK }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/apk/claw/android/agent/AgentConfig.kt
git commit -m "feat: add OPENGODE_GO and DEEPSEEK provider enum values"
```

---

### Task 2: Add `thinkingMode` to `AgentConfig` + Default Base URLs Per Provider

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/agent/AgentConfig.kt`

- [ ] **Step 1: Add `thinkingMode` field and default base URLs**

In the `AgentConfig` data class, add:
```kotlin
data class AgentConfig(
    // ... existing fields ...
    val thinkingMode: Boolean = false,
) {
    companion object {
        fun defaultBaseUrl(provider: LlmProvider): String = when (provider) {
            LlmProvider.OPENAI -> "https://api.openai.com/v1"
            LlmProvider.ANTHROPIC -> "https://api.anthropic.com/v1"
            LlmProvider.OPENGODE_GO -> "https://opencode.ai/zen/go/v1"
            LlmProvider.DEEPSEEK -> "https://api.deepseek.com/v1"
        }
    }
}
```

In the `Builder` class, add:
```kotlin
class Builder {
    // ... existing fields ...
    private var thinkingMode: Boolean = false

    fun thinkingMode(thinkingMode: Boolean) = apply { this.thinkingMode = thinkingMode }

    fun build(): AgentConfig {
        // ... existing validation ...
        return AgentConfig(apiKey, baseUrl, modelName, systemPrompt, maxIterations, temperature, provider, streaming, thinkingMode)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/apk/claw/android/agent/AgentConfig.kt
git commit -m "feat: add thinkingMode and defaultBaseUrl to AgentConfig"
```

---

### Task 3: Add Provider + ThinkingMode Persistence in KVUtils

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/utils/KVUtils.kt:192-204`

- [ ] **Step 1: Add persistent keys and methods**

After the existing LLM key definitions (around line 194), add:
```kotlin
private const val KEY_LLM_PROVIDER = "KEY_LLM_PROVIDER"
private const val KEY_LLM_THINKING_MODE = "KEY_LLM_THINKING_MODE"

fun getLlmProvider(): String = getString(KEY_LLM_PROVIDER, "OPENAI")
fun setLlmProvider(value: String) = putString(KEY_LLM_PROVIDER, value)
fun isLlmThinkingMode(): Boolean = getBoolean(KEY_LLM_THINKING_MODE, false)
fun setLlmThinkingMode(enabled: Boolean) = putBoolean(KEY_LLM_THINKING_MODE, enabled)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/apk/claw/android/utils/KVUtils.kt
git commit -m "feat: add provider and thinkingMode persistence in KVUtils"
```

---

### Task 4: Create `DeepSeekLlmClient`

**Files:**
- Create: `app/src/main/java/com/apk/claw/android/agent/llm/DeepSeekLlmClient.kt`

- [ ] **Step 1: Write DeepSeekLlmClient**

```kotlin
package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.output.TokenUsage
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DeepSeekLlmClient(
    private val config: AgentConfig
) : LlmClient {

    private val gson = Gson()
    private val JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8")

    /** Cache: assistant message text -> reasoning_content, preserved across turns */
    private val reasoningCache = mutableMapOf<String, String>()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            XLog.d("DeepSeek", "--> ${request.method} ${request.url}")
            val response = chain.proceed(request)
            val body = response.body?.string() ?: ""
            XLog.d("DeepSeek", "<-- ${response.code} (${body.length} chars)")
            response.newBuilder().body(okhttp3.ResponseBody.create(
                body, response.body?.contentType()
            )).build()
        }
        .build()

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        val requestJson = buildRequestJson(messages, toolSpecs, streaming = false)
        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(requestJson, JSON_MEDIA_TYPE))
            .build()

        val response = okHttpClient.newCall(httpRequest).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw RuntimeException("API error ${response.code}: $responseBody")
        }

        return parseResponse(responseBody)
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse {
        val requestJson = buildRequestJson(messages, toolSpecs, streaming = true)
        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(RequestBody.create(requestJson, JSON_MEDIA_TYPE))
            .build()

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<LlmResponse>()
        val errorRef = AtomicReference<Throwable>()

        val textBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val toolCalls = mutableListOf<ToolExecutionRequest>()
        var currentToolName: String? = null
        var currentToolArgs = StringBuilder()

        val factory = EventSources.createFactory(okHttpClient)
        factory.newEventSource(httpRequest, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") return
                try {
                    val json = JsonParser.parseString(data).asJsonObject
                    if (!json.has("choices")) return
                    val choice = json.getAsJsonArray("choices")[0].asJsonObject
                    val delta = choice.getAsJsonObject("delta")

                    if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull) {
                        reasoningBuilder.append(delta.get("reasoning_content").asString)
                    }
                    if (delta.has("content") && !delta.get("content").isJsonNull) {
                        val content = delta.get("content").asString
                        textBuilder.append(content)
                        listener.onPartialText(content)
                    }
                    if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull) {
                        for (tc in delta.getAsJsonArray("tool_calls")) {
                            val tcObj = tc.asJsonObject
                            val func = tcObj.getAsJsonObject("function")
                            if (func.has("name") && !func.get("name").isJsonNull) {
                                currentToolName = func.get("name").asString
                                currentToolArgs = StringBuilder()
                            }
                            if (func.has("arguments") && !func.get("arguments").isJsonNull) {
                                currentToolArgs.append(func.get("arguments").asString)
                            }
                        }
                    }
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull
                        && choice.get("finish_reason").asString == "tool_calls" && currentToolName != null) {
                        toolCalls.add(ToolExecutionRequest.builder()
                            .id("call_${toolCalls.size}")
                            .name(currentToolName!!)
                            .arguments(currentToolArgs.toString())
                            .build())
                    }
                } catch (e: Exception) {
                    XLog.w("DeepSeek", "SSE parse error: ${e.message}")
                }
            }

            override fun onClosed(eventSource: EventSource) {
                val fullText = textBuilder.toString()
                reasoningBuilder.toString().let { rc ->
                    if (rc.isNotEmpty() && fullText.isNotEmpty()) reasoningCache[fullText] = rc
                }
                resultRef.set(LlmResponse(fullText.ifEmpty { null }, toolCalls, null))
                listener.onComplete(resultRef.get())
                latch.countDown()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                errorRef.set(t ?: RuntimeException("Stream failed: ${response?.code}"))
                listener.onError(errorRef.get())
                latch.countDown()
            }
        })

        latch.await()
        errorRef.get()?.let { throw it }
        return resultRef.get()
    }

    private fun buildRequestJson(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        streaming: Boolean
    ): String {
        val root = JsonObject()
        root.addProperty("model", config.modelName.ifEmpty { "deepseek-v4-flash" })
        if (config.thinkingMode) {
            root.add("thinking", JsonObject().apply { addProperty("type", "enabled") })
        }
        root.addProperty("stream", streaming)
        root.addProperty("temperature", config.temperature)

        val messagesArray = JsonArray()
        for (message in messages) messagesArray.add(convertMessage(message))
        root.add("messages", messagesArray)

        if (toolSpecs.isNotEmpty()) {
            val toolsArray = JsonArray()
            for (spec in toolSpecs) toolsArray.add(convertToolSpec(spec))
            root.add("tools", toolsArray)
        }
        return gson.toJson(root)
    }

    private fun convertMessage(message: ChatMessage): JsonObject = JsonObject().apply {
        when (message) {
            is SystemMessage -> {
                addProperty("role", "system")
                addProperty("content", message.text())
            }
            is UserMessage -> {
                addProperty("role", "user")
                addProperty("content", message.singleText())
            }
            is AiMessage -> {
                addProperty("role", "assistant")
                message.text()?.let { text ->
                    addProperty("content", text)
                    reasoningCache[text]?.let { addProperty("reasoning_content", it) }
                }
                message.toolExecutionRequests()?.let { tcs ->
                    val arr = JsonArray()
                    for ((i, tc) in tcs.withIndex()) {
                        arr.add(JsonObject().apply {
                            addProperty("id", tc.id() ?: "call_$i")
                            addProperty("type", "function")
                            add("function", JsonObject().apply {
                                addProperty("name", tc.name())
                                addProperty("arguments", tc.arguments())
                            })
                        })
                    }
                    add("tool_calls", arr)
                }
            }
            is ToolExecutionResultMessage -> {
                addProperty("role", "tool")
                addProperty("tool_call_id", message.id())
                addProperty("content", message.text())
            }
        }
    }

    private fun convertToolSpec(spec: ToolSpecification): JsonObject = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", spec.name())
            addProperty("description", spec.description() ?: "")
            spec.parameters()?.let { params ->
                val schema = params as? JsonObjectSchema ?: return@let
                val props = JsonObject()
                val required = JsonArray()
                schema.properties().forEach { (name, propSchema) ->
                    props.add(name, JsonObject().apply {
                        addProperty("type", when (propSchema) {
                            is JsonStringSchema -> "string"
                            is JsonIntegerSchema -> "integer"
                            is JsonNumberSchema -> "number"
                            is JsonBooleanSchema -> "boolean"
                            else -> "string"
                        })
                        val desc = (propSchema as? JsonStringSchema)?.description()
                            ?: (propSchema as? JsonIntegerSchema)?.description()
                            ?: (propSchema as? JsonNumberSchema)?.description()
                            ?: (propSchema as? JsonBooleanSchema)?.description()
                        if (desc != null) addProperty("description", desc)
                    })
                }
                schema.required().forEach { required.add(it) }
                add("parameters", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", props)
                    add("required", required)
                })
            }
        })
    }

    private fun parseResponse(responseBody: String): LlmResponse {
        val json = JsonParser.parseString(responseBody).asJsonObject
        val tokenUsage = if (json.has("usage")) {
            val u = json.getAsJsonObject("usage")
            TokenUsage(u.get("prompt_tokens").asInt, u.get("completion_tokens").asInt)
        } else null

        val choices = json.getAsJsonArray("choices")
        if (choices.size() == 0) return LlmResponse(null, emptyList(), tokenUsage)

        val message = choices[0].asJsonObject.getAsJsonObject("message")
        val text = if (message.has("content") && !message.get("content").isJsonNull)
            message.get("content").asString else null
        val reasoningContent = if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull)
            message.get("reasoning_content").asString else null

        if (reasoningContent != null && text != null) reasoningCache[text] = reasoningContent

        val toolExecutionRequests = if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull) {
            message.getAsJsonArray("tool_calls").map { tc ->
                val func = tc.asJsonObject.getAsJsonObject("function")
                ToolExecutionRequest.builder()
                    .id(tc.asJsonObject.get("id").asString)
                    .name(func.get("name").asString)
                    .arguments(func.get("arguments").asString)
                    .build()
            }
        } else emptyList()

        return LlmResponse(text ?: reasoningContent, toolExecutionRequests, tokenUsage)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/apk/claw/android/agent/llm/DeepSeekLlmClient.kt
git commit -m "feat: create DeepSeekLlmClient with reasoning_content thinking mode support"
```

---

### Task 5: Update `LlmClientFactory`

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/agent/llm/LlmClientFactory.kt`

- [ ] **Step 1: Add branches for new providers**

```kotlin
object LlmClientFactory {
    fun create(config: AgentConfig): LlmClient {
        val httpClientBuilder = OkHttpClientBuilderAdapter().apply {
            if (DefaultAgentService.FILE_LOGGING_ENABLED && DefaultAgentService.FILE_LOGGING_CACHE_DIR != null) {
                setFileLoggingEnabled(true, DefaultAgentService.FILE_LOGGING_CACHE_DIR)
            }
        }
        return when (config.provider) {
            LlmProvider.OPENAI -> OpenAiLlmClient(config, httpClientBuilder)
            LlmProvider.ANTHROPIC -> AnthropicLlmClient(config, httpClientBuilder)
            LlmProvider.OPENGODE_GO -> DeepSeekLlmClient(config)
            LlmProvider.DEEPSEEK -> DeepSeekLlmClient(config)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/apk/claw/android/agent/llm/LlmClientFactory.kt
git commit -m "feat: add OPENGODE_GO and DEEPSEEK to LlmClientFactory"
```

---

### Task 6: Update `AppViewModel.getAgentConfig()` to Include Provider and ThinkingMode

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/AppViewModel.kt:53-63`

- [ ] **Step 1: Add provider + thinkingMode to config builder**

```kotlin
fun getAgentConfig(): AgentConfig {
    val providerStr = KVUtils.getLlmProvider()
    val provider = try { LlmProvider.valueOf(providerStr) } catch (e: Exception) { LlmProvider.OPENAI }
    var baseUrl = KVUtils.getLlmBaseUrl().trim()
    if (baseUrl.isEmpty()) {
        baseUrl = AgentConfig.defaultBaseUrl(provider)
    }
    return AgentConfig.Builder()
        .apiKey(KVUtils.getLlmApiKey())
        .baseUrl(baseUrl)
        .modelName(KVUtils.getLlmModelName())
        .temperature(0.1)
        .maxIterations(60)
        .provider(provider)
        .thinkingMode(KVUtils.isLlmThinkingMode())
        .build()
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/apk/claw/android/AppViewModel.kt
git commit -m "feat: build AgentConfig with provider and thinkingMode from KVUtils"
```

---

### Task 7: Add String Resources for Provider Selector and Thinking Toggle

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (around line 110-121)
- Modify: `app/src/main/res/values-zh/strings.xml` (around line 110-121)

- [ ] **Step 1: Add English strings**

After the existing LLM config strings (after line 121), add:
```xml
    <string name="llm_config_provider">Provider</string>
    <string name="llm_config_provider_openai">OpenAI</string>
    <string name="llm_config_provider_anthropic">Anthropic</string>
    <string name="llm_config_provider_opencode_go">OpenCode Go</string>
    <string name="llm_config_provider_deepseek">DeepSeek</string>
    <string name="llm_config_thinking_mode">Thinking Mode</string>
    <string name="llm_config_thinking_mode_hint">Enable DeepSeek reasoning_content (thinking mode)</string>
```

- [ ] **Step 2: Add Chinese strings**

After the existing LLM config strings in `values-zh/strings.xml`, add:
```xml
    <string name="llm_config_provider">提供商</string>
    <string name="llm_config_provider_openai">OpenAI</string>
    <string name="llm_config_provider_anthropic">Anthropic</string>
    <string name="llm_config_provider_opencode_go">OpenCode Go</string>
    <string name="llm_config_provider_deepseek">DeepSeek</string>
    <string name="llm_config_thinking_mode">思考模式</string>
    <string name="llm_config_thinking_mode_hint">开启 DeepSeek 的 reasoning_content 思考链</string>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml
git commit -m "feat: add provider and thinking mode string resources"
```

---

### Task 8: Update LLM Config Layout (`activity_llm_config.xml`)

**Files:**
- Modify: `app/src/main/res/layout/activity_llm_config.xml`

- [ ] **Step 1: Add Provider Spinner and Thinking Mode Switch before existing fields**

After the tip TextView (line 34) and before the API Key section (line 36), add:
```xml
            <!-- Provider Selector -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:paddingHorizontal="4pt"
                android:paddingBottom="8pt"
                android:text="@string/llm_config_provider"
                android:textColor="@color/colorTextPrimary"
                android:textSize="14pt"
                android:textStyle="bold" />

            <androidx.cardview.widget.CardView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                app:cardBackgroundColor="@color/colorContainerBrighten"
                app:cardCornerRadius="12pt"
                app:cardElevation="1pt">

                <Spinner
                    android:id="@+id/spProvider"
                    android:layout_width="match_parent"
                    android:layout_height="48pt"
                    android:paddingHorizontal="16pt"
                    android:spinnerMode="dropdown" />

            </androidx.cardview.widget.CardView>

            <!-- Thinking Mode Switch (only visible for DeepSeek/OpenCode Go) -->
            <LinearLayout
                android:id="@+id/layoutThinkingMode"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="20pt"
                android:orientation="horizontal"
                android:paddingHorizontal="4pt"
                android:visibility="gone">

                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:orientation="vertical">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/llm_config_thinking_mode"
                        android:textColor="@color/colorTextPrimary"
                        android:textSize="14pt"
                        android:textStyle="bold" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/llm_config_thinking_mode_hint"
                        android:textColor="@color/colorTextTertiary"
                        android:textSize="12pt" />

                </LinearLayout>

                <androidx.appcompat.widget.SwitchCompat
                    android:id="@+id/swThinkingMode"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:checked="false" />

            </LinearLayout>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/layout/activity_llm_config.xml
git commit -m "feat: add provider Spinner and thinking mode Switch to LLM config layout"
```

---

### Task 9: Update `LlmConfigActivity` with Provider Selection + Thinking Toggle Logic

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/ui/settings/LlmConfigActivity.kt`

- [ ] **Step 1: Rewrite LlmConfigActivity with provider + thinking toggle**

```kotlin
package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.LlmProvider
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton

class LlmConfigActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.llm_config_title))
            showBackButton(true) { finish() }
        }

        val spProvider = findViewById<Spinner>(R.id.spProvider)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etModelName = findViewById<EditText>(R.id.etModelName)
        val layoutThinkingMode = findViewById<View>(R.id.layoutThinkingMode)
        val swThinkingMode = findViewById<SwitchCompat>(R.id.swThinkingMode)

        // Setup provider spinner
        val providers = listOf(
            LlmProvider.OPENAI,
            LlmProvider.ANTHROPIC,
            LlmProvider.OPENGODE_GO,
            LlmProvider.DEEPSEEK
        )
        val providerLabels = providers.map { provider ->
            when (provider) {
                LlmProvider.OPENAI -> getString(R.string.llm_config_provider_openai)
                LlmProvider.ANTHROPIC -> getString(R.string.llm_config_provider_anthropic)
                LlmProvider.OPENGODE_GO -> getString(R.string.llm_config_provider_opencode_go)
                LlmProvider.DEEPSEEK -> getString(R.string.llm_config_provider_deepseek)
            }
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spProvider.adapter = adapter

        // Load saved values
        val savedProviderStr = KVUtils.getLlmProvider()
        val savedProvider = try { LlmProvider.valueOf(savedProviderStr) } catch (e: Exception) { LlmProvider.OPENAI }
        val savedProviderIndex = providers.indexOf(savedProvider).coerceAtLeast(0)
        spProvider.setSelection(savedProviderIndex)

        etApiKey.setText(KVUtils.getLlmApiKey())
        etBaseUrl.setText(KVUtils.getLlmBaseUrl().ifEmpty { AgentConfig.defaultBaseUrl(savedProvider) })
        etModelName.setText(KVUtils.getLlmModelName())
        swThinkingMode.isChecked = KVUtils.isLlmThinkingMode()

        // Show/hide thinking mode based on provider
        fun updateThinkingModeVisibility(provider: LlmProvider) {
            layoutThinkingMode.visibility = when (provider) {
                LlmProvider.DEEPSEEK, LlmProvider.OPENGODE_GO -> View.VISIBLE
                else -> View.GONE
            }
        }
        updateThinkingModeVisibility(savedProvider)

        // When provider changes, auto-fill base URL and toggle thinking visibility
        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedProvider = providers[position]
                // Auto-fill base URL if current field is empty or matches previous default
                val currentUrl = etBaseUrl.text.toString().trim()
                val currentProvider = try {
                    LlmProvider.valueOf(KVUtils.getLlmProvider())
                } catch (e: Exception) { LlmProvider.OPENAI }
                if (currentUrl.isEmpty() || currentUrl == AgentConfig.defaultBaseUrl(currentProvider)) {
                    etBaseUrl.setText(AgentConfig.defaultBaseUrl(selectedProvider))
                }
                updateThinkingModeVisibility(selectedProvider)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val baseUrl = etBaseUrl.text.toString().trim()
            val modelName = etModelName.text.toString().trim().ifEmpty { "" }
            val selectedProvider = providers[spProvider.selectedItemPosition]
            val thinkingMode = swThinkingMode.isChecked

            if (apiKey.isEmpty()) {
                Toast.makeText(this, getString(R.string.llm_config_api_key_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            KVUtils.setLlmProvider(selectedProvider.name)
            KVUtils.setLlmApiKey(apiKey)
            KVUtils.setLlmBaseUrl(baseUrl)
            KVUtils.setLlmModelName(modelName)
            KVUtils.setLlmThinkingMode(thinkingMode)

            ClawApplication.appViewModelInstance.updateAgentConfig()
            ClawApplication.appViewModelInstance.initAgent()
            ClawApplication.appViewModelInstance.afterInit()
            Toast.makeText(this, getString(R.string.llm_config_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/apk/claw/android/ui/settings/LlmConfigActivity.kt
git commit -m "feat: add provider selector and thinking mode toggle to LLM config page"
```

---

### Task 10: Update ConfigServer `/api/llm` Endpoints

**Files:**
- Modify: `app/src/main/java/com/apk/claw/android/server/ConfigServer.kt` (lines 210-262)

- [ ] **Step 1: Add `llmProvider` and `llmThinkingMode` to `handleGetLlm()`**

In `handleGetLlm()` (line 210), add to the data JsonObject:
```kotlin
addProperty("llmProvider", KVUtils.getLlmProvider())
addProperty("llmThinkingMode", KVUtils.isLlmThinkingMode())
```

- [ ] **Step 2: Add `llmProvider` and `llmThinkingMode` to `handlePostLlm()`**

In `handlePostLlm()` (line 225), add after the model name block (after line 253):
```kotlin
if (json.has("llmProvider")) {
    KVUtils.setLlmProvider(json.get("llmProvider").asString)
}
if (json.has("llmThinkingMode")) {
    KVUtils.setLlmThinkingMode(json.get("llmThinkingMode").asBoolean)
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/apk/claw/android/server/ConfigServer.kt
git commit -m "feat: add provider and thinkingMode to ConfigServer /api/llm endpoints"
```

---

### Task 11: Update LAN Config H5 Page

**Files:**
- Modify: `app/src/main/assets/web/index.html`

- [ ] **Step 1: Add provider dropdown and thinking toggle to the LLM card**

In the LLM card section (lines 149-167), replace with:
```html
    <div class="card llm">
      <div class="card-title"><span class="dot"></span><span data-i18n="llm_title"></span></div>
      <div class="field">
        <label data-i18n="llm_provider"></label>
        <select id="llmProvider" style="width:100%;padding:12px 14px;border:1.5px solid #e5e5ea;border-radius:10px;font-size:15px;outline:none;background:#fafafa;">
          <option value="OPENAI">OpenAI</option>
          <option value="ANTHROPIC">Anthropic</option>
          <option value="OPENGODE_GO">OpenCode Go</option>
          <option value="DEEPSEEK">DeepSeek</option>
        </select>
      </div>
      <div class="field">
        <label data-i18n="llm_api_key"></label>
        <div class="input-wrap">
          <input type="password" id="llmApiKey" data-i18n-placeholder="ph_llm_api_key" autocomplete="off">
          <button class="toggle-eye" onclick="togglePassword('llmApiKey', this)" data-i18n-title="toggle_visibility"></button>
        </div>
      </div>
      <div class="field">
        <label data-i18n="llm_base_url"></label>
        <input type="text" id="llmBaseUrl" data-i18n-placeholder="ph_llm_base_url" autocomplete="off">
      </div>
      <div class="field">
        <label data-i18n="llm_model_name"></label>
        <input type="text" id="llmModelName" data-i18n-placeholder="ph_llm_model" autocomplete="off">
      </div>
      <div class="field" id="thinkingModeField" style="display:none">
        <label style="display:flex;align-items:center;justify-content:space-between">
          <span data-i18n="llm_thinking_mode"></span>
          <input type="checkbox" id="llmThinkingMode" style="width:18px;height:18px;">
        </label>
        <p style="font-size:12px;color:#86868b;margin-top:4px;" data-i18n="llm_thinking_tip"></p>
      </div>
      <p style="font-size:12px;color:#86868b;margin-top:8px;" data-i18n="llm_tip"></p>
    </div>
```

- [ ] **Step 2: Add i18n messages for the new fields**

In the `zh` section, add:
```js
    llm_provider: '提供商',
    llm_thinking_mode: '思考模式',
    llm_thinking_tip: '开启 DeepSeek 的 reasoning_content（思考链）',
```
In the `en` section:
```js
    llm_provider: 'Provider',
    llm_thinking_mode: 'Thinking Mode',
    llm_thinking_tip: 'Enable DeepSeek reasoning_content (thinking chain)',
```
In the `ja` section:
```js
    llm_provider: 'プロバイダー',
    llm_thinking_mode: '思考モード',
    llm_thinking_tip: 'DeepSeek reasoning_content（思考チェーン）を有効にする',
```

- [ ] **Step 3: Update the load function**

Replace `const llmFields = ['llmApiKey', 'llmBaseUrl', 'llmModelName'];` with:
```js
const llmFields = ['llmApiKey', 'llmBaseUrl', 'llmModelName', 'llmProvider', 'llmThinkingMode'];
```

Add a special handler after loading LLM data to show/hide thinking mode:
```js
    // Show/hide thinking mode field based on provider
    const llmProviderEl = document.getElementById('llmProvider');
    const thinkingField = document.getElementById('thinkingModeField');
    function toggleThinkingMode() {
      thinkingField.style.display = (llmProviderEl.value === 'DEEPSEEK' || llmProviderEl.value === 'OPENGODE_GO') ? 'block' : 'none';
    }
    llmProviderEl.addEventListener('change', toggleThinkingMode);
    // Also handle initial load - set provider then toggle
    // Note: llmThinkingMode sends boolean, need to handle checkbox properly in load
```

Update the load function to handle the checkbox properly:
```js
    if (llmJson.code === 0 && llmJson.data) {
      // Set provider first, then toggle visibility
      const providerEl = document.getElementById('llmProvider');
      if (providerEl && llmJson.data.llmProvider) {
        providerEl.value = llmJson.data.llmProvider;
      }
      llmFields.forEach(f => {
        if (f === 'llmThinkingMode') {
          const el = document.getElementById(f);
          if (el && llmJson.data[f] !== undefined) el.checked = llmJson.data[f] === true;
        } else {
          const el = document.getElementById(f);
          if (el && llmJson.data[f] !== undefined) el.value = llmJson.data[f] || '';
        }
      });
      toggleThinkingMode();
    }
```

Update the save function to send the provider and thinking mode:
```js
    // After the existing llmFields handling
    const llmBody = {};
    llmFields.forEach(f => {
      if (f === 'llmThinkingMode') {
        const el = document.getElementById(f);
        if (el) llmBody[f] = el.checked;
      } else {
        const el = document.getElementById(f);
        if (el) llmBody[f] = el.value.trim();
      }
    });
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/web/index.html
git commit -m "feat: add provider selection and thinking toggle to LAN config page"
```

---

### Task 12: Verify Build

- [ ] **Step 1: Run the Gradle build to verify compilation**

```bash
cd D:\project\ApkClaw
./gradlew assembleDebug 2>&1 | tail -100
```

Expected: `BUILD SUCCESSFUL` with no errors.

If errors occur, fix them and repeat step 1.

- [ ] **Step 2: Commit any build fixes**

```bash
git add -A
git commit -m "fix: build errors from provider system implementation"
```

---

### Task 13: Push to Fork

- [ ] **Step 1: Push all commits**

```bash
git push origin main
```

Expected: All commits pushed to `SQW114514/HamsterClaw`.
