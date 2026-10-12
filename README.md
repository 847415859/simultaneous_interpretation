# 以南译

<img src="yinanyi/design/icons/anime_2_headphones.svg" width="96" align="right" alt="以南译图标">

看 B站 外语直播和投稿视频时，把**英语、日语、韩语**实时翻成**中文字幕**，以悬浮窗的形式显示在画面上。

- 默认用在线同传，译文自然、懂上下文；断网时自动切到手机本地的离线模型，字幕不中断
- 直接录制 B站 App 播放的声音，不用换播放器，弹幕、礼物照常用
- 字幕窗可拖动、调宽度、收起，可以锁定成「点击穿透」，不挡 B站 的按钮

## 界面

| 首页 | 设置 |
| :---: | :---: |
| <img src="docs/screenshots/home.png" width="260" alt="首页"> | <img src="docs/screenshots/settings.png" width="260" alt="设置页"> |

| 悬浮字幕（日语直播 → 中文） | 收起后（右侧「译」小圆点） |
| :---: | :---: |
| <img src="docs/screenshots/overlay.png" width="400" alt="B站直播画面上的悬浮中文字幕"> | <img src="docs/screenshots/collapsed.png" width="400" alt="字幕收起成屏幕边上的小圆点"> |

## 功能

**悬浮字幕窗**

- 竖屏默认贴在视频下方，横屏在底部居中；横竖屏各自记住位置和宽度
- 高度随字幕行数自动变化，不留空白
- 控制条 3 秒无操作自动隐藏，点字幕再出来
- 控制条按钮：切换源语言、锁定（点击穿透到 B站）、收起成小圆点、暂停、关闭
- 字幕外观可调：文字颜色、背景颜色、背景透明度、字号、最多显示行数、是否显示原文

**翻译**

| | 在线（默认） | 离线 |
| --- | --- | --- |
| 服务 / 模型 | 阿里云百炼 千问 LiveTranslate | 识别：ReazonSpeech（日语）、SenseVoice（英语、韩语）<br>翻译：腾讯 HY-MT1.5-1.8B |
| 什么时候用 | 有网络并填了 API Key | 断网、在线出错、没填 Key，或设置里选了「始终离线」 |
| 特点 | 边说边出，按句断开显示 | 全部在手机上运行，不花钱 |

- **术语表**：给主播名、游戏名、常见梗指定固定译法，在线和离线都生效
- **省钱**：在线模式下没人说话时不上传音频；首页显示当天上传时长和估算费用
- **稳定**：在线断线先自动重连，连不上再切离线，网络恢复后切回在线

## 安装和使用

1. 从 [Releases](https://github.com/847415859/simultaneous_interpretation/releases) 下载最新的 `yinanyi-x.y.z.apk`，在手机上安装（Android 10 及以上）。
2. 打开 App，在「准备工作」里开启悬浮窗权限。
3. **在线翻译**：设置 →「在线翻译密钥」填写你自己的[阿里云百炼](https://bailian.console.aliyun.com/) API Key（北京地域）。
   Key 只保存在手机上，安装包里不含任何密钥。不填则只用离线翻译。
4. **离线翻译**（可选）：把模型放到手机上，见下文「离线模型」。
5. 选好主播说的语言，点「开始翻译」，允许录音和录屏后会自动打开 B站。

## 构建

需要 Android Studio，或 JDK 21 + Android SDK（含 NDK 28.2.13676358、CMake 3.22.1）。
下面两样太大，没有放进仓库，构建前自己下载：

1. **llama.cpp 源码**：[b11181](https://github.com/ggml-org/llama.cpp/releases/tag/b11181)，解压到 `third_party/llama.cpp-b11181/`
2. **sherpa-onnx**：[sherpa-onnx-1.13.8.aar](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)，放到 `yinanyi/app/libs/`

然后在 `yinanyi/` 下运行 `gradlew assembleDebug`，安装包在 `yinanyi/app/build/outputs/apk/debug/`。

## 离线模型

模型约 1.5 GB，不在安装包里。用 adb 推到手机上 App 的目录
`/sdcard/Android/data/com.yinanyi.app/files/models/`：

| 文件 / 目录 | 用途 | 来源 |
| --- | --- | --- |
| `silero_vad.onnx` | 断句 | sherpa-onnx [asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| `reazon-ja/`（int8 的 encoder / decoder / joiner、`tokens.txt`） | 日语识别 | `sherpa-onnx-zipformer-ja-en-reazonspeech-2025-01-17` |
| `sense-voice/`（`model.int8.onnx`、`tokens.txt`） | 英语、韩语识别 | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09` |
| `HY-MT1.5-1.8B-Q4_K_M.gguf` | 翻译 | Hugging Face [tencent/HY-MT1.5-1.8B-GGUF](https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF) |

## 目录

| 目录 | 说明 |
| --- | --- |
| `yinanyi/` | App 源码（包名 `com.yinanyi.app`） |
| `yinanyi/design/icons/` | 图标源文件和渲染脚本 |
| `phase0-capture-test/` | 早期技术验证用的测试 App |
| `docs/screenshots/` | README 里的界面截图 |
