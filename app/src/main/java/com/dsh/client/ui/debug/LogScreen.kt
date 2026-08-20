package com.dsh.client.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.client.data.debug.DebugLog
import com.dsh.client.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onBack: () -> Unit
) {
    val entries = remember { DebugLog.snapshot() }
    var showRaw by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("调试日志", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { DebugLog.clear() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空")
                    }
                    IconButton(onClick = { showRaw = !showRaw }) {
                        Icon(if (showRaw) Icons.Filled.List else Icons.Filled.Code, contentDescription = "切换视图")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 过滤器
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("过滤 (tag: 关键字)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )

            val filtered = if (filter.isBlank()) entries else {
                val kw = filter.lowercase()
                entries.filter { it.formatted().lowercase().contains(kw) }
            }

            if (showRaw) {
                // 纯文本视图（可复制）
                val text = remember(filtered) { filtered.joinToString("\n") { it.formatted() } }
                TextField(
                    value = text,
                    onValueChange = {},
                    readOnly = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        color = Color(0xFFE8E8F0), lineHeight = 14.sp
                    ),
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F0F1A)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0F0F1A),
                        unfocusedContainerColor = Color(0xFF0F0F1A),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    )
                )
            } else {
                // 列表视图
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(filtered, key = { "${it.timestamp}${it.message}" }) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: DebugLog.Entry) {
    val bgColor = when (entry.level) {
        DebugLog.Level.ERROR -> AccentRed.copy(alpha = 0.08f)
        DebugLog.Level.WARN -> AccentOrange.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val levelColor = when (entry.level) {
        DebugLog.Level.ERROR -> AccentRed
        DebugLog.Level.WARN -> AccentOrange
        DebugLog.Level.INFO -> AccentGreen
        DebugLog.Level.DEBUG -> TextTertiary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        // 级别
        Text(
            text = entry.level.tag,
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp)
        )
        // 时间
        Text(
            text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(entry.timestamp)),
            color = TextTertiary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.width(60.dp)
        )
        // 标签
        Text(
            text = entry.tag,
            color = AccentBlue,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(64.dp)
        )
        // 消息
        Text(
            text = entry.message,
            color = if (entry.level == DebugLog.Level.ERROR) AccentRed else TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
