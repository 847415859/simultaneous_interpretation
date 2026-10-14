# 第三方组件与许可声明

以南译是个人开发的非官方工具，与腾讯、阿里云、哔哩哔哩没有任何关联、赞助或背书关系。

## 翻译模型：腾讯混元 HY-MT1.5

Tencent HY is licensed under the Tencent HY Community License Agreement, Copyright © 2025 Tencent. All Rights Reserved. The trademark rights of "Tencent HY" are owned by Tencent or its affiliate.

- 许可全文：<https://modelscope.cn/models/Tencent-Hunyuan/HY-MT1.5-1.8B-GGUF/file/view/master/License.txt>
- **该许可不适用于欧盟、英国和韩国**，只在这些地区之外授权使用。
- 以南译**不分发**这个模型的文件：App 在用户点「下载」后，直接从腾讯官方账号 Tencent-Hunyuan 在 ModelScope 上的仓库下载，并核对 SHA-256。
- 许可还要求：不得用该模型的输出去改进其他 AI 模型。

## 随 App 打包或下载的组件

| 组件 | 用途 | 许可 | 说明 |
| --- | --- | --- | --- |
| [Silero VAD](https://github.com/snakers4/silero-vad) | 语音检测（`silero_vad.onnx`，随 App 打包） | MIT | |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 | 离线语音识别运行库（AAR，构建时下载，不提交） | Apache-2.0 | 许可按其仓库声明，未逐文件核对 |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) b11181 | 离线翻译运行库（源码，构建时下载，不提交） | MIT | 同上 |
| [OkHttp](https://github.com/square/okhttp)、AndroidX / Jetpack Compose | 网络、界面 | Apache-2.0 | |
| SenseVoice 离线识别模型 | 英语、韩语识别（用户点「下载」后从 ModelScope 下载） | **未核实** | 模型权重的许可我没有逐条核对 |
| ReazonSpeech `k2-v2-ja-en` 离线识别模型（int8） | 日语识别（用户点「下载」后从本项目的 ModelScope 仓库下载） | **未核实** | 项目代码是 Apache-2.0，但这个模型权重的许可我没能查到（官方模型页是私有的）。因为 GitHub 在国内常连不上，本项目把 sherpa-onnx 官方发布包里的 4 个 int8 文件原样转存到了自己的 ModelScope 仓库，内容有 SHA-256 可核对。如权利人有异议，会立即删除 |

「未核实」的两项只在用户主动下载时才会取到，GitHub 仓库和安装包里都不含它们。

## 在线服务

在线翻译使用用户自己的阿里云百炼账号和 API Key（千问 LiveTranslate），受阿里云的服务条款约束，费用由用户自己承担。
