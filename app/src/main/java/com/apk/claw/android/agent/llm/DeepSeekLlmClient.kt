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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DeepSeekLlmClient(
    private val config: AgentConfig
) : LlmClient {

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val r = chain.request()
            XLog.d("DeepSeek", "--> ${r.method} ${r.url}")
            val resp = chain.proceed(r)
            val b = resp.body?.string() ?: ""
            XLog.d("DeepSeek", "<-- ${resp.code} (${b.length} chars)")
            resp.newBuilder().body(b.toResponseBody(resp.body?.contentType())).build()
        }
        .build()

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        val json = buildJson(messages, toolSpecs, streaming = false)
        val req = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody(JSON))
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: ""
        if (!resp.isSuccessful) throw RuntimeException("API error ${resp.code}: $body")
        return parse(body)
    }

    override fun chatStreaming(
        messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>, listener: StreamingListener
    ): LlmResponse {
        val json = buildJson(messages, toolSpecs, streaming = true)
        val req = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(json.toRequestBody(JSON))
            .build()

        val latch = CountDownLatch(1)
        val result = AtomicReference<LlmResponse>()
        val err = AtomicReference<Throwable>()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<ToolExecutionRequest>()
        var curTool: String? = null
        var curArgs = StringBuilder()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                err.set(e); listener.onError(e); latch.countDown()
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line!!.trim()
                        if (!l.startsWith("data: ")) continue
                        val d = l.removePrefix("data: ").trim()
                        if (d == "[DONE]" || d.isEmpty()) continue
                        try {
                            val j = JsonParser.parseString(d).asJsonObject
                            if (!j.has("choices")) continue
                            val c = j.getAsJsonArray("choices")[0].asJsonObject
                            val delta = c.getAsJsonObject("delta")
                            if (delta.has("content") && !delta.get("content").isJsonNull) {
                                val t = delta.get("content").asString; text.append(t); listener.onPartialText(t)
                            }
                            if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull) {
                                for (tc in delta.getAsJsonArray("tool_calls")) {
                                    val f = tc.asJsonObject.getAsJsonObject("function")
                                    if (f.has("name") && !f.get("name").isJsonNull) { curTool = f.get("name").asString; curArgs = StringBuilder() }
                                    if (f.has("arguments") && !f.get("arguments").isJsonNull) curArgs.append(f.get("arguments").asString)
                                }
                            }
                            if (c.has("finish_reason") && !c.get("finish_reason").isJsonNull
                                && c.get("finish_reason").asString == "tool_calls" && curTool != null) {
                                toolCalls.add(ToolExecutionRequest.builder().id("call_${toolCalls.size}").name(curTool!!).arguments(curArgs.toString()).build())
                            }
                        } catch (_: Exception) {}
                    }
                    val resp2 = LlmResponse(text.toString().ifEmpty { null }, toolCalls, null)
                    result.set(resp2); listener.onComplete(resp2)
                } catch (e: Exception) { err.set(e); listener.onError(e) }
                finally { response.close(); latch.countDown() }
            }
        })
        latch.await(); err.get()?.let { throw it }; return result.get()
    }

    private fun buildJson(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>, streaming: Boolean): String {
        val root = JsonObject()
        root.addProperty("model", config.modelName.ifEmpty { "deepseek-v4-flash" })
        root.addProperty("stream", streaming)
        root.addProperty("temperature", config.temperature)
        val arr = JsonArray()
        for (m in messages) arr.add(convert(m))
        root.add("messages", arr)
        if (toolSpecs.isNotEmpty()) {
            val tArr = JsonArray()
            for (s in toolSpecs) tArr.add(convertTools(s))
            root.add("tools", tArr)
        }
        return gson.toJson(root)
    }

    private fun convert(m: ChatMessage): JsonObject = JsonObject().apply {
        when (m) {
            is SystemMessage -> { addProperty("role", "system"); addProperty("content", m.text()) }
            is UserMessage -> { addProperty("role", "user"); addProperty("content", m.singleText()) }
            is AiMessage -> {
                addProperty("role", "assistant")
                m.text()?.let { addProperty("content", it) }
                m.toolExecutionRequests()?.let { tcs ->
                    val arr = JsonArray()
                    for ((i, tc) in tcs.withIndex()) arr.add(JsonObject().apply {
                        addProperty("id", tc.id() ?: "call_$i")
                        addProperty("type", "function")
                        add("function", JsonObject().apply { addProperty("name", tc.name()); addProperty("arguments", tc.arguments()) })
                    })
                    add("tool_calls", arr)
                }
            }
            is ToolExecutionResultMessage -> { addProperty("role", "tool"); addProperty("tool_call_id", m.id()); addProperty("content", m.text()) }
        }
    }

    private fun convertTools(spec: ToolSpecification): JsonObject = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", spec.name())
            addProperty("description", spec.description() ?: "")
            spec.parameters()?.let { params ->
                val schema = params as? JsonObjectSchema ?: return@let
                val props = JsonObject(); val required = JsonArray()
                schema.properties().forEach { (name, ps) ->
                    props.add(name, JsonObject().apply {
                        addProperty("type", when (ps) {
                            is JsonStringSchema -> "string"; is JsonIntegerSchema -> "integer"
                            is JsonNumberSchema -> "number"; is JsonBooleanSchema -> "boolean"
                            else -> "string"
                        })
                        val desc = (ps as? JsonStringSchema)?.description() ?: (ps as? JsonIntegerSchema)?.description()
                            ?: (ps as? JsonNumberSchema)?.description() ?: (ps as? JsonBooleanSchema)?.description()
                        if (desc != null) addProperty("description", desc)
                    })
                }
                schema.required().forEach { required.add(it) }
                add("parameters", JsonObject().apply { addProperty("type", "object"); add("properties", props); add("required", required) })
            }
        })
    }

    private fun parse(body: String): LlmResponse {
        val j = JsonParser.parseString(body).asJsonObject
        val usage = if (j.has("usage")) { val u = j.getAsJsonObject("usage"); TokenUsage(u.get("prompt_tokens").asInt, u.get("completion_tokens").asInt) } else null
        val choices = j.getAsJsonArray("choices")
        if (choices.size() == 0) return LlmResponse(null, emptyList(), usage)
        val msg = choices[0].asJsonObject.getAsJsonObject("message")
        val text = if (msg.has("content") && !msg.get("content").isJsonNull) msg.get("content").asString else null
        val tools = if (msg.has("tool_calls") && !msg.get("tool_calls").isJsonNull) {
            msg.getAsJsonArray("tool_calls").map { tc ->
                val f = tc.asJsonObject.getAsJsonObject("function")
                ToolExecutionRequest.builder().id(tc.asJsonObject.get("id").asString).name(f.get("name").asString).arguments(f.get("arguments").asString).build()
            }
        } else emptyList()
        return LlmResponse(text, tools, usage)
    }
}
