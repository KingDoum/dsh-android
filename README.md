# DSH Android Client

原生 Android DSH（DeepSeek Harness）客户端，基于 Jetpack Compose + Material 3 构建。

## 特点

- 原生 Kotlin + Compose UI，流畅顺滑
- 通过 DSH RPC API 直接通信，无需 WebView
- 深色主题，适配 Material You
- 会话列表 + 聊天 + 设置三 tab 导航
- WebSocket 实时事件流

## 构建

### GitHub Actions（推荐）

Push 到 main 分支自动构建，在 Actions 页面下载 APK。

### 本地构建

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew assembleDebug
```

APK 路径：`app/build/outputs/apk/debug/app-debug.apk`
