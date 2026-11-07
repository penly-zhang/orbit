package dev.codexpocket.core

import org.junit.Test
import kotlin.test.*

class ReviewCommentsTest {
    @Test fun `unified diff tracks staged and working line numbers including deleted lines`() {
        val diff = "已暂存\ndiff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n@@ -10,3 +10,3 @@\n context\n-old\n+new\n tail\n\n工作区\ndiff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n@@ -20,2 +20,2 @@\n--- tricky deletion\n+++ tricky addition\n end\n\\ No newline at end of file\n"
        val lines = diffLines(diff)
        val removed = lines.first { it.text == "-old" }
        assertEquals(11, removed.oldLine); assertNull(removed.newLine); assertEquals("已暂存", removed.section)
        val added = lines.first { it.text == "+new" }
        assertEquals(11, added.newLine); assertNull(added.oldLine)
        assertEquals(20, lines.first { it.text == "--- tricky deletion" }.oldLine)
        assertEquals(20, lines.first { it.text == "+++ tricky addition" }.newLine)
        assertEquals("工作区", lines.first { it.text == "+++ tricky addition" }.section)
        assertTrue(lines.filter { it.kind == DiffKind.META || it.kind == DiffKind.HUNK }.none { it.commentable })
    }
    @Test fun `untracked files have real reviewable line numbers and preserve trailing line semantics`() {
        val lines = diffLines(untrackedDiff("新 文件.kt", "one\n\nthree".toByteArray()))
        assertEquals(listOf(1, 2, 3), lines.filter { it.kind == DiffKind.ADDED }.map { it.newLine })
        assertTrue(lines.filter { it.kind == DiffKind.ADDED }.all { it.oldLine == null && it.section == "新增文件" })
        assertTrue(lines.last { it.text.isNotEmpty() }.text.startsWith("\\ No newline"))
        assertTrue(diffLines(untrackedDiff("binary", byteArrayOf(1, 0, 2))).none { it.commentable })
        assertTrue(diffLines(untrackedDiff("empty", byteArrayOf())).none { it.commentable })
    }
    @Test fun `review feedback sends only file line and comment`() {
        val comment = ReviewComment(root = "/project with spaces", path = "新 文件.kt", section = "已暂存", oldLine = 7, excerpt = "-return old", body = "这里需要兼容中文。")
        val feedback = reviewFeedback(listOf(comment))
        assertEquals("新 文件.kt:7（旧行）\n这里需要兼容中文。", feedback)
        assertEquals("新 文件.kt:12\n这里需要兼容中文。", reviewFeedback(listOf(comment.copy(oldLine = 7, newLine = 12))))
        assertEquals("新 文件.kt\n这里需要兼容中文。", reviewFeedback(listOf(comment.copy(oldLine = null))))
        assertFailsWith<IllegalArgumentException> { reviewFeedback(emptyList()) }
        assertFailsWith<IllegalArgumentException> { reviewFeedback(listOf(comment, comment.copy(root = "/another-project"))) }
    }
}
