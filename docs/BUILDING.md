# 构建 NASPlayer

环境：JDK17、Gradle8.13（Wrapper已固定分发地址和SHA256）、Android SDK36、Build Tools36.0.0。最低Android13/API33，当前Media3为1.9.1。

完整音频扩展另需Android NDK 28.2.13676358、CMake3.21及以上、Ninja、Make、curl和tar/xz。脚本支持Linux x86_64与macOS；Windows可用WSL2构建原生依赖。请用Android Studio SDK Manager安装SDK和NDK，并配置PATH及以下变量：

```bash
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
bash scripts/build-ffmpeg-android.sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

也可在不提交的local.properties中设置`sdk.dir=/path/to/Android/Sdk`。产物为`app/build/outputs/apk/debug/app-debug.apk`。

## FFmpeg音频扩展

脚本下载固定的FFmpeg6.1.4源码并校验SHA256，构建arm64-v8a和x86_64。中间文件位于忽略的`.native-build/`，生成库位于忽略的`decoder-ffmpeg/src/main/jniLibs/`。可用绝对路径`NASPLAYER_NATIVE_BUILD_DIR`改变缓存目录，用`NASPLAYER_NATIVE_JOBS`改变并行数（默认4）。

仅启用AAC、AC3、EAC3、DTS、TrueHD、MLP、FLAC、Opus、Vorbis、MP3、APE解码，不启用GPL/nonfree、编码器、网络协议或libavformat。此模块不包含FFmpeg视频软解。JNI采用静态链接和16KiB ELF对齐设置，源码及构建选项以脚本为准。

Java源码可以在缺少本机JNI时编译，但完整音频兼容功能需要先构建扩展；不能把未生成JNI的包当作完整功能版本发布。

## 测试与签名

单元测试位于`app/src/test/`，使用JUnit4。单元测试不代表真实NAS、电视、HDR、全部编码或手机已经验收；日常改动做定向检查，完整设备测试集中在里程碑。

源码仓库不包含个人签名、APK或原生预编译库。Debug使用各开发者本机密钥，通常不能覆盖维护者之前签发的安装包；覆盖安装要求相同签名。

当前release配置仍使用debug签名，属于开发分发。应用市场正式发行需单独配置生产签名并完成设备与第三方许可检查，不能将签名密码或密钥提交到仓库。
