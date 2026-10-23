# 第三方声明

仓库顶层许可证不替代第三方组件各自的许可证。

| 组件 | 来源 | 许可材料 |
| --- | --- | --- |
| Media3 FFmpeg Java/JNI包装源码 | AndroidX Media3 1.9.1，提交01c37572827dc77543fe6d0deda3efe06ec95f61 | decoder-ffmpeg/ANDROIDX-LICENSE，Apache-2.0；保留原版权头 |
| FFmpeg音频解码库 | 6.1.4，构建时下载固定源码 | decoder-ffmpeg/COPYING.LGPLv2.1、FFMPEG-LICENSE.md和LICENSE-NOTES.md；仅LGPL配置 |
| ApeExtractor.java | 改编自FFmpeg 6.1.4 libavformat/ape.c | 文件已标注LGPL-2.1-or-later并保留原作者版权 |
| Lucide/Feather相关图标 | 许可随资源保留 | app/src/main/assets/licenses/LUCIDE-ISC.txt |
| Gradle Wrapper | 8.13 | 包装脚本保留Apache-2.0版权头；分发地址及哈希见wrapper配置 |

通过Gradle获取的AndroidX、Kotlin、OkHttp等依赖和可选Google Cast SDK继续适用各自发布方条款，版本以Gradle文件为准。本项目不重新许可第三方SDK。

APK资产包含Apache-2.0、LGPL-2.1和图标许可。发布原生二进制的衍生版本时，需要继续保留相应许可并提供适用的源码、构建和替换材料。本源码仓库不提交预编译JNI库或APK。
