package com.dsh.client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.client.ui.theme.*

// ── Block-level AST ──────────────────────────────────────────────────────────

private sealed class MdBlock {
    data class Paragraph(val spans: AnnotatedString) : MdBlock()
    data class H1(val spans: AnnotatedString) : MdBlock()
    data class H2(val spans: AnnotatedString) : MdBlock()
    data class H3(val spans: AnnotatedString) : MdBlock()
    data class CodeBlock(val code: String, val language: String?) : MdBlock()
    data class BulletList(val items: List<AnnotatedString>) : MdBlock()
    data class OrderedList(val items: List<AnnotatedString>) : MdBlock()
    data class Blockquote(val spans: AnnotatedString) : MdBlock()
    data object HorizontalRule : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
}

// ── Streaming helper ─────────────────────────────────────────────────────────

private fun countOccurrences(text: String, pattern: String): Int {
    var count = 0
    var i = 0
    while (i <= text.length - pattern.length) {
        if (text.regionMatches(i, pattern, 0, pattern.length)) {
            count++
            i += pattern.length
        } else {
            i++
        }
    }
    return count
}

private fun countInlineMarkers(text: String, regex: Regex): Int {
    return regex.findAll(text).count()
}

/**
 * Check if the given text has unclosed markdown tokens.
 * Used by the streaming buffer to avoid rendering incomplete markdown.
 */
fun hasUnclosedMarkdown(text: String): Boolean {
    val noCode = text.replace(Regex("```[\\s\\S]*?```"), "")
    if (countOccurrences(noCode, "**") % 2 != 0) return true
    if (countOccurrences(noCode, "__") % 2 != 0) return true
    if (countOccurrences(noCode, "~~") % 2 != 0) return true
    if (countOccurrences(noCode, "`") % 2 != 0) return true
    if (countInlineMarkers(noCode, Regex("(?<!\\w)\\*(?!\\*|\\s)")) % 2 != 0) return true
    if (countInlineMarkers(noCode, Regex("(?<!\\w)_(?!_|\\s)")) % 2 != 0) return true
    // Check for unclosed code fences
    var fenceCount = 0
    var i = 0
    while (i < text.length) {
        if (text.startsWith("```", i)) {
            fenceCount++
            i += 3
        } else {
            i++
        }
    }
    return fenceCount % 2 != 0
}

/**
 * Strip trailing unclosed markdown tokens from text for safe rendering.
 */
fun stripUnclosedMarkdown(text: String): String {
    if (!hasUnclosedMarkdown(text)) return text
    var result = text
    result = result.replace(Regex("```[^\\n]*$"), "")
    result = result.replace(Regex("\\*\\*[^*]*$"), "")
    result = result.replace(Regex("(?<![\\w*])\\*[^*]*$"), "")
    result = result.replace(Regex("`[^`]*$"), "")
    result = result.replace(Regex("~~[^~]*$"), "")
    return result.trimEnd()
}

// ── Block parser ─────────────────────────────────────────────────────────────

private fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.split("\n")
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Code fence
        if (line.trimStart().startsWith("```")) {
            val language = line.trimStart().removePrefix("```").trim().ifBlank { null }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n"), language))
            i++ // skip closing ```
            continue
        }

        // Headers
        val headerMatch = Regex("^(#{1,3})\\s+(.*)").find(line)
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            val content = headerMatch.groupValues[2]
            when (level) {
                1 -> blocks.add(MdBlock.H1(parseInline(content)))
                2 -> blocks.add(MdBlock.H2(parseInline(content)))
                3 -> blocks.add(MdBlock.H3(parseInline(content)))
            }
            i++
            continue
        }

        // Horizontal rule
        if (line.trim().matches(Regex("^[-*_]{3,}$"))) {
            blocks.add(MdBlock.HorizontalRule)
            i++
            continue
        }

        // Blockquote
        if (line.trimStart().startsWith("> ")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith("> ")) {
                quoteLines.add(lines[i].trimStart().removePrefix("> "))
                i++
            }
            blocks.add(MdBlock.Blockquote(parseInline(quoteLines.joinToString("\n"))))
            continue
        }

        // Unordered list
        if (line.trimStart().matches(Regex("^[-*+]\\s+.*"))) {
            val items = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().matches(Regex("^[-*+]\\s+.*"))) {
                items.add(lines[i].trimStart().removePrefix(Regex("^[-*+]\\s+")))
                i++
            }
            blocks.add(MdBlock.BulletList(items.map { parseInline(it) }))
            continue
        }

        // Ordered list
        if (line.trimStart().matches(Regex("^\\d+\\.\\s+.*"))) {
            val items = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().matches(Regex("^\\d+\\.\\s+.*"))) {
                items.add(lines[i].trimStart().replace(Regex("^\\d+\\.\\s+"), ""))
                i++
            }
            blocks.add(MdBlock.OrderedList(items.map { parseInline(it) }))
            continue
        }

        // Table
        if (line.trimStart().startsWith("|") && line.count { it == '|' } >= 3) {
            val tableRows = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                tableRows.add(lines[i])
                i++
            }
            val dataRows = tableRows.filter { row ->
                !row.trim().matches(Regex("^\\|[\\s:-]+\\|"))
            }
            if (dataRows.isNotEmpty()) {
                val headers = dataRows[0].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                val rows = dataRows.drop(1).map { row ->
                    row.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                }
                blocks.add(MdBlock.Table(headers, rows))
            }
            continue
        }

        // Empty line
        if (line.isBlank()) {
            i++
            continue
        }

        // Paragraph
        val paraLines = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank()
            && !lines[i].trimStart().startsWith("#")
            && !lines[i].trimStart().startsWith("```")
            && !lines[i].trimStart().startsWith("> ")
            && !lines[i].trimStart().startsWith("|")
        ) {
            paraLines.add(lines[i])
            i++
        }
        if (paraLines.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(parseInline(paraLines.joinToString("\n"))))
        }
    }

    return blocks
}

// ── Inline parser ────────────────────────────────────────────────────────────

private fun parseInline(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // **bold**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(text.substring(i + 2, end))
                        pop()
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // ~~strikethrough~~
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end != -1) {
                        pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        append(text.substring(i + 2, end))
                        pop()
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // `inline code`
                text[i] == '`' && !text.startsWith("```", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        pushStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x33000000),
                            fontSize = 14.sp
                        ))
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // [link](url)
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i)
                    if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen != -1) {
                            val linkText = text.substring(i + 1, closeBracket)
                            val url = text.substring(closeBracket + 2, closeParen)
                            val start = this.toAnnotatedString().length
                            pushStyle(SpanStyle(
                                color = AccentBlue,
                                textDecoration = TextDecoration.Underline
                            ))
                            append(linkText)
                            pop()
                            addStringAnnotation("url", url, start, this.toAnnotatedString().length)
                            i = closeParen + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // *italic*
                text[i] == '*' && i + 1 < text.length && text[i + 1] != '*' -> {
                    val end = text.indexOf('*', i + 1)
                    val nextIsWord = i > 0 && text[i - 1].isLetterOrDigit()
                    val endIsWord = end > 0 && end + 1 < text.length && text[end + 1].isLetterOrDigit()
                    if (end != -1 && !nextIsWord && !endIsWord) {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // _italic_
                text[i] == '_' && i + 1 < text.length && text[i + 1] != '_' -> {
                    val end = text.indexOf('_', i + 1)
                    if (end != -1 && !(i > 0 && text[i - 1].isLetterOrDigit())) {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

// ── Composable renderers ─────────────────────────────────────────────────────

/**
 * Render a markdown text as Compose UI.
 * @param text The markdown text to render.
 * @param isStreaming If true, strip unclosed markdown tokens to avoid rendering artifacts.
 */
@Composable
fun MarkdownText(
    text: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val safeText = if (isStreaming) stripUnclosedMarkdown(text) else text
    val blocks = remember(safeText) { parseMarkdown(safeText) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> InlineText(block.spans, color, fontSize, onLinkClick)
                is MdBlock.H1 -> InlineText(block.spans, color, fontSize * 1.5f, onLinkClick, FontWeight.Bold)
                is MdBlock.H2 -> InlineText(block.spans, color, fontSize * 1.3f, onLinkClick, FontWeight.Bold)
                is MdBlock.H3 -> InlineText(block.spans, color, fontSize * 1.15f, onLinkClick, FontWeight.SemiBold)
                is MdBlock.CodeBlock -> CodeBlockView(block.code, block.language)
                is MdBlock.BulletList -> BulletListView(block.items, color, fontSize, onLinkClick)
                is MdBlock.OrderedList -> OrderedListView(block.items, color, fontSize, onLinkClick)
                is MdBlock.Blockquote -> BlockquoteView(block.spans, color, fontSize, onLinkClick)
                is MdBlock.HorizontalRule -> HorizontalRuleView()
                is MdBlock.Table -> TableView(block.headers, block.rows, color)
            }
        }
    }
}

@Composable
private fun InlineText(
    spans: AnnotatedString,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onLinkClick: ((String) -> Unit)?,
    fontWeight: FontWeight? = null,
) {
    if (onLinkClick != null) {
        ClickableText(
            text = spans,
            style = TextStyle(
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                lineHeight = fontSize * 1.4f
            ),
            onClick = { offset ->
                spans.getStringAnnotations("url", offset, offset).firstOrNull()?.let {
                    onLinkClick(it.item)
                }
            }
        )
    } else {
        Text(
            text = spans,
            style = TextStyle(
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                lineHeight = fontSize * 1.4f
            )
        )
    }
}

@Composable
private fun CodeBlockView(code: String, language: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A2E))
            .padding(12.dp)
    ) {
        if (language != null) {
            Text(
                text = language,
                style = TextStyle(
                    color = TextSecondary, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = code.trimEnd(),
            style = TextStyle(
                color = Color(0xFFE8E8F0), fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 18.sp
            ),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            maxLines = 20,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BulletListView(
    items: List<AnnotatedString>, color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit, onLinkClick: ((String) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { item ->
            Row(modifier = Modifier.padding(start = 8.dp)) {
                Text("•  ", color = color, fontSize = fontSize, lineHeight = fontSize * 1.4f)
                InlineText(item, color, fontSize, onLinkClick)
            }
        }
    }
}

@Composable
private fun OrderedListView(
    items: List<AnnotatedString>, color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit, onLinkClick: ((String) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    "${index + 1}.  ", color = color,
                    fontSize = fontSize, lineHeight = fontSize * 1.4f
                )
                InlineText(item, color, fontSize, onLinkClick)
            }
        }
    }
}

@Composable
private fun BlockquoteView(
    spans: AnnotatedString, color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit, onLinkClick: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(AccentBlue.copy(alpha = 0.5f))
        )
        Spacer(Modifier.width(8.dp))
        InlineText(spans, color.copy(alpha = 0.8f), fontSize, onLinkClick)
    }
}

@Composable
private fun HorizontalRuleView() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(horizontal = 8.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    )
}

@Composable
private fun TableView(
    headers: List<String>, rows: List<List<String>>, color: Color,
) {
    val headerRow = headers.joinToString(" | ") { "**$it**" }
    val rowTexts = rows.map { row -> row.joinToString(" | ") { it } }
    val tableText = buildString {
        appendLine(headerRow)
        appendLine(headers.joinToString(" | ") { "---" })
        rowTexts.forEach { appendLine(it) }
    }
    Text(
        text = tableText,
        style = TextStyle(
            color = color, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, lineHeight = 16.sp
        ),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    )
}

// ── Streaming buffer ─────────────────────────────────────────────────────────

/**
 * Accumulate streaming markdown text and produce safe renderable text.
 * Hides unclosed markdown tokens until they are fully received.
 */
class StreamingMarkdownBuffer {
    private val buffer = StringBuilder()

    fun append(delta: String): String {
        buffer.append(delta)
        val full = buffer.toString()
        return if (hasUnclosedMarkdown(full)) stripUnclosedMarkdown(full) else full
    }

    fun raw(): String = buffer.toString()
    fun reset() { buffer.clear() }
}
