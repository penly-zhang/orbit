package dev.codexpocket.core

/** Resolve a working Git on the final SSH target without changing PATH or xcode-select. */
internal fun gitReadCommand(arguments: List<String>, fallbacks: List<String> = listOf(
    "/opt/homebrew/bin/git", "/usr/local/bin/git", "/usr/bin/git", "/bin/git",
    "/Library/Developer/CommandLineTools/usr/bin/git", "/Applications/Xcode.app/Contents/Developer/usr/bin/git",
)): String = withWorkingGit("exec \"\$pocket_candidate\" ${arguments.joinToString(" ", transform = ::shellQuote)}", fallbacks)

internal fun withWorkingGit(run: String, fallbacks: List<String> = listOf(
    "/opt/homebrew/bin/git", "/usr/local/bin/git", "/usr/bin/git", "/bin/git",
    "/Library/Developer/CommandLineTools/usr/bin/git", "/Applications/Xcode.app/Contents/Developer/usr/bin/git",
)): String = listOf(
    "pocket_git=\$(command -v git 2>/dev/null || true);",
    "for pocket_candidate in \"\$pocket_git\" ${fallbacks.joinToString(" ", transform = ::shellQuote)}; do",
    "if [ -n \"\$pocket_candidate\" ] && [ -x \"\$pocket_candidate\" ]; then",
    "if pocket_version=\$(\"\$pocket_candidate\" --version 2>/dev/null); then",
    "case \"\$pocket_version\" in 'git version '*) GIT_OPTIONAL_LOCKS=0; export GIT_OPTIONAL_LOCKS; $run ;; esac;",
    "fi; fi; done;",
    "printf '%s\\n' ${shellQuote("电脑上没有可运行的 Git。请修复 Command Line Tools，或安装 Git 后重试。已检查 PATH 和常见安装位置。") } >&2; exit 127",
).joinToString(" ")

fun gitFailureMessage(details: String): String = when {
    details.contains("not a git repository", ignoreCase = true) -> "当前目录不是 Git 仓库，请从文件中进入具体仓库后查看变更。"
    details.contains("xcrun", ignoreCase = true) || details.contains("invalid active developer path", ignoreCase = true) -> "电脑的开发工具路径无效，Git 无法运行。请修复 Command Line Tools，或安装 Git 后重试。"
    else -> details.trim().take(1000).ifBlank { "无法读取 Git 变更，请检查电脑上的仓库和 Git。" }
}


fun parseGitStatus(output: String): List<GitChange> {
    val parts = output.split('\u0000')
    val changes = mutableListOf<GitChange>()
    var index = 0
    while (index < parts.size) {
        val part = parts[index++]
        if (part.length < 4) continue
        val status = part.take(2)
        val path = part.substring(3)
        val old = if (status.contains('R') || status.contains('C')) parts.getOrNull(index++) else null
        changes += GitChange(path, old, status)
    }
    return changes
}
