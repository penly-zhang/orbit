package dev.codexpocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalImagePicker = staticCompositionLocalOf<(String, Int) -> Unit> { { _, _ -> } }

@Composable internal fun DraftImageTray(s: PocketState, remove: (DraftImage) -> Unit) {
    var preview by remember { mutableStateOf<DraftImage?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (s.draftImages.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.draftImages.forEach { image -> key(image.id) {
                val thumbnail by produceState<Bitmap?>(null, image.path) {
                    value = withContext(Dispatchers.IO) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(image.path, bounds)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
                        BitmapFactory.decodeFile(image.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }
                Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = Paper) {
                    Box(Modifier.size(76.dp).clickable { preview = image }) {
                        val bitmap = thumbnail
                        if (bitmap != null) Image(bitmap.asImageBitmap(), image.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(Icons.Outlined.Image, image.name, Modifier.align(Alignment.Center), tint = Mint)
                        Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(bottomStart = 10.dp), modifier = Modifier.align(Alignment.TopEnd)) {
                            IconButton(onClick = { remove(image) }, enabled = !s.sending && !s.imagesLoading, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.Close, "移除图片：${image.name}", Modifier.size(15.dp), tint = Color.White) }
                        }
                    }
                }
            } }
        }
        if (s.imagesLoading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp); Text("正在处理图片…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (s.draftImages.isNotEmpty()) Text("待发送 ${s.draftImages.size}/4 · 点击预览，可只发图片", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.imageError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 3) }
    }
    preview?.let { image ->
        val result by produceState<Result<Bitmap>?>(null, image.path) { value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(image.path) ?: error("图片不可用，请重新选择") } } }
        val value = result
        if (value == null) Dialog(onDismissRequest = { preview = null }) { Surface(shape = RoundedCornerShape(16.dp)) { CircularProgressIndicator(Modifier.padding(24.dp)) } }
        else value.getOrNull()?.let { ImageViewer(it, image.name) { preview = null } }
            ?: AlertDialog(onDismissRequest = { preview = null }, title = { Text("无法预览图片") }, text = { Text(value.exceptionOrNull()?.message.orEmpty()) }, confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } })
    }
}
