package dev.codexpocket.core

import kotlinx.serialization.Serializable

@Serializable data class ReviewOverview(
    val root: String, val repository: String, val branch: String, val head: String = "",
    val changes: List<GitChange> = emptyList(), val fingerprints: Map<String, String> = emptyMap(), val capturedAt: Long = 0,
)
@Serializable data class ReviewBaseline(val token: String, val root: String, val partial: Boolean = false)
@Serializable data class TaskReviewReport(
    val token: String, val root: String, val repository: String, val branch: String,
    val changes: List<GitChange> = emptyList(), val diffs: Map<String, String> = emptyMap(),
    val capturedAt: Long = 0, val partial: Boolean = false,
)

val GitChange.staged: Boolean get() = status.firstOrNull()?.let { it != ' ' && it != '?' } == true
val GitChange.working: Boolean get() = status.getOrNull(1)?.let { it != ' ' } == true
fun changeLabel(status: String): String = when {
    status.contains('U') || status in setOf("AA", "DD") -> "冲突"
    status == "??" -> "新增"
    status.contains('R') -> "重命名"
    status.contains('D') -> "删除"
    status.contains('A') -> "新增"
    status.contains('T') -> "类型变化"
    else -> "修改"
}
fun reviewViewKey(scope: String, token: String?, path: String, side: String) = "$scope:${token.orEmpty()}:$side:$path"
fun changeViewed(change: GitChange, scope: String, token: String?, viewed: Map<String, String>): Boolean {
    val sides = if (scope == "task") listOf("all") else listOfNotNull("staged".takeIf { change.staged }, "working".takeIf { change.working })
    return change.fingerprint.isNotBlank() && sides.isNotEmpty() && sides.all { viewed[reviewViewKey(scope, token, change.path, it)] == change.fingerprint }
}
fun commentLocationValid(comment: ReviewComment, fingerprint: String?, freshDiff: String? = null): Boolean {
    if (comment.stale) return false
    if (comment.fingerprint.isNotBlank()) return fingerprint != null && comment.fingerprint == fingerprint
    if (comment.oldLine == null && comment.newLine == null) return fingerprint != null
    return freshDiff != null && comment.excerpt.isNotBlank() && diffLines(freshDiff).any {
        it.section == comment.section && it.oldLine == comment.oldLine && it.newLine == comment.newLine && it.text == comment.excerpt
    }
}
fun currentFileDiff(path: String, text: String): String {
    val lines = text.removeSuffix("\n").lines()
    return "当前文件\n--- a/$path\n+++ b/$path\n@@ -1,${lines.size} +1,${lines.size} @@\n" + lines.joinToString("\n") { " $it" }
}
fun reviewDiffSide(diff: String, side: String): String = when (side) {
    "staged" -> diffLines(diff).filter { it.section == "已暂存" }.joinToString("\n") { it.text }
    "working" -> diffLines(diff).filter { it.section != "已暂存" }.joinToString("\n") { it.text }
    else -> diff
}.ifBlank { "此范围没有文本差异。" }

sealed interface ReviewTarget {
    data object UncommittedChanges : ReviewTarget
    data class Branch(val name: String) : ReviewTarget
    data class Commit(val sha: String) : ReviewTarget
}
