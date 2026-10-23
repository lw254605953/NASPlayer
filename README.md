# NASPlayer

面向Android手机的个人NAS媒体播放器，将视频、音乐和自己的媒体服务器放在一起。

当前开发版本 **0.20.9**，最低 **Android13**，尚未发布应用市场正式版本。

## 开发说明

**本项目完全由 ChatGPT 开发。** 项目需求与产品取舍由维护者提出，应用的自有代码、界面及开发文档由 ChatGPT 协助完成；AndroidX、FFmpeg等第三方组件仍归各自作者所有。

## 下载

前往 [GitHub Releases](https://github.com/lw254605953/NASPlayer/releases) 下载打包好的APK，或直接下载 [NASPlayer 0.20.9 测试版](https://github.com/lw254605953/NASPlayer/releases/download/v0.20.9/NASPlayer-0.20.9-test.apk)。需要Android13及以上，当前为开发签名测试包。

## AI功能状态 · Beta

**AI功能仍在试探和探索中，目前效果尚不理想，不推荐日常使用。** 现阶段保留实验入口供自愿体验，欢迎大家一起优化识别准确率、匹配流程与隐私边界，并通过Issues或Pull Request参与改进。

AI功能默认关闭，结果需要用户确认后才能保存，不默认上传音频文件；API费用由用户自行管理。

## 功能

- **文件浏览**：连接WebDAV，浏览NAS目录、读取原文件，独立配置多个连接。
- **Plex媒体库**：电影、电视剧资料，海报、评分、演员、分季选集、搜索、排序和多种列表视图。可绑定独立WebDAV作为视频来源。
- **视频播放**：基于Media3，提供手势/精确时间跳转、字幕、音轨、音画偏移、屏幕锁定、定时暂停及按剧设置片头片尾时间。
- **个人内容**：继续观看、记录、影片收藏和视频时间点收藏。
- **音乐**：目录扫描、标签/封面缓存、后台播放、队列、歌词、收藏、文件移动/删除，以及MV和演唱会目录。支持MP3、FLAC、WAV、APE、M4A、M4R等类型，具体可播放性取决于文件编码。
- **电视投屏**：DLNA发现、手机遥控、进度跳转和选集；Google Cast作为可选入口。
- **备份恢复**：配置、收藏和记录备份到自己的WebDAV，可选备份密码。
- **音乐资料整理 · Beta**：可选Qwen API接入，根据用户确认的线索匹配资料，预览后保存到本机补充层。

## 使用边界

本项目不提供影片、音乐或服务器账号。请仅访问你有权使用的媒体。

视频默认读取原文件，不通过应用服务器转码。Plex资料与WebDAV连接分别配置；绑定播放需要WebDAV能访问相应文件。文件后缀不代表所有编码变体都兼容，部分旧Xvid/MPEG-4 AVI仍可能黑屏或花屏；0.20.9解决了其中的起播报错，画面兼容尚未解决。

系统最终音频输出、HDR及电视格式支持受设备能力影响，不承诺所有设备都能实现原采样率输出或所有HDR模式。

无密码备份可能包含明文NAS凭据及Plex令牌，请保护备份目录。

## 构建

需要JDK17、Android SDK36及Build Tools36.0.0；完整音频扩展还需NDK28.2.13676358、CMake、Ninja、Make、curl及tar/xz。

```bash
git clone https://github.com/lw254605953/NASPlayer.git
cd NASPlayer
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"

# Linux/macOS：从固定版本源码构建音频扩展
bash scripts/build-ffmpeg-android.sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

产物为`app/build/outputs/apk/debug/app-debug.apk`，详细准备见[构建说明](docs/BUILDING.md)。源码仓库不包含APK、预编译原生库、签名密钥、本地SDK或私人媒体。当前release配置仍使用开发签名，不能直接作为应用市场正式发行包。

## 代码结构

- `app/src/main/java/com/megatron/player/source/`：WebDAV与原文件读取。
- `plex/`：Plex资料、媒体库和播放记录。
- `music/`：扫描、标签、歌词和后台播放。
- `cast/`：DLNA、Google Cast与遥控。
- `backup/`：WebDAV备份恢复。
- `ai/`：Qwen API、权限边界及资料补全。
- `decoder-ffmpeg/`：固定版本音频扩展及APE提取器。
- `scripts/`、`docs/`：构建脚本与公开说明。

包名保留`com.megatron.player`，延续早期安装及本机配置兼容。

## 反馈与后续方向

通过[Issues](https://github.com/lw254605953/NASPlayer/issues)报告问题，或提交Pull Request。播放问题请提供设备、Android/应用版本、媒体编码及已脱敏诊断，不要公开NAS地址、密码、Plex令牌、API Key或含凭据的备份。

后续候选见[路线图](docs/ROADMAP.md)，不代表已经承诺交付日期。

## 许可证

自有代码的开源许可证尚待维护者选定，本次先公开源码，暂未额外授予自有代码的复用许可。选定后会补充LICENSE文件。第三方代码和资源仍遵循各自许可证，详见[第三方声明](NOTICE.md)和[原生解码许可说明](decoder-ffmpeg/LICENSE-NOTES.md)。
