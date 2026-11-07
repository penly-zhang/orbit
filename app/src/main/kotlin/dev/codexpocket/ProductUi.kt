package dev.codexpocket

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One shell for long forms, inventories and code context; actions stay above the keyboard. */
@Composable internal fun FullPage(title: String, close: () -> Unit, bottom: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
        DialogBars()
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.Outlined.ArrowBack, "返回$title") }
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                bottom()
            }
        }
        }
    }
}

@Composable internal fun DialogBars() {
    val view = LocalView.current
    val light = Paper.luminance() > 0.5f
    DisposableEffect(view, light) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        }
        onDispose { }
    }
}

@Composable internal fun ActionRow(title: String, icon: ImageVector, enabled: Boolean = true, supporting: String? = null, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = action).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(21.dp), tint = if (enabled) Ink else Muted.copy(alpha = 0.55f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 15.sp, color = if (enabled) Ink else Muted)
            supporting?.let { Text(it, fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
    }
}

@Composable internal fun StateNotice(title: String, detail: String? = null, actionLabel: String? = null, action: () -> Unit = {}, dismiss: (() -> Unit)? = null, failure: Boolean = false, actionEnabled: Boolean = true) {
    var details by remember(title, detail) { mutableStateOf(false) }
    Surface(color = if (failure) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (failure) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, Modifier.size(18.dp), tint = if (failure) MaterialTheme.colorScheme.onErrorContainer else Muted)
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f), color = if (failure) MaterialTheme.colorScheme.onErrorContainer else Ink)
                if (actionLabel != null) TextButton(onClick = action, enabled = actionEnabled) { Text(actionLabel) }
                if (dismiss != null) IconButton(onClick = dismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(18.dp)) }
            }
            detail?.takeIf { it.isNotBlank() }?.let {
                TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) { Text(if (details) "收起详情" else "查看详情", fontSize = 12.sp) }
                if (details) SelectionContainer { Text(it, fontSize = 12.sp, color = Muted, modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) }
            }
        }
    }
}

@Composable internal fun SearchBox(value: String, change: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, change, placeholder = { Text(hint, fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(21.dp)) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { change("") }) { Icon(Icons.Outlined.Close, "清除搜索", Modifier.size(18.dp)) } },
        singleLine = true, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant, focusedBorderColor = Mint, unfocusedContainerColor = Panel, focusedContainerColor = Panel))
}

internal fun shortPath(path: String): String = path.trimEnd('/').substringAfterLast('/').ifBlank { "/" }
