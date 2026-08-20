package com.dsh.client.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.client.domain.model.ToolCall
import com.dsh.client.domain.model.ToolCallStatus
import com.dsh.client.ui.theme.*
import kotlinx.serialization.json.Json

@Composable
fun ToolCallCard(toolCall: ToolCall) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, top = 2.dp, bottom = 2.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = ToolCallBg
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Status icon
                StatusIcon(toolCall.status)
                Spacer(Modifier.width(8.dp))
                // Tool name
                Text(
                    text = toolCall.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Expand/collapse
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Expandable content
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    // Arguments
                    Text(
                        text = "参数",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                    Spacer(Modifier.height(4.dp))
                    CodeBlock(
                        text = try {
                            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(toolCall.arguments)
                            kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
                                kotlinx.serialization.json.JsonElement.serializer(), parsed
                            )
                        } catch (_: Exception) {
                            toolCall.arguments
                        }
                    )

                    // Result (if available)
                    if (toolCall.result != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "结果",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (toolCall.status == ToolCallStatus.Error) AccentRed else TextTertiary
                        )
                        Spacer(Modifier.height(4.dp))
                        CodeBlock(text = toolCall.result)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: ToolCallStatus) {
    val (icon, tint) = when (status) {
        ToolCallStatus.Pending -> Icons.Outlined.HourglassEmpty to TextSecondary
        ToolCallStatus.Running -> Icons.Outlined.Sync to AccentBlue
        ToolCallStatus.Success -> Icons.Filled.CheckCircle to AccentGreen
        ToolCallStatus.Error -> Icons.Filled.Error to AccentRed
    }
    Icon(
        imageVector = icon,
        contentDescription = status.name,
        tint = tint,
        modifier = Modifier.size(18.dp)
    )
}

@Composable
private fun CodeBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF141428))
            .padding(8.dp)
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            ),
            maxLines = 15,
            overflow = TextOverflow.Ellipsis
        )
    }
}
