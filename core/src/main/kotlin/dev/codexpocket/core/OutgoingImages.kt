package dev.codexpocket.core

import kotlinx.serialization.json.JsonObject
import java.util.Base64

const val MAX_OUTGOING_IMAGES = 4
const val MAX_OUTGOING_IMAGE_BYTES = 2 * 1024 * 1024
const val MAX_OUTGOING_TOTAL_BYTES = MAX_OUTGOING_IMAGES * MAX_OUTGOING_IMAGE_BYTES

data class OutgoingImage(val bytes: ByteArray)

/** Images travel as native image inputs, never as phone-local paths or Markdown. */
fun validateOutgoingImages(text: String, images: List<OutgoingImage> = emptyList()): List<String> {
    require(text.isNotBlank() || images.isNotEmpty()) { "请输入文字或选择图片" }
    require(images.size <= MAX_OUTGOING_IMAGES) { "每条消息最多发送 4 张图片" }
    require(images.sumOf { it.bytes.size.toLong() } <= MAX_OUTGOING_TOTAL_BYTES) { "图片总大小超过 8 MB" }
    val mimes = images.map { image ->
        val bytes = image.bytes
        require(bytes.isNotEmpty() && bytes.size <= MAX_OUTGOING_IMAGE_BYTES) { "单张图片需在 2 MB 以内" }
        val mime = when {
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
            bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> error("图片格式不支持，请重新选择图片")
        }
        mime
    }
    require(text.toByteArray().size.toLong() + images.sumOf { (it.bytes.size.toLong() + 2) / 3 * 4 + 64 } <= 12L * 1024 * 1024) { "消息过大，请减少文字或图片" }
    return mimes
}
fun outgoingUserInput(text: String, images: List<OutgoingImage> = emptyList()): List<JsonObject> {
    val mimes = validateOutgoingImages(text, images)
    return buildList {
        if (text.isNotBlank()) add(obj("type" to "text", "text" to text))
        images.forEachIndexed { index, image -> add(obj("type" to "image", "url" to "data:${mimes[index]};base64,${Base64.getEncoder().encodeToString(image.bytes)}")) }
    }
}
