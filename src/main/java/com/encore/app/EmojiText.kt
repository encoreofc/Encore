package com.encore.app

import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit

/** Loads fonts/emoji.ttf from assets once. If the file is missing, falls back to the system emoji. */
object EmojiFont {
    @Volatile
    private var family: FontFamily? = null

    fun get(ctx: Context): FontFamily {
        family?.let { return it }
        val f = try {
            FontFamily(Typeface.createFromAsset(ctx.applicationContext.assets, "fonts/emoji.ttf"))
        } catch (e: Throwable) {
            FontFamily.Default
        }
        family = f
        return f
    }
}

internal fun isEmojiCp(cp: Int): Boolean = when {
    cp >= 0x1F000 -> true
    cp == 0x2713 -> false
    cp in 0x2600..0x27BF -> true
    cp in 0x2300..0x23FF -> true
    cp in 0x2B00..0x2BFF -> true
    cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 -> true
    else -> false
}

internal fun hasEmoji(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.code < 0x2300 && !Character.isHighSurrogate(c)) { i++; continue }
        val cp = s.codePointAt(i)
        if (isEmojiCp(cp)) return true
        i += Character.charCount(cp)
    }
    return false
}

internal fun emojiAnnotate(s: String, font: FontFamily): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val n = Character.charCount(cp)
        val part = s.substring(i, i + n)
        if (isEmojiCp(cp)) withStyle(SpanStyle(fontFamily = font)) { append(part) } else append(part)
        i += n
    }
}

/** For text fields: draws emoji typed by the user with the emoji font. */
@Composable
fun rememberEmojiTransformation(): VisualTransformation {
    val ctx = LocalContext.current
    return remember {
        VisualTransformation { t ->
            if (hasEmoji(t.text)) TransformedText(emojiAnnotate(t.text, EmojiFont.get(ctx)), OffsetMapping.Identity)
            else TransformedText(t, OffsetMapping.Identity)
        }
    }
}

/**
 * Same name as the Material Text on purpose. Every file in this package that uses
 * `import androidx.compose.material3.*` (or has no Text import of its own) now picks this one
 * automatically, so emoji show up in the emoji font everywhere without editing each Text call.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
    val ctx = LocalContext.current
    val annotated = remember(text) {
        if (hasEmoji(text)) emojiAnnotate(text, EmojiFont.get(ctx)) else AnnotatedString(text)
    }
    androidx.compose.material3.Text(
        text = annotated, modifier = modifier, color = color, fontSize = fontSize,
        fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap,
        maxLines = maxLines, minLines = minLines,
        onTextLayout = onTextLayout ?: {},
        style = style
    )
}

/** Same as Text, for places where you want to call it by name. */
@Composable
fun EmojiText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) = Text(text = text, modifier = modifier, color = color, style = style, maxLines = maxLines, overflow = overflow)
