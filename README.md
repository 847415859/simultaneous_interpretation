# 以南译（simultaneous_interpretation）

安卓上的实时同声翻译 App：录制手机正在播放的声音（首版只适配 B站 直播和投稿视频），
把英语、日语、韩语实时翻成中文，以悬浮字幕显示在画面上。

- 需求文档：<https://claude.ai/code/artifact/ccb80b4d-a714-479f-b9c1-d528e1ca3fd0>
- 界面原型：<https://claude.ai/artifact/DXmyghQDfY1vQ56b7gn2qT>

## 目录

| 目录 | 说明 |
| --- | --- |
| `yinanyi/` | 正式 App（包名 `com.yinanyi.app`） |
| `phase0-capture-test/` | 阶段 0 技术验证用的测试 App，保留作对照 |
| `third_party/` | llama.cpp 源码（不提交，见下文） |

## 方案

- **在线（默认）**：阿里云百炼 `qwen3.5-livetranslate-flash-realtime`，WebSocket 实时同传。
- **离线（断网 / 在线出错 / 设置里强制离线）**：Silero VAD 断句 →
  识别（日语 ReazonSpeech，英语、韩语 SenseVoice，均经 sherpa-onnx）→
  翻译 HY-MT1.5-1.8B（经 llama.cpp）。

## 构建

需要 Android Studio（或 JDK 21 + Android SDK，SDK 里装 NDK 28.2.13676358 和 CMake 3.22.1）。
以下文件太大或含密钥，没有提交，构建前自己准备：

1. **llama.cpp 源码**：下载 [b11181](https://github.com/ggml-org/llama.cpp/releases/tag/b11181)
   的源码包，解压到 `third_party/llama.cpp-b11181/`。
2. **sherpa-onnx**：下载 [sherpa-onnx-1.13.8.aar](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)，
   放到 `yinanyi/app/libs/`（测试 App 放 `phase0-capture-test/app/libs/`）。
3. **在线密钥**：在 `yinanyi/local.properties` 里加一行 `dashscope.api.key=sk-…`（北京地域的百炼 API Key）。
   不配置时 App 只用离线模式。

然后在 `yinanyi/` 下运行 `gradlew assembleDebug`。

## 离线模型

开发阶段模型不打进安装包，用 adb 推到手机上 App 的外部目录
`/sdcard/Android/data/com.yinanyi.app/files/models/`：

| 文件 / 目录 | 来源 |
| --- | --- |
| `silero_vad.onnx` | sherpa-onnx `asr-models` 发布页 |
| `sense-voice/`（`model.int8.onnx`、`tokens.txt`） | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09` |
| `reazon-ja/`（int8 的 encoder / decoder / joiner、`tokens.txt`） | `sherpa-onnx-zipformer-ja-en-reazonspeech-2025-01-17` |
| `HY-MT1.5-1.8B-Q4_K_M.gguf` | Hugging Face `tencent/HY-MT1.5-1.8B-GGUF` |
