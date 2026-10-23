package com.megatron.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BrandAboutDialog(onDismiss: () -> Unit) {
    var page by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val licenseFiles = linkedMapOf("AndroidX / Media3" to "ANDROIDX-APACHE-2.0.txt", "FFmpeg / APE" to "FFMPEG-LGPL-2.1.txt", "Lucide" to "LUCIDE-ISC.txt")
    val license = page?.let { licenseFiles[it] }
    val licenseText = remember(license) { license?.let { runCatching { context.assets.open("licenses/$it").bufferedReader().use { reader -> reader.readText() } }.getOrDefault("许可文件暂时无法读取") } }
    if (page != null) {
        AlertDialog(onDismissRequest = { page = null }, title = { Text(page!!) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (page == "数据与权限") {
                    listOf(
                        "连接与记录" to "服务器地址、连接凭据、收藏、播放偏好和记录保存在本机。连接凭据使用设备密钥加密保存。",
                        "NAS备份" to "启用备份后，配置会加密上传到你指定的WebDAV目录。备份包含已确认的音乐资料及歌词，不包含影音原文件、图片缓存或模型 Key。卸载应用不会删除NAS中的备份。",
                        "网络访问" to "连接WebDAV、Plex或播放网络链接时，应用会向你选择的服务发送对应请求。Plex账号授权与资料读取会访问Plex服务；启用Google投屏会调用Google组件。",
                        "音乐资料整理" to "仅在你启用并确认后，向所选地域的阿里云 Qwen 和资料服务发送已选择的歌曲文字及候选信息。资料来源包括MusicBrainz、Cover Art Archive和LRCLIB。不发送音频、NAS地址或连接凭据；模型Key使用设备密钥加密保存，可在模型设置中清除。",
                        "附近设备" to "查找电视时会申请附近设备权限，并在本地网络发现和连接播放设备。应用不申请定位权限。",
                        "文件与后台播放" to "本地文件由系统选择器授权。后台音乐和电视投屏使用媒体前台服务；停止播放后可关闭对应会话。",
                        "删除数据" to "可在设置中移除连接、收藏和观看记录。系统应用设置中的清除数据或卸载会清除本机数据，NAS备份需另行删除。"
                    ).forEach { (title, body) -> Text(title, style = MaterialTheme.typography.titleSmall); Text(body, fontSize = 13.sp) }
                } else if (page == "开源许可") {
                    Text("本应用使用AndroidX / Media3、Kotlin、OkHttp、FFmpeg及Lucide等组件。Google Cast组件另适用其服务条款。", fontSize = 13.sp)
                    licenseFiles.keys.forEach { title -> TextButton(onClick = { page = title }) { Text(title) } }
                } else Text(licenseText.orEmpty(), fontSize = 11.sp)
            }
        }, confirmButton = { TextButton(onClick = { page = if (license != null) "开源许可" else null }) { Text("返回") } })
    } else AlertDialog(onDismissRequest = onDismiss, title = { Text("NASPlayer") }, text = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.ic_brand_mark), "NASPlayer图标", Modifier.size(112.dp))
            Text("你的私人影音空间", style = MaterialTheme.typography.titleMedium)
            Text("版本 ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            HorizontalDivider()
            TextButton(onClick = { page = "数据与权限" }) { Text("数据与权限") }
            TextButton(onClick = { page = "开源许可" }) { Text("开源许可") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
