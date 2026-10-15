package com.encore.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Telegram-style text + time: the time sits at the end of the last line when it fits,
// and drops to its own line only when there is no room.
@Composable
private fun TextWithMeta(text: String, meta: String, italic: Boolean, metaColor: Color) {
    var lastLineW by remember(text) { mutableIntStateOf(0) }
    Layout(
        content = {
            Text(
                text,
                fontSize = 16.sp,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                onTextLayout = { r ->
                    lastLineW = kotlin.math.ceil(r.getLineRight(r.lineCount - 1)).toInt()
                }
            )
            Text(meta, fontSize = 12.sp, color = metaColor)
        }
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val t = measurables[0].measure(loose)
        val mt = measurables[1].measure(loose)
        val sameLine = lastLineW + gap + mt.width <= constraints.maxWidth
        val width = if (sameLine) maxOf(t.width, lastLineW + gap + mt.width) else maxOf(t.width, mt.width)
        val height = if (sameLine) t.height else t.height + mt.height
        layout(width, height) {
            t.placeRelative(0, 0)
            mt.placeRelative(width - mt.width, height - mt.height)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(
    cid: String,
    item: MsgItem,
    mine: Boolean,
    tick: String,
    senderName: String?,
    reply: ReplyInfo?,
    replyName: String,
    reactions: List<Pair<String, String>>,
    myId: String,
    progress: Float?,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
    onQuote: () -> Unit,
    onImage: () -> Unit,
    onFile: () -> Unit,
    onVideo: () -> Unit,
    onOnce: () -> Unit = {}
) {
    val m = item.msg
    val deleted = m.deleted
    val isVoice = !deleted && item.media != null && m.kind == "voice"
    val isOnce = !deleted && item.media != null && item.ttl != 0 && (m.kind == "image" || m.kind == "video")
    val isVideo = !deleted && item.media != null && m.kind == "video"
    val hasMedia = !deleted && item.media != null && (m.kind == "image" || m.kind == "file")
    val haptic = LocalHapticFeedback.current
    val isSticker = !deleted && m.kind == "sticker" && !m.body.isNullOrBlank()
    val shape = if (mine) {
        RoundedCornerShape(17.dp, 17.dp, 5.dp, 17.dp)
    } else {
        RoundedCornerShape(17.dp, 17.dp, 17.dp, 5.dp)
    }
    val plainText = !(isVoice || isOnce || isVideo || hasMedia)
    val meta = (if (item.editedAt != null && !deleted) "edited  " else "") +
        clock(m.createdAt) + (if (mine && !deleted) "  $tick" else "")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        if (isSticker) {
            // a sticker has no bubble: it sits on the chat background, time in a small pill below
            Box(
                modifier = Modifier
                    .size(156.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            if (!m.id.startsWith("local-")) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPress()
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                StickerView(m.body ?: "", item.media?.type ?: "image", Modifier.fillMaxSize())
            }
            Text(
                clock(m.createdAt) + (if (mine) "  $tick" else ""),
                fontSize = 11.sp,
                color = Ui.muted,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ui.soft)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        } else Surface(
            color = if (deleted) Color.Transparent else if (mine) Ui.mine else Ui.theirs,
            contentColor = if (deleted) Ui.muted else if (mine) Ui.onMine else Ui.ink,
            shape = shape,
            border = if (deleted) BorderStroke(1.dp, Ui.line) else null,
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(shape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (!deleted && !m.id.startsWith("local-")) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        }
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.End
            ) {
                if (senderName != null) {
                    Row(
                        modifier = Modifier.align(Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            senderName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Badge(m.senderId, 12.sp, name = senderName)
                    }
                }
                if (!deleted && item.fwdFrom != null) {
                    Text(
                        "Forwarded from ${item.fwdFrom}",
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.Start)
                    )
                }
                if (!deleted && item.replyTo != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.Start)
                            .padding(bottom = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onQuote() }
                            .height(IntrinsicSize.Min)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(LocalContentColor.current.copy(alpha = 0.6f))
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(replyName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (reply == null || reply.deleted) "Message unavailable"
                                else msgPreview(reply.kind, reply.body),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = LocalContentColor.current.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                if (isVoice) {
                    Box(modifier = Modifier.align(Alignment.Start)) {
                        VoiceRow(cid, item, progress)
                    }
                } else if (isOnce) {
                    Box(modifier = Modifier.align(Alignment.Start)) {
                        OnceBlock(item, mine, progress, onOnce)
                    }
                } else if (isVideo) {
                    Box(modifier = Modifier.align(Alignment.Start)) {
                        VideoBlock(item, progress, onVideo)
                    }
                    if (!m.body.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(m.body, fontSize = 16.sp, modifier = Modifier.align(Alignment.Start))
                    }
                } else if (hasMedia) {
                    Box(modifier = Modifier.align(Alignment.Start)) {
                        MediaBlock(cid, item, progress, onImage, onFile)
                    }
                    if (!m.body.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(m.body, fontSize = 16.sp, modifier = Modifier.align(Alignment.Start))
                    }
                } else {
                    TextWithMeta(
                        text = if (deleted) "This message was deleted" else (m.body ?: kindLabel(m.kind)),
                        meta = meta,
                        italic = deleted,
                        metaColor = LocalContentColor.current.copy(alpha = 0.6f)
                    )
                }
                if (!plainText) {
                    Text(
                        meta,
                        fontSize = 12.sp,
                        color = LocalContentColor.current.copy(alpha = 0.6f)
                    )
                }
            }
        }
        if (reactions.isNotEmpty() && !deleted) {
            Row(
                modifier = Modifier.padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                reactions.groupBy { it.second }.forEach { (emoji, list) ->
                    val chosen = list.any { it.first == myId }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Ui.soft)
                            .then(
                                if (chosen) Modifier.border(1.dp, Ui.ink, RoundedCornerShape(12.dp))
                                else Modifier
                            )
                            .clickable { onReact(emoji) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(emoji, fontSize = 14.sp)
                        if (list.size > 1) {
                            Spacer(Modifier.width(4.dp))
                            Text("${list.size}", fontSize = 12.sp, color = Ui.muted)
                        }
                    }
                }
            }
        }
    }
}
