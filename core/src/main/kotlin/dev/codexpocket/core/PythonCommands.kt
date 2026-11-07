package dev.codexpocket.core

/** Resolve a usable interpreter on the final SSH host, including broken macOS tool stubs. */
internal fun pythonCommand(arguments: List<String>): String =
    withWorkingPython(arguments.joinToString(" ", transform = ::shellQuote))

internal fun withWorkingPython(arguments: String, fallbacks: List<String> = listOf(
    "/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3", "/bin/python3",
)): String = listOf(
    "pocket_python=\$(command -v python3 2>/dev/null || true);",
    "for pocket_python_candidate in \"\$pocket_python\" ${fallbacks.joinToString(" ", transform = ::shellQuote)}; do",
    "if [ -n \"\$pocket_python_candidate\" ] && [ -x \"\$pocket_python_candidate\" ]; then",
    "if \"\$pocket_python_candidate\" -c ${shellQuote("import sys; sys.exit(0 if sys.version_info >= (3, 7) else 1)")} >/dev/null 2>&1; then",
    "exec \"\$pocket_python_candidate\" $arguments;",
    "fi; fi; done;",
    "printf '%s\\n' ${shellQuote("电脑上没有可运行的 Python 3.7 或以上版本；请安装后重试。已检查 PATH 和常见安装位置。")} >&2; exit 127",
).joinToString(" ")
