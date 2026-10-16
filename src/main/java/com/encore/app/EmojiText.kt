package com.encore.app

import android.graphics.Typeface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

private fun isEmojiCp(cp: Int) =
    cp >= 0x1F000 || cp in 0x2190..0x2BFF || cp == 0x200D || cp == 0xFE0F

@Composable
fun EmojiText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val context = LocalContext.current
    val font = remember {
        FontFamily(Typeface.createFromAsset(context.assets, "fonts/emoji.ttf"))
    }
    val annotated = remember(text) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val n = Character.charCount(cp)
                val part = text.substring(i, i + n)
                if (isEmojiCp(cp)) withStyle(SpanStyle(fontFamily = font)) { append(part) }
                else append(part)
                i += n
            }
        }
    }
    Text(annotated, modifier, color = color, style = style, maxLines = maxLines, overflow = overflow)
}
