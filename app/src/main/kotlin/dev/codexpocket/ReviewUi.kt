package dev.codexpocket

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.core.*

@Composable internal fun ChangeCounts(changes: List<GitChange>, modifier: Modifier = Modifier) {
    val added = if (changes.any { it.added == null }) null else changes.sumOf { it.added ?: 0 }
    val deleted = if (changes.any { it.deleted == null }) null else changes.sumOf { it.deleted ?: 0 }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("+${added?.toString() ?: "—"}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        Text("−${deleted?.toString() ?: "—"}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}

@Composable internal fun ConversationChanges(s: PocketState, vm: PocketViewModel) {
    if (s.taskReview == null && !s.taskReviewLoading) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
        OutlinedButton(onClick = vm::openTaskChanges, enabled = s.connected && s.taskReview != null,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp), modifier = Modifier.heightIn(min = 36.dp)) {
            if (s.taskReviewLoading) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            else Icon(Icons.Outlined.Difference, null, Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (s.taskReview == null) "正在记录本轮变更…" else "本轮期间 · ${s.taskReview.changes.size} 个文件", fontSize = 12.sp)
            s.taskReview?.let { Spacer(Modifier.width(10.dp)); ChangeCounts(it.changes) }
        }
    }
}

private val syntax = Regex("//.*|#[^\\n]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b(?:fun|val|var|class|object|interface|data|sealed|return|if|else|when|for|while|try|catch|finally|throw|new|const|let|function|async|await|import|from|export|def|in|not|and|or|None|True|False|true|false|null|public|private|internal|override|suspend|package|void|int|boolean|type|struct|enum|switch|case|break|continue)\\b|\\b\\d+(?:\\.\\d+)?\\b")
@Composable internal fun reviewSyntax(text: String, kind: DiffKind): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return remember(text, kind, colors) {
        buildAnnotatedString {
            append(text)
            if (kind in setOf(DiffKind.META, DiffKind.HUNK)) return@buildAnnotatedString
            syntax.findAll(text).forEach { match ->
                val token = match.value
                val color = when {
                    token.startsWith("//") || token.startsWith('#') || token.startsWith("/*") -> colors.onSurfaceVariant
                    token.startsWith('"') || token.startsWith('\'') -> colors.tertiary
                    token.firstOrNull()?.isDigit() == true -> colors.secondary
                    else -> colors.primary
                }
                addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
            }
        }
    }
}
