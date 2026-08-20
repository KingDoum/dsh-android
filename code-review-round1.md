# 第一轮代码审查报告（技术主管自查版）

> 审查方式：技术主管直接审查 + DSH API 实测验证（session.list / session.history / session.create / session.prompt）
> 日期：2026-08-20

## 🔴 严重问题（需立即修复）

### 1. ChatScreen 的 setApiAndSession 每次重组都执行 → 消息重复/闪烁
- **位置**：`ui/chat/ChatScreen.kt:46` + `ui/chat/ChatViewModel.kt:27`
- **问题**：`apiProvider?.let { api -> viewModel.setApiAndSession(api, sessionId) }` 在每次 Compose 重组时都会执行（不在 `remember`/`LaunchedEffect` 中）。而 `setApiAndSession` 没有去重保护（对比 SessionListViewModel 有 `if (this.api === api) return`）。导致：
  - `loadHistory()` 反复发起网络请求
  - `observeEvents()` 反复启动多个 collect 协程 → 同一条 WebSocket 消息被追加多次
- **修复**：`setApiAndSession` 加 `if (this.api === api && this.currentSessionId == sessionId) return`；ChatScreen 改用 `LaunchedEffect(sessionId)` 包裹调用。

### 2. 底部导航 Chat tab 跳到无参路由会崩溃/空页
- **位置**：`ui/navigation/AppNavigation.kt:60-70`
- **问题**：底部导航 Chat tab 的 route 是 `"chat/{sessionId}"`，但 `onClick` 直接 `navigate(screen.route)` 把字面量 `"chat/{sessionId}"` 当作路由导航，没有替换 sessionId 参数 → 导航失败或空白页。
- **修复**：Chat tab 在无当前会话时点击应无效（或显示提示），不应导航到残缺路由。将 Chat tab 的 onClick 改为：有 activeSessionId 才 navigate，否则忽略。

### 3. 会话列表标题全部显示"新对话"
- **位置**：`data/api/DshApi.kt:34-43`（listSessions 的 title = ""）
- **问题**：API 实测 session.list 返回的 projections.values.title 携带真实标题，但代码硬编码 title = "" → 所有有内容的会话都显示"新对话"。
- **修复**：从 projections.values.title 提取标题。

## 🟠 中等问题

### 4. DshApp 单例 API 懒初始化无并发保护
- **位置**：`DshApp.kt:20-35`
- **问题**：`_api` 的读-写不是原子的，多线程同时访问可能创建两个实例，且一个覆盖另一个。
- **修复**：加 @Volatile + synchronized 或双重检查锁。

### 5. 乐观消息（temp- 前缀）不会被真实 user/message 替换
- **位置**：`ui/chat/ChatViewModel.kt:70-80` + observeEvents
- **问题**：发送消息时创建 temp-<time> 的乐观消息，但 WebSocket 推送的真实 user/message 事件的 id 是服务端生成的，与 temp- 不匹配 → 乐观消息和真实消息重复显示。
- **修复**：发送后按内容匹配替换（或记录 rpcId 关联），真实事件到达后移除 temp 消息。

### 6. SessionListViewModel 事件触发全量刷新
- **位置**：`ui/sessionlist/SessionListViewModel.kt:60-61`
- **问题**：收到任意 SessionSubscribed/SessionEvent 就 loadSessions() 全量刷新 → WebSocket 高频事件时列表频繁刷新抖动。
- **修复**：节流（等 500ms 合并）或只刷新特定会话行。

## 🟡 轻微问题

### 7. 打字指示器动画未实现（静态点）
- **位置**：`ui/chat/ChatScreen.kt:216`
- **问题**：LaunchedEffect(message.id, delay) 内是空 body，三个点不跳动。
- **修复**：用 rememberInfiniteTransition 实现点的透明度/位移动画。

### 8. formatTime 每次创建 SimpleDateFormat 实例
- **位置**：`ui/sessionlist/SessionListScreen.kt` formatTime
- **问题**：每次调用创建新实例，轻微性能浪费。
- **修复**：用静态实例或 java.time 库。

### 9. 未使用 import
- **位置**：ChatScreen.kt（androidx.compose.animation.*、Icons.Outlined.Send）
- **问题**：编译警告。

## ✅ API 实测验证通过的协议

| API | 返回结构 | 与代码匹配 |
|-----|---------|-----------|
| session.list | result.value.items[] {sessionId, updatedAt, running, blank, agentPreset, projections} | ✅ 匹配（缺 title 提取） |
| session.create | result.value {sessionId, agentPreset} | ✅ 匹配 |
| session.history | result.value.events[] {event:{type, seq, time, data}} | ✅ 匹配（data.message.content 嵌套） |
| session.prompt | result.value {accepted} | ✅ 匹配 |

## 总结

共 9 个问题：3 严重、3 中等、3 轻微。核心是 Compose 生命周期与数据去重问题、会话标题缺失。优先修复 1-3，然后 4-6，最后 7-9。修复后进入第二轮审查。
