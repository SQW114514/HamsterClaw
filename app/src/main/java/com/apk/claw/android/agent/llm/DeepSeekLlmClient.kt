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
