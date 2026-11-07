package dev.codexpocket.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.nio.file.Paths

/** Host services shared by adapters; no agent protocol or conversation state. */
internal class SshWorkspace(private val ssh: SshConnection, remoteFiles: RemoteFiles) :
    WorkspaceRepository, RemoteFiles by remoteFiles {
    override suspend fun webTunnel(port: Int) = ssh.openWebTunnel(port)
    override suspend fun artifactResource(root: String, encodedPath: String): FilePreview {
        val path = artifactResourcePath(root, encodedPath)
        val parts = Paths.get(root).normalize().relativize(Paths.get(path)).toList()
        var current = Paths.get(root).normalize()
        val check = parts.joinToString(" && ") { part -> current = current.resolve(part); "[ ! -L ${shellQuote(current.toString())} ]" }
        require(ssh.execute(check, 4096).code == 0) { "不允许读取符号链接预览资源" }
        return file(path, MAX_CHAT_IMAGE_BYTES)
    }
    override suspend fun image(source: String, cwd: String): ByteArray {
        if (isLoopbackLink(source)) return ssh.openWebTunnel(loopbackPort(source)).use { tunnel -> readChatImage(tunneledUrl(source, tunnel.port), cwd) { error("网络图片地址无效") } }
        return readChatImage(source, cwd) { path -> file(path, MAX_CHAT_IMAGE_BYTES).bytes }
    }
    override suspend fun changes(root: String): List<GitChange> {
        val result = ssh.execute(gitReadCommand(listOf("-C", root, "status", "--porcelain=v1", "-z", "--untracked-files=normal")))
        require(result.code == 0) { gitFailureMessage(result.stderr) }
        val prefixResult = ssh.execute(gitReadCommand(listOf("-C", root, "rev-parse", "--show-prefix")))
        require(prefixResult.code == 0) { gitFailureMessage(prefixResult.stderr) }
        val prefix = prefixResult.stdout.trimEnd('\n', '\r')
        fun relative(path: String) = if (prefix.isBlank()) path else Paths.get(prefix).relativize(Paths.get(path)).toString()
        return parseGitStatus(result.stdout).map { it.copy(path = relative(it.path), oldPath = it.oldPath?.let(::relative)) }
    }
    private suspend fun reviewResult(root: String, action: String, token: String = "", paths: List<String> = emptyList()): String {
        val result = ssh.execute(gitReviewCommand(root, action, token, paths), 3 * 1024 * 1024)
        val value = runCatching { json.parseToJsonElement(result.stdout).jsonObject }.getOrNull()
        check(result.code == 0 && value?.get("error") == null) { gitFailureMessage(value?.text("error")?.takeIf(String::isNotBlank) ?: result.stderr) }
        return result.stdout
    }
    override suspend fun reviewOverview(root: String, paths: List<String>): ReviewOverview =
        json.decodeFromString<ReviewOverview>(reviewResult(root, "overview", paths = paths)).copy(root = root)
    override suspend fun beginReviewBaseline(root: String): ReviewBaseline =
        json.decodeFromString<ReviewBaseline>(reviewResult(root, "begin")).copy(root = root)
    override suspend fun taskReview(baseline: ReviewBaseline, finish: Boolean): TaskReviewReport =
        json.decodeFromString<TaskReviewReport>(reviewResult(baseline.root, if (finish) "finish" else "compare", baseline.token)).copy(root = baseline.root)
    override suspend fun savedTaskReview(root: String, token: String): TaskReviewReport =
        json.decodeFromString<TaskReviewReport>(reviewResult(root, "read", token)).copy(root = root)
    override suspend fun validateReviewComments(comments: List<ReviewComment>): List<ReviewComment> {
        require(comments.isNotEmpty() && comments.map { it.root }.distinct().size == 1)
        val root = comments.first().root
        val overview = reviewOverview(root, comments.map { it.path }.distinct())
        val diffs = mutableMapOf<String, String>()
        return comments.filterNot { comment ->
            val fresh = if (comment.fingerprint.isBlank() && (comment.oldLine != null || comment.newLine != null)) {
                if (comment.path !in diffs) diffs[comment.path] = overview.changes.find { it.path == comment.path }?.let { diff(root, it) }.orEmpty()
                diffs[comment.path]
            } else null
            commentLocationValid(comment, overview.fingerprints[comment.path], fresh)
        }
    }
    override suspend fun reviewFile(root: String, path: String): Triple<String, String, ReviewOverview> {
        val overview = reviewOverview(root, listOf(path))
        val change = overview.changes.find { it.path == path }
        val text = if (change?.binary == true) "二进制文件发生变化，仅显示变更摘要。" else if (change != null && change.added == null) "该文件不提供文本差异，仅显示变更摘要。" else if (change != null) diff(root, change) else currentFileDiff(path, file(root.trimEnd('/') + "/" + path).bytes.toString(Charsets.UTF_8))
        return Triple(text, overview.fingerprints[path].orEmpty(), overview)
    }
    override suspend fun diff(root: String, change: GitChange): String {
        if (change.status == "??") return untrackedDiff(change.path, file(root.trimEnd('/') + "/" + change.path).bytes)
        val paths = listOfNotNull(change.oldPath, change.path)
        val base = listOf("--literal-pathspecs", "--no-pager", "-C", root, "diff", "--no-ext-diff", "--no-textconv", "--no-color")
        val staged = ssh.execute(gitReadCommand(base + "--cached" + "--" + paths))
        val working = ssh.execute(gitReadCommand(base + "--" + paths))
        require(staged.code == 0 && working.code == 0) { gitFailureMessage(staged.stderr + working.stderr) }
        return listOf("已暂存\n${staged.stdout}".takeIf { staged.stdout.isNotBlank() }, "工作区\n${working.stdout}".takeIf { working.stdout.isNotBlank() }).filterNotNull().joinToString("\n\n").ifBlank { "没有文本差异（文件可能是二进制文件、重命名或权限变化）" }
    }
}
