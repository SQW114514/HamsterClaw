# Inline Chat Test Design Spec

## Goal

Add a built-in chat interface in the app that allows users to test LLM API connectivity and interact with the Agent **without** configuring any messaging channel (DingTalk, Feishu, QQ, etc.).

## Architecture

A new `ChatActivity` launched from the home page. It wraps `DefaultAgentService` with a custom `AgentCallback` that renders responses directly into a chat bubble UI, bypassing `TaskOrchestrator` and the entire Channel system.

```
HomeActivity (新增按钮)
    │  点击 "测试对话"
    ▼
ChatActivity
    ├── RecyclerView ← ChatMessageAdapter (用户/AI 气泡)
    ├── EditText (输入框)
    └── 发送按钮
            │
            ▼
    DefaultAgentService.executeTask(text, ChatCallback)
            │
            ▼
    ChatCallback:
        onContent()  → 追加/更新 AI 气泡
        onToolCall() → 显示工具调用日志 (灰色小字)
        onToolResult()→ 显示工具结果
        onComplete() → 标记完成
        onError()    → 显示错误
```

## Files

| File | Action | Purpose |
|------|--------|---------|
| `ChatActivity.kt` | Create | 全屏聊天 Activity |
| `activity_chat.xml` | Create | 聊天界面布局 |
| `ChatMessageAdapter.kt` | Create | 消息气泡适配器 |
| `ChatCallback.kt` | Create | AgentCallback 实现 → UI 更新 |
| `HomeActivity.kt` | Modify | 加「测试对话」按钮 |
| `activity_home.xml` | Modify | 加按钮（btnTestChat） |
| `strings.xml` + `values-zh/strings.xml` | Modify | 新增字符串 |

## UI Spec

### ChatActivity Layout (`activity_chat.xml`)

主题色：
- 背景: `@color/colorBgPrimary`
- 用户气泡: `@color/colorContainerBrighten`，白色文字
- AI 气泡: `@color/colorPrimary` 或 `@color/colorContainerBrighten`，灰色小字工具日志
- 工具栏: `CommonToolbar`，标题 "测试对话" / "API Test"
- 底部输入栏: CardView 包裹 EditText + 发送图标按钮（与已有 UI 控件风格一致）

布局结构：
```
┌─────────────────────────┐
│ CommonToolbar (返回按钮)  │
├─────────────────────────┤
│                         │
│  RecyclerView           │
│  ┌──────────────────┐   │
│  │ 用户气泡 (右对齐)  │   │
│  └──────────────────┘   │
│  ┌──────────────────┐   │
│  │ AI 气泡 (左对齐)   │   │
│  │ 工具日志 (灰色小字) │   │
│  └──────────────────┘   │
│                         │
├─────────────────────────┤
│ CardView                │
│ ┌─────────────────────┐ │
│ │ EditText  │ 发送图标 │ │
│ └─────────────────────┘ │
└─────────────────────────┘
```

### ChatMessage Model

```kotlin
data class ChatMessage(
    val role: String,         // "user" | "assistant" | "tool_log"
    val content: String,      // 显示文本
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false  // AI 正在流式输出
)
```

### ChatCallback

```kotlin
class ChatCallback(
    private val onMessageUpdate: (ChatMessage) -> Unit
) : AgentCallback {
    // onLoopStart → 可忽略或显示轮次
    // onContent → 追加/更新 AI 气泡（流式逐字追加）
    // onToolCall → 追加一个灰色工具日志气泡
    // onToolResult → 更新对应工具日志气泡的结果部分
    // onComplete → 标记消息流式结束
    // onError → 显示错误气泡
}
```

## UX Flow

1. 用户打开 App → 主页底部看到「测试对话」按钮
2. 点击进入 ChatActivity，显示空聊天界面
3. 用户在底部 EditText 输入文字，点击发送
4. 用户消息以蓝色气泡出现在右侧
5. AI 开始响应，左侧出现灰色气泡逐字显示内容
6. 如果 AI 调用工具，灰色小字显示工具名称和参数
7. 工具执行完毕后显示结果摘要
8. 任务完成后显示完成标记
9. 用户可以继续输入下一条消息（新任务）
10. 点左上角返回主页

## Scope

- **不修改** `DefaultAgentService`、`TaskOrchestrator`、`AgentConfig` 等核心类
- **不涉及** Channel 系统、无障碍服务
- 纯新增文件 + 主页的极小改动
