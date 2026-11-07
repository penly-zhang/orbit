package dev.codexpocket

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable internal fun CloseSessionDialog(tab: SessionTab?, dismiss: () -> Unit, confirm: (SessionTab) -> Unit) {
    tab ?: return
    AlertDialog(onDismissRequest = dismiss, title = { Text("关闭标签 ${tab.label}？") },
        text = { Text("这个标签还有未发送的内容或待发消息。关闭将清除草稿，电脑上的任务继续运行。") },
        confirmButton = { TextButton(onClick = { confirm(tab) }) { Text("清除草稿并关闭") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("保留标签") } })
}
