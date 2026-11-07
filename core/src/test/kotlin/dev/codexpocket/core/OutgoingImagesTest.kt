package dev.codexpocket.core

import org.junit.Test
import kotlin.test.*
import java.util.Base64

class OutgoingImagesTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4XcAAAAASUVORK5CYII=")
    @Test fun `image only and mixed messages contain real image input with preserved bytes`() {
        val input = outgoingUserInput("检查这张图", listOf(OutgoingImage(png)))
        assertEquals(listOf("text", "image"), input.map { it.text("type") })
        assertEquals("检查这张图", input.first().text("text"))
        val url = input.last().text("url")
        assertTrue(url.startsWith("data:image/png;base64,"))
        assertContentEquals(png, Base64.getDecoder().decode(url.substringAfter(',')))
        val only = outgoingUserInput("", listOf(OutgoingImage(png)))
        assertEquals("image", only.single().text("type"))
        assertFalse(only.single().containsKey("path"))
    }
    @Test fun `oversized nonimage and empty messages are rejected before transport`() {
        assertFailsWith<IllegalArgumentException> { outgoingUserInput("", emptyList()) }
        assertFailsWith<IllegalArgumentException> { outgoingUserInput("", List(5) { OutgoingImage(png) }) }
        assertFailsWith<IllegalArgumentException> { outgoingUserInput("", listOf(OutgoingImage(ByteArray(MAX_OUTGOING_IMAGE_BYTES + 1)))) }
        assertFails { outgoingUserInput("", listOf(OutgoingImage("not an image".toByteArray()))) }
    }
    @Test fun `model image capabilities respect text only catalogs and older missing values`() {
        assertEquals(listOf("text", "image"), modelOption(obj("model" to "old")).inputModalities)
        assertEquals(listOf("text"), modelOption(obj("model" to "text-only", "inputModalities" to listOf("text"))).inputModalities)
    }
}
