package dev.codexpocket

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable internal fun CloseSessionDialog(tab: SessionTab?, dismiss: () -> Unit, confirm: (SessionTab) -> Unit) {
    tab ?: return
    AlertDialog(onDismissRequest = dismiss, title = { Text("关闭标签 ${tab.label}？") },
        text = { Text("这个标签还有未发送的内容或待发消息。关闭将清除未发送的内容；已关注的任务仍在任务中心跟踪。") },
        confirmButton = { TextButton(onClick = { confirm(tab) }) { Text("清除草稿并关闭") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("保留标签") } })
}
