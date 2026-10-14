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

| 悬浮字幕（日语直播 → 中文） | 控制条自动隐藏后 |
| :---: | :---: |
| <img src="docs/screenshots/overlay.png" width="400" alt="B站竖屏直播下方的悬浮中文字幕，带控制条"> | <img src="docs/screenshots/overlay-hidden.png" width="400" alt="控制条隐藏后只剩字幕"> |

| 横屏全屏 | 收起后（右侧「译」小圆点） |
| :---: | :---: |
| <img src="docs/screenshots/landscape.png" width="480" alt="B站横屏全屏时的悬浮字幕"> | <img src="docs/screenshots/collapsed.png" width="240" alt="字幕收起成屏幕边上的小圆点"> |

> 截图里的直播画面来自 B站，左侧弹幕栏已打码，透过字幕窗的弹幕用户名已抹去。

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
4. **离线翻译**（可选）：首页「准备工作」里点「去下载」，或设置 →「离线模型 管理」，下载你要用的语言所需的模型，见下文「离线模型」。
5. **后台运行**：首页「准备工作」里把「后台不被系统杀掉」设为允许。小米手机还要把省电策略设为「无限制」、打开自启动，否则看直播时翻译可能被系统关掉。
6. 选好主播说的语言，点「开始翻译」，允许录音和录屏后会自动打开 B站。

## 构建

需要 Android Studio，或 JDK 21 + Android SDK（含 NDK 28.2.13676358、CMake 3.22.1）。
下面两样太大，没有放进仓库，构建前自己下载：

1. **llama.cpp 源码**：[b11181](https://github.com/ggml-org/llama.cpp/releases/tag/b11181)，解压到 `third_party/llama.cpp-b11181/`
2. **sherpa-onnx**：[sherpa-onnx-1.13.8.aar](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)，放到 `yinanyi/app/libs/`

然后在 `yinanyi/` 下运行 `gradlew assembleDebug`，安装包在 `yinanyi/app/build/outputs/apk/debug/`。
单元测试：`gradlew testDebugUnitTest`（下载器的续传、换源、校验都有测试）。

## 发布

`gradlew assembleRelease` 生成压缩混淆后的安装包。没有签名密钥时用 debug 签名，只能自己试用；
正式发布要用固定的发布签名，否则换一台电脑编译出来的包无法覆盖安装。

1. 生成密钥库（**放在仓库之外，并且备份**；丢了就没法再发布能覆盖安装的更新）：
   ```
   keytool -genkeypair -keystore yinanyi-release.jks -alias yinanyi -keyalg RSA -keysize 4096 -validity 36500
   ```
2. 在 `yinanyi/` 下新建 `keystore.properties`（已被 `.gitignore` 忽略，不要提交）：
   ```
   storeFile=D\:\\path\\to\\yinanyi-release.jks
   storePassword=……
   keyAlias=yinanyi
   keyPassword=……
   ```
3. 在线翻译的 API Key 不在安装包里，所以发布的 APK 可以公开。

## 离线模型

模型共约 1.4 GB，不在安装包里，在 App 里按语言一键下载（日语约 73 MB，英韩约 237 MB，翻译模型约 1.1 GB）。
每个文件下载后都会按固定的 SHA-256 校验，内容不对会丢弃并换下一个下载源，支持断点续传，所以下载源可以是任何镜像。

| 模型 | 用途 | 下载源 |
| --- | --- | --- |
| 翻译 HY-MT1.5-1.8B（Q4_K_M，1.1 GB） | 所有语言的离线翻译 | ModelScope，腾讯官方账号 [Tencent-Hunyuan](https://modelscope.cn/models/Tencent-Hunyuan/HY-MT1.5-1.8B-GGUF)；备用 Hugging Face 镜像 |
| SenseVoice（237 MB） | 英语、韩语识别 | ModelScope（第三方上传，内容与官方一致，已按 SHA-256 核对） |
| ReazonSpeech `zipformer-ja-en`（int8，73 MB） | 日语识别 | 本项目自己的 ModelScope 仓库。官方文件只发布在 GitHub 上、国内很多网络连不上，所以从官方压缩包里挑出 4 个 int8 文件原样转存了一份（SHA-256 在仓库的 README 里） |
| Silero VAD（0.6 MB） | 断句 | 已内置在 App 里 |

**手动安装**（开发调试用）：也可以把文件放到手机上 App 的目录 `/sdcard/Android/data/com.yinanyi.app/files/models/`（用 `adb push`），App 会按文件名和大小自动识别：

| 文件 / 目录 | 用途 |
| --- | --- |
| `reazon-ja/`（`encoder-epoch-35-avg-1.int8.onnx`、`decoder-epoch-35-avg-1.int8.onnx`、`joiner-epoch-35-avg-1.int8.onnx`、`tokens.txt`） | 日语识别 |
| `sense-voice/`（`model.int8.onnx`、`tokens.txt`） | 英语、韩语识别 |
| `HY-MT1.5-1.8B-Q4_K_M.gguf` | 翻译 |

文件大小必须和官方文件一致，否则 App 会认为没装好。

## 许可与声明

以南译是个人开发的非官方工具，与腾讯、阿里云、哔哩哔哩无关。第三方组件和模型的许可见 [NOTICE.md](NOTICE.md)。
其中翻译模型（腾讯混元 HY-MT1.5）的许可**不适用于欧盟、英国和韩国**。

## 目录

| 目录 | 说明 |
| --- | --- |
| `yinanyi/` | App 源码（包名 `com.yinanyi.app`） |
| `yinanyi/design/icons/` | 图标源文件和渲染脚本 |
| `NOTICE.md` | 第三方组件与许可声明 |
| `docs/screenshots/` | README 里的界面截图和处理脚本 |
