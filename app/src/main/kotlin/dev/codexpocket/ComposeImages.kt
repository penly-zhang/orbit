package dev.codexpocket

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import dev.codexpocket.core.MAX_OUTGOING_IMAGE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** The picker grants access; make a bounded, oriented private copy before the grant expires. */
internal suspend fun prepareDraftImage(context: Context, uri: Uri): DraftImage {
    var created: File? = null
    return try { withContext(Dispatchers.IO) {
        require(uri.scheme in setOf("content", "file")) { "不支持此图片来源" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                ensureActive(); val count = input.read(buffer); if (count < 0) break
                require(out.size() + count <= 20 * 1024 * 1024) { "原图超过 20 MB，请先缩小图片" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: error("无法打开图片，请重新选择")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 120_000_000L) { "图片无法解码或分辨率过大" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("图片解码失败")
        var oriented: Bitmap = decoded
        var flattened: Bitmap? = null
        try {
            ensureActive()
            val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply { when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            } }
            if (!matrix.isIdentity) oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val canvasBitmap = Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
            flattened = canvasBitmap
            Canvas(canvasBitmap).apply { drawColor(Color.WHITE); drawBitmap(oriented, 0f, 0f, null) }
            val encoded = ByteArrayOutputStream()
            for (quality in listOf(90, 80, 70, 60, 50)) {
                ensureActive(); encoded.reset()
                require(canvasBitmap.compress(Bitmap.CompressFormat.JPEG, quality, encoded)) { "图片压缩失败" }
                if (encoded.size() <= MAX_OUTGOING_IMAGE_BYTES) break
            }
            require(encoded.size() in 1..MAX_OUTGOING_IMAGE_BYTES) { "图片压缩后仍超过 2 MB，请换一张较小的图片" }
            val directory = File(context.filesDir, "composer-images").apply { require(isDirectory || mkdirs()) { "无法保存图片草稿" } }
            val id = UUID.randomUUID().toString()
            val file = File(directory, "$id.jpg"); created = file
            file.outputStream().use { encoded.writeTo(it) }
            val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } }.getOrNull()
                ?.filterNot(Char::isISOControl)?.take(80)?.takeIf(String::isNotBlank) ?: "图片.jpg"
            DraftImage(id, file.absolutePath, name, encoded.size())
        } finally {
            flattened?.recycle()
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
    } } catch (error: Exception) { created?.delete(); throw error }
}
