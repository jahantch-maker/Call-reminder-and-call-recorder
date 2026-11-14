package com.callbackreminder.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * v0.55 The message bar, the same everywhere (task Timeline, team Timeline, Help chat), copied from WhatsApp:
 * one slim row with a rounded box (the text, and the small buttons inside it on the right) and a round ➤ / 🎤 button.
 * A picked @name is shown in blue, and one tap of the delete key removes the whole name.
 */

/** The @word a member is called by: the first name, or the whole name without spaces when two share a first name. */
fun memberTag(m: TeamMember, all: List<TeamMember>): String {
    val dup = all.count { it.active && it.first.equals(m.first, ignoreCase = true) } > 1
    return (if (dup) m.name.filter { !it.isWhitespace() } else m.first).ifBlank { "Member" }
}

fun mentionTags(all: List<TeamMember>): Set<String> = all.filter { it.active }.map { memberTag(it, all).lowercase() }.toSet()

private val MENTION_WORD = Regex("@(\\S+)")
private const val MENTION_END = ",.!?;:"

/** Where the @names are in [text] (inclusive ranges, from the @ to the last letter of the name). */
fun mentionSpans(text: String, tags: Set<String>): List<IntRange> {
    if (tags.isEmpty()) return emptyList()
    return MENTION_WORD.findAll(text).mapNotNull { r ->
        val start = r.range.first
        if (start > 0 && !text[start - 1].isWhitespace()) return@mapNotNull null
        val core = r.groupValues[1].trimEnd(*MENTION_END.toCharArray())
        if (core.lowercase() !in tags) null else start..(start + core.length)
    }.toList()
}

fun mentionStyled(text: String, tags: Set<String>, blue: Color): AnnotatedString {
    val spans = mentionSpans(text, tags)
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        spans.forEach { addStyle(SpanStyle(color = blue, fontWeight = FontWeight.SemiBold), it.first, it.last + 1) }
    }
}

@Composable
fun mentionBlue(): Color = if (LocalDarkTheme.current) Color(0xFF53BDEB) else Color(0xFF1565C0)

@Composable
fun rememberMentionTags(): Set<String> {
    val members by TeamRepo.members.collectAsState()
    return remember(members) { mentionTags(members) }
}

/** One delete key press next to / inside a picked @name removes the whole name, like WhatsApp. */
fun mentionEdit(old: TextFieldValue, new: TextFieldValue, tags: Set<String>): TextFieldValue {
    if (tags.isEmpty() || old.text.length - new.text.length != 1) return new
    val idx = if (old.selection.collapsed && old.selection.start > 0) old.selection.start - 1
    else {
        var i = 0
        while (i < new.text.length && old.text[i] == new.text[i]) i++
        i
    }
    val span = mentionSpans(old.text, tags).firstOrNull { idx in it } ?: return new
    val text = old.text.removeRange(span.first, span.last + 1)
    return TextFieldValue(text, TextRange(span.first))
}

/** The @ button: an @ at the cursor (with a space before it when it follows a word). */
fun insertAtSign(v: TextFieldValue): TextFieldValue {
    val s = v.selection.min.coerceIn(0, v.text.length)
    val e = v.selection.max.coerceIn(s, v.text.length)
    val ins = if (s > 0 && !v.text[s - 1].isWhitespace()) " @" else "@"
    return TextFieldValue(v.text.substring(0, s) + ins + v.text.substring(e), TextRange(s + ins.length))
}

/** The word being typed after an @ at the cursor ("" right after the @), or null when there is none. */
fun mentionToken(v: TextFieldValue): String? =
    Regex("(?:^|\\s)@(\\S*)$").find(v.text.substring(0, v.selection.start.coerceIn(0, v.text.length)))?.groupValues?.get(1)

/** A picked member: the @word becomes "@Name " and the cursor goes after it. */
fun pickMention(v: TextFieldValue, tag: String): TextFieldValue {
    val cur = v.selection.start.coerceIn(0, v.text.length)
    val before = v.text.substring(0, cur).replace(Regex("@\\S*$")) { "@$tag " }
    return TextFieldValue(before + v.text.substring(cur), TextRange(before.length))
}

/** The rounded message box. [trailing] holds the small buttons inside it (voice typing, @, photo). */
@Composable
fun ChatPill(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val tags = rememberMentionTags()
    val blue = mentionBlue()
    val transform = remember(tags, blue) {
        object : VisualTransformation {
            override fun filter(text: AnnotatedString) = TransformedText(mentionStyled(text.text, tags, blue), OffsetMapping.Identity)
        }
    }
    BasicTextField(
        value = value,
        onValueChange = { onValue(mentionEdit(value, it, tags)) },
        textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp),
        cursorBrush = SolidColor(cs.primary),
        visualTransformation = transform,
        maxLines = 4,
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).background(cs.surfaceVariant, RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (value.text.isEmpty()) Text(hint, color = cs.onSurfaceVariant, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                trailing()
            }
        },
    )
}

/** A small round button inside the message box (voice typing, @, photo). */
@Composable
fun ChatIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { content() }
}

/** The round ➤ button next to the box. */
@Composable
fun ChatSendButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(44.dp).clip(CircleShape).background(if (enabled) Color(0xFF3DD6B5) else MaterialTheme.colorScheme.surfaceVariant),
    ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = Color(0xFF053D2E)) }
}
