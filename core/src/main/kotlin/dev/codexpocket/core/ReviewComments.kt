package dev.codexpocket.core

import kotlinx.serialization.Serializable
import java.util.UUID

enum class DiffKind { META, HUNK, CONTEXT, ADDED, REMOVED }
data class DiffLine(val index: Int, val text: String, val section: String, val oldLine: Int?, val newLine: Int?, val kind: DiffKind) {
    val commentable get() = oldLine != null || newLine != null
}
@Serializable data class ReviewComment(
    val id: String = UUID.randomUUID().toString(), val root: String, val path: String,
    val section: String, val oldLine: Int? = null, val newLine: Int? = null,
    val excerpt: String = "", val body: String,
    val fingerprint: String = "", val stale: Boolean = false,
)

fun diffLines(diff: String): List<DiffLine> {
    var section = "工作区"
    var old: Int? = null
    var new: Int? = null
    val hunk = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")
    return diff.lines().mapIndexed { index, text ->
        val header = hunk.find(text)
        val kind = when {
            text in setOf("已暂存", "工作区", "新增文件", "本轮期间", "当前文件") -> { section = text; old = null; new = null; DiffKind.META }
            text.startsWith("diff --git ") || ((old == null || new == null) && (text.startsWith("--- ") || text.startsWith("+++ "))) -> { old = null; new = null; DiffKind.META }
            header != null -> { old = header.groupValues[1].toInt(); new = header.groupValues[2].toInt(); DiffKind.HUNK }
            old == null || new == null -> DiffKind.META
            text.startsWith('+') -> DiffKind.ADDED
            text.startsWith('-') -> DiffKind.REMOVED
            text.startsWith(' ') -> DiffKind.CONTEXT
            else -> DiffKind.META
        }
        val left = old.takeIf { kind == DiffKind.REMOVED || kind == DiffKind.CONTEXT }
        val right = new.takeIf { kind == DiffKind.ADDED || kind == DiffKind.CONTEXT }
        if (left != null) old = left + 1
        if (right != null) new = right + 1
        DiffLine(index, text, section, left, right, kind)
    }
}

fun untrackedDiff(path: String, bytes: ByteArray): String {
    if (bytes.take(8192).contains(0.toByte())) return "新增文件\n二进制文件：$path"
    val text = bytes.toString(Charsets.UTF_8)
    if (text.isEmpty()) return "新增文件\n空文件：$path"
    val lines = text.removeSuffix("\n").split('\n')
    return "新增文件\n--- /dev/null\n+++ b/$path\n@@ -0,0 +1,${lines.size} @@\n" + lines.joinToString("\n") { "+$it" } +
        if (text.endsWith('\n')) "\n" else "\n\\ No newline at end of file\n"
}

fun reviewFeedback(comments: List<ReviewComment>): String {
    require(comments.isNotEmpty() && comments.map { it.root }.distinct().size == 1) { "请选择同一工作目录中的评论" }
    return comments.joinToString("\n\n") { comment ->
        val location = when {
            comment.newLine != null -> "${comment.path}:${comment.newLine}"
            comment.oldLine != null -> "${comment.path}:${comment.oldLine}（旧行）"
            else -> comment.path
        }
        "$location\n${comment.body}"
    }
}
