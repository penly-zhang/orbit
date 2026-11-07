package dev.codexpocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexpocket.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream

internal val LocalOpenProjectFile = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Bounded in-memory decoded thumbnails; the encrypted history stores references only. */
internal class ConversationImageLoader(private val read: suspend (String, String) -> ByteArray) {
    private val cache = object : LruCache<Pair<String, String>, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Pair<String, String>, value: Bitmap) = value.allocationByteCount
    }
    private val permits = Semaphore(2)
    @Volatile private var generation = 0
    fun clear() { generation++; cache.evictAll() }
    suspend fun load(image: ChatImage, cwd: String): Bitmap {
        val key = cwd to image.source
        cache.get(key)?.let { return it }
        val current = generation
        return permits.withPermit {
            cache.get(key)?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                val bytes = read(image.source, cwd)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "文件不是可显示的图片，或格式暂不支持" }
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: error("图片解码失败")
                val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                    }
                }
                val bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    .also { if (it !== decoded) decoded.recycle() }
                ensureActive()
                if (current == generation) cache.put(key, bitmap)
                bitmap
            }
        }
    }
}

@Composable internal fun ConversationImage(image: ChatImage, cwd: String, session: String, loader: ConversationImageLoader) {
    if (isRemoteFileLink(image.source) && image.source.substringBefore('?').endsWith(".svg", true)) { LinkedDocument(image.source, image.label); return }
    val openSource = LocalOpenProjectFile.current
    var retry by remember(image.source, cwd, session) { mutableIntStateOf(0) }
    var fullscreen by rememberSaveable(image.source.hashCode(), cwd, session) { mutableStateOf(false) }
    val result by produceState<Result<Bitmap>?>(null, image.source, cwd, session, retry) {
        value = null
        try { value = Result.success(loader.load(image, cwd)) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { value = Result.failure(error) }
    }
    val bitmap = result?.getOrNull()
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        if (bitmap != null) Box(Modifier.fillMaxWidth().clickable(onClickLabel = "放大图片") { fullscreen = true }) {
            Image(bitmap.asImageBitmap(), image.label, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio((bitmap.width.toFloat() / bitmap.height).coerceIn(0.65f, 2.2f)))
            Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(8.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ZoomIn, null, Modifier.size(15.dp), tint = Color.White)
                    Text("查看图片", color = Color.White, fontSize = 12.sp)
                }
            }
        } else Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (result == null) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("正在加载图片…", fontSize = 12.sp)
            } else {
                Icon(Icons.Outlined.BrokenImage, null)
                Text(result?.exceptionOrNull()?.message ?: "图片加载失败", fontSize = 12.sp)
                Row { TextButton(onClick = { retry++ }) { Text("重新加载") }; if (openSource != null) TextButton(onClick = { openSource(image.source) }) { Text("打开原链接") } }
            }
        }
    }
    if (fullscreen && bitmap != null) ImageViewer(bitmap, image.label) { fullscreen = false }
}

@Composable internal fun ImageViewer(bitmap: Bitmap, label: String, onClose: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭图片", tint = Color.White) }
                Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { zoom = 1f; offset = Offset.Zero }) { Text("还原", color = Color.White) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp))) {
                val width = constraints.maxWidth.toFloat()
                val height = constraints.maxHeight.toFloat()
                Image(bitmap.asImageBitmap(), label, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(bitmap) {
                        detectTransformGestures { _, pan, scale, _ ->
                            zoom = (zoom * scale).coerceIn(1f, 6f)
                            val xLimit = width * (zoom - 1f) / 2
                            val yLimit = height * (zoom - 1f) / 2
                            offset = Offset((offset.x + pan.x).coerceIn(-xLimit, xLimit), (offset.y + pan.y).coerceIn(-yLimit, yLimit))
                        }
                    }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y })
            }
            Text("双指缩放 · 拖动查看", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
        }
    }
}

@Composable internal fun ConversationBody(text: String, images: List<ChatImage>, cwd: String, session: String, loader: ConversationImageLoader, markdown: Boolean = true, expandArtifacts: Boolean = true) {
    val parts = remember(text) { if (markdown) messageContent(text) else listOf(MessageContent.Text(text)) }
    val inlineSources = remember(parts) { parts.filterIsInstance<MessageContent.Image>().map { it.image.source }.toSet() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.forEach { part -> when (part) {
            is MessageContent.Text -> if (part.text.isNotBlank()) {
                if (markdown) Markdown(part.text.trim())
                else androidx.compose.foundation.text.selection.SelectionContainer { Text(part.text, fontSize = 16.sp, lineHeight = 27.sp) }
            }
            is MessageContent.Image -> ConversationImage(part.image, cwd, session, loader)
            is MessageContent.Document -> DocumentCard(part.source, part.format, LocalArtifactContext.current)
            is MessageContent.Artifact -> if (expandArtifacts) LinkedDocument(part.link, part.label) else Markdown("[${part.label}](${part.link})")
        } }
        images.filterNot { it.source in inlineSources }.forEach { image -> ConversationImage(image, cwd, session, loader) }
    }
}
