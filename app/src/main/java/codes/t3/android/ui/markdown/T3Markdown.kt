package codes.t3.android.ui.markdown

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codes.t3.android.ui.theme.LocalT3Colors
import codes.t3.android.ui.theme.MonoStyle
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Assistant/user markdown with Material 3 styling and T3-style code blocks. */
@Composable
fun T3Markdown(
    text: String,
    modifier: Modifier = Modifier,
    wrapCode: Boolean = false,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val body = type.bodyLarge.copy(fontSize = 15.5.sp, lineHeight = 23.sp)
    val codeInline = MonoStyle.copy(fontSize = 13.5.sp)
    Markdown(
        content = text,
        modifier = modifier,
        colors = markdownColor(
            text = textColor,
            codeBackground = LocalT3Colors.current.codeBackground,
            inlineCodeBackground = scheme.surfaceContainerHighest,
            dividerColor = scheme.outlineVariant,
            tableBackground = scheme.surfaceContainerLow,
        ),
        typography = markdownTypography(
            h1 = type.headlineSmall,
            h2 = type.titleLarge,
            h3 = type.titleMedium,
            h4 = type.titleSmall,
            h5 = type.titleSmall,
            h6 = type.titleSmall,
            text = body,
            paragraph = body,
            ordered = body,
            bullet = body,
            list = body,
            quote = body.copy(color = scheme.onSurfaceVariant),
            code = MonoStyle,
            inlineCode = codeInline,
            textLink = androidx.compose.ui.text.TextLinkStyles(
                style = SpanStyle(color = scheme.primary, fontWeight = FontWeight.Medium),
            ),
        ),
        padding = markdownPadding(block = 4.dp),
        dimens = markdownDimens(codeBackgroundCornerSize = 16.dp),
        components = markdownComponents(
            codeFence = { m -> MarkdownCodeFence(m.content, m.node, MonoStyle) { code, lang, _ -> CodeBlock(code, lang, wrapCode) } },
            codeBlock = { m -> MarkdownCodeBlock(m.content, m.node, MonoStyle) { code, lang, _ -> CodeBlock(code, lang, wrapCode) } },
        ),
    )
}

@Composable
fun CodeBlock(code: String, language: String?, wrap: Boolean = false, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlighted by produceState(AnnotatedString(code), code, language, dark) {
        value = withContext(Dispatchers.Default) { highlight(code, language, dark) }
    }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = LocalT3Colors.current.codeBackground,
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    language?.takeIf { it.isNotBlank() } ?: "text",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }) {
                    AnimatedContent(copied, label = "copy") { done ->
                        Icon(if (done) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(if (copied) "Copied" else "Copy", style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SelectionContainer {
                Text(
                    highlighted,
                    style = MonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    softWrap = wrap,
                    modifier = Modifier
                        .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private val languageAliases = mapOf(
    "ts" to "typescript", "tsx" to "typescript", "js" to "javascript", "jsx" to "javascript",
    "py" to "python", "kt" to "kotlin", "kts" to "kotlin", "rs" to "rust", "sh" to "shell",
    "bash" to "shell", "zsh" to "shell", "console" to "shell", "rb" to "ruby", "cs" to "csharp",
    "c++" to "cpp", "h" to "c", "yml" to "yaml", "golang" to "go",
)

internal fun highlight(code: String, language: String?, dark: Boolean): AnnotatedString {
    val lang = language?.lowercase()?.let { languageAliases[it] ?: it }?.let { SyntaxLanguage.getByName(it) }
        ?: return AnnotatedString(code)
    val highlights = runCatching {
        Highlights.Builder()
            .theme(SyntaxThemes.atom(darkMode = dark))
            .language(lang)
            .code(code)
            .build()
            .getHighlights()
    }.getOrElse { return AnnotatedString(code) }
    return buildAnnotatedString {
        append(code)
        highlights.forEach { h ->
            val start = h.location.start.coerceIn(0, code.length)
            val end = h.location.end.coerceIn(start, code.length)
            when (h) {
                is ColorHighlight -> addStyle(SpanStyle(color = Color(h.rgb).copy(alpha = 1f)), start, end)
                is BoldHighlight -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            }
        }
    }
}

