package com.dsh.client.ui.sessionlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.client.domain.model.SessionSummary
import com.dsh.client.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    onSessionClick: (String) -> Unit,
    onNewSession: (String) -> Unit,
    viewModel: SessionListViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var renameDialogSessionId by remember { mutableStateOf<String?>(null) }

    val apiProvider = remember { com.dsh.client.DshApp.api }
    apiProvider?.let { viewModel.setApi(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("DSH", fontWeight = FontWeight.Bold)
                        if (uiState.isConnected) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Online)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.createSession { id -> onNewSession(id) } },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新建会话")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.sessions.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                uiState.error != null && uiState.sessions.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("无法连接", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            uiState.error ?: "检查服务器地址",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadSessions() }) {
                            Text("重试")
                        }
                    }
                }
                uiState.sessions.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Outlined.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("暂无会话", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "点击右下角 + 新建对话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.sessions, key = { it.sessionId }) { session ->
                            SwipeableSessionCard(
                                session = session,
                                isPinned = viewModel.isPinned(session.sessionId),
                                onClick = { onSessionClick(session.sessionId) },
                                onDelete = { viewModel.hideSession(session.sessionId) },
                                onPin = { viewModel.togglePinSession(session.sessionId) },
                                onRename = { renameDialogSessionId = session.sessionId }
                            )
                        }
                    }
                }
            }
        }
    }

    // Rename dialog
    if (renameDialogSessionId != null) {
        RenameDialog(
            sessionId = renameDialogSessionId!!,
            onRename = { newTitle ->
                viewModel.renameSession(renameDialogSessionId!!, newTitle)
                renameDialogSessionId = null
            },
            onDismiss = { renameDialogSessionId = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableSessionCard(
    session: SessionSummary,
    isPinned: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else if (value == SwipeToDismissBoxValue.StartToEnd) {
                onPin()
                false // Don't fully dismiss, just toggle pin
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> AccentRed.copy(alpha = 0.8f)
                    SwipeToDismissBoxValue.StartToEnd -> AccentBlue.copy(alpha = 0.8f)
                    else -> Color.Transparent
                },
                label = "swipeBg"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    else -> Alignment.Center
                }
            ) {
                Icon(
                    imageVector = when (dismissState.targetValue) {
                        SwipeToDismissBoxValue.EndToStart -> Icons.Filled.Delete
                        SwipeToDismissBoxValue.StartToEnd -> if (isPinned) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder
                        else -> Icons.Filled.Delete
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
    ) {
        // Context menu
        Box {
            SessionCard(
                session = session,
                isPinned = isPinned,
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (isPinned) "取消置顶" else "置顶") },
                    onClick = { showMenu = false; onPin() },
                    leadingIcon = {
                        Icon(if (isPinned) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = { showMenu = false; onRename() },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }
                )
                Divider()
                DropdownMenuItem(
                    text = { Text("删除", color = AccentRed) },
                    onClick = { showMenu = false; onDelete() },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = AccentRed) }
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SessionCard(
    session: SessionSummary,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isPinned) AccentBlue.copy(alpha = 0.25f) else AccentBlue.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                if (isPinned) {
                    Icon(
                        Icons.Filled.Bookmark,
                        contentDescription = "已置顶",
                        tint = AccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        Icons.Outlined.SmartToy,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifEmpty { "新对话" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (session.lastMessagePreview != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = session.lastMessagePreview!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.agentPreset ?: "默认",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatTime(session.updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun RenameDialog(
    sessionId: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newTitle by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text("新名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newTitle.isNotBlank()) onRename(newTitle.trim())
                },
                enabled = newTitle.isNotBlank()
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private val dateFormat = java.text.SimpleDateFormat("MM/dd", java.util.Locale.getDefault())

fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        diff < 604_800_000 -> "${diff / 86_400_000}天前"
        else -> dateFormat.format(java.util.Date(timestamp))
    }
}
