# ApkClaw — Agent Guide

## 项目概述

AI 驱动的 Android 自动化 App。用户通过消息渠道（钉钉/飞书/QQ/Discord/Telegram/微信）发送自然语言指令，LLM Agent 理解意图后操控手机执行任务。

基于 LangChain4j 的 Agent 循环：Observe → Think → Act → Verify，通过 Android 无障碍服务操作设备。

## 技术栈

| 领域 | 技术 |
|------|------|
| 语言 | Kotlin 62%, Java 33%, HTML 4% |
| 构建 | Gradle 9.3.1, JDK 21, Android SDK 36, minSdk 28 |
| AI Agent | LangChain4j 1.12.2 (OpenAI/Anthropic/DeepSeek) |
| HTTP | OkHttp 4.12.0 (自定义适配器替换 JDK HttpClient) |
| 存储 | MMKV (Tencent 高性能 KV) |
| 序列化 | Gson |
| 嵌入式服务器 | NanoHTTPD (LAN 配置页端口 9527) |
| UI | 原生 Android (BaseActivity 密度适配), Glide, EasyFloat |

## 构建

```bash
# Debug APK
./gradlew assembleDebug

# Release APK（需要 local.properties 配置 keystore）
./gradlew assembleRelease
```

依赖自动从腾讯云镜像下载（Gradle 已配置）。需要在 `local.properties` 中设置：
```
sdk.dir=D\:\\android-sdk
```

## 项目结构

```
app/src/main/java/com/apk/claw/android/
├── agent/                    # Agent 循环、配置、回调
│   ├── langchain/            # LangChain4j 桥接 + OkHttp 适配器
│   └── llm/                  # LLM 客户端实现
├── base/                     # BaseActivity（屏幕密度适配）
├── channel/                  # 消息渠道处理器
│   ├── dingtalk/
│   ├── feishu/
│   ├── qqbot/
│   ├── discord/
│   ├── telegram/
│   └── wechat/
├── floating/                 # 悬浮球管理器
├── server/                   # LAN 配置 & 调试 HTTP 服务
├── service/                  # 无障碍、前台、保活服务
├── tool/                     # 工具抽象层 & 注册表
│   └── impl/                 # 工具实现（common/phone/TV）
├── ui/                       # Activity（启动、主页、引导、设置）
│   └── chat/                 # 内嵌对话测试（ChatActivity, ChatCallback, ChatMessageAdapter）
├── utils/                    # KVUtils, XLog 等工具
└── widget/                   # 自定义 UI 组件
```

## 核心架构

### LLM 提供商系统

`LlmProvider` 枚举定义支持的 AI 提供商：

| Provider | 默认 Base URL | 客户端实现 | 说明 |
|----------|--------------|------------|------|
| `OPENAI` | `https://api.openai.com/v1` | `OpenAiLlmClient` | 标准 OpenAI 兼容 API |
| `ANTHROPIC` | `https://api.anthropic.com/v1` | `AnthropicLlmClient` | Anthropic Messages API |
| `OPENGODE_GO` | `https://opencode.ai/zen/go/v1` | `DeepSeekLlmClient` | OpenAI 兼容 + thinking |
| `DEEPSEEK` | `https://api.deepseek.com/v1` | `DeepSeekLlmClient` | OpenAI 兼容 + thinking |

**Provider 选择流程：**
1. 用户在 `LlmConfigActivity` 下拉选择 Provider
2. 选择时自动填充默认 Base URL
3. 保存到 `KVUtils`（MMKV 持久化）
4. `AppViewModel.getAgentConfig()` 读取持久化配置构建 `AgentConfig`
5. `LlmClientFactory.create(config)` 根据 `config.provider` 实例化对应客户端

**添加新 Provider 需要在以下位置修改：**
1. `LlmProvider` enum → 添加枚举值
2. `AgentConfig` companion → 添加 `defaultBaseUrl`
3. `LlmClientFactory` when 分支 → 映射到对应客户端
4. `LlmConfigActivity` → 如果有 UI 变更
5. `KVUtils` → 如果新增配置字段
6. `ConfigServer` GET/POST → 如果新增 API 字段
7. `assets/web/index.html` → 如果新增 H5 表单字段

### DeepSeek / Thinking 模式

`DeepSeekLlmClient` 直接使用 OkHttp 发送 HTTP 请求（绕过 LangChain4j ChatModel 层），手动处理 `reasoning_content`：

- **请求时**：添加 `{"thinking": {"type": "enabled"}}` 参数
- **响应时**：解析 `reasoning_content` 字段
- **历史保持**：内部 `reasoningCache: Map<assistant消息文本, reasoningContent>` 在对话轮次间保持 thinking 上下文
- **同时兼容** OpenCode Go 和 DeepSeek 官方 API

### Agent 循环 (`DefaultAgentService`)

```
1. 构建 System Prompt（含设备信息、已注册工具列表）
2. 调用 LLM（带 3 次指数退避重试）
3. 解析响应 → 提取文本 & 工具调用
4. 执行工具 → 添加结果到消息历史
5. 循环检测（4 轮滑动窗口）
6. 上下文压缩（get_screen_info 只保留最新一次）
7. 重复直到 finish 工具被调用或超 60 轮
```

### 工具系统

所有工具继承 `BaseTool`，通过 `ToolRegistry` 注册。`LangChain4jToolBridge` 将工具转换为 LangChain4j `ToolSpecification`。

- **通用工具**：`get_screen_info`, `find_node_info`, `tap`, `input_text`, `open_app`, `swipe`, 系统按键等
- **添加工具**：在 `tool/impl/` 下创建实现类，`ToolRegistry.registerAllTools()` 中注册

### 消息渠道

`ChannelManager` 统一管理。渠道通过 Stream Client / WebSocket / Bot API 接收消息，通过 `TaskOrchestrator` 分发任务。

### 内嵌对话测试（Inline Chat Test）

主页工具栏下方有 **「测试对话」** 按钮，可直接与 Agent 对话，无需配置任何外部消息渠道。

**实现文件：**
| 文件 | 说明 |
|------|------|
| `ui/chat/ChatActivity.kt` | 全屏聊天 Activity |
| `ui/chat/ChatMessage.kt` | 消息数据模型（role, content, timestamp, isStreaming） |
| `ui/chat/ChatCallback.kt` | 实现 AgentCallback，将 Agent 响应转为 UI 消息 |
| `ui/chat/ChatMessageAdapter.kt` | RecyclerView 适配器（用户/AI/工具日志三种气泡） |
| `res/layout/activity_chat.xml` | 聊天界面布局（RecyclerView + 底部输入栏） |
| `res/layout/item_chat_*.xml` | 三种气泡布局 |
| `res/drawable/bg_chat_*.xml` | 气泡形状背景 |

**交互流程：**
1. 用户输入文字 → 调用 `ChatActivity.sendMessage()`
2. 创建 `AgentService` + `ChatCallback` → 调用 `executeTask()`
3. `ChatCallback.onContent()` → 流式逐字追加 AI 气泡
4. `ChatCallback.onToolCall/ToolResult()` → 灰色小字工具日志
5. `ChatCallback.onComplete()` → 标记完成，恢复输入

**注意点：**
- 每次发送消息创建新的 AgentService 实例，不共享缓存
- `ChatCallback` 使用 `Handler(Looper.getMainLooper())` 确保 UI 更新在主线程
- DeepSeek thinking 模式在本界面也可正常使用

## 常见开发任务

### 修改 LLM 配置页面

- UI 布局：`res/layout/activity_llm_config.xml`
- Activity 逻辑：`ui/settings/LlmConfigActivity.kt`
- 字符串：`res/values/strings.xml` + `values-zh/strings.xml`
- LAN 配置 H5：`assets/web/index.html`
- LAN 配置 API：`server/ConfigServer.kt`

### 新增 LLM 客户端

1. 在 `agent/llm/` 下创建新类实现 `LlmClient` 接口
2. 在 `LlmProvider` enum 添加对应值
3. 在 `LlmClientFactory` 添加 when 分支
4. 在 `AgentConfig.defaultBaseUrl()` 添加对应 URL

### 修改/新增内嵌对话

- UI 布局：`res/layout/activity_chat.xml` + `res/layout/item_chat_*.xml`
- 核心逻辑：`ui/chat/ChatActivity.kt` + `ChatCallback.kt`
- 适配器：`ui/chat/ChatMessageAdapter.kt`
- 入口按钮：主页 `activity_home.xml` 中 `btnTestChat2`
- 入口处理：`HomeActivity.kt` → `initViews()`

### 发布 Release

1. 在 `local.properties` 配置 keystore 信息
2. `./gradlew assembleRelease`
3. APK 输出在 `app/build/outputs/apk/release/`
4. APK 文件名格式：`ApkClaw_v{version}_{timestamp}.apk`

## 代码规范

- 遵循项目现有命名风格（camelCase, 包名全小写）
- 工具描述中英文双语
- 日志使用 `XLog` 工具类
- 持久化使用 `KVUtils`（MMKV 封装）
- UI 布局使用 `pt` 单位适配屏幕密度
- `BaseActivity` 提供统一的屏幕密度适配
