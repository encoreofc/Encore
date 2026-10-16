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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Text + time: the time sits at the end of the last line when it fits,
// and drops to its own line only when there is no room.
@Composable
private fun TextWithMeta(text: String, meta: String, italic: Boolean, metaColor: Color, incoming: Boolean) {
    var lastLineW by remember(text) { mutableIntStateOf(0) }
    Layout(
        content = {
            Text(
                text,
                fontSize = fs(16f),
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                onTextLayout = { r ->
                    lastLineW = kotlin.math.ceil(r.getLineRight(r.lineCount - 1)).toInt()
                }
            )
            Text(meta, fontSize = fs(12f), color = metaColor)
        }
    ) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val t = measurables[0].measure(loose)
        val mt = measurables[1].measure(loose)
        val sameLine = lastLineW + gap + mt.width <= constraints.maxWidth
        val width = if (sameLine) maxOf(t.width, lastLineW + gap + mt.width) else maxOf(t.width, mt.width)
        val height = if (sameLine) t.height else t.height + mt.height
        layout(width, height) {
            t.placeRelative(0, 0)
            // time bottom is 6.5dp above the cell bottom (5dp below the last text line); incoming time sits 4dp further right
            mt.placeRelative(width - mt.width + (if (incoming) 4.dp.roundToPx() else 0), height - mt.height + 5.dp.roundToPx())
        }
    }
}

// Reaction chips: height 26, fully rounded, gap 4 (both ways),
// emoji box 20 at 8 from the left, counter 12 bold; width = 8 + 20 + 4 + counter + 8 (emoji only: 8 + 20 + 3)
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionChips(
    reactions: List<Pair<String, String>>,
    myId: String,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        reactions.groupBy { it.second }.forEach { (emoji, list) ->
            val chosen = list.any { it.first == myId }
            val shape = RoundedCornerShape(13.dp)
            Row(
                modifier = Modifier
                    .height(26.dp)
                    .glassSurface(shape)
                    .then(if (chosen) Modifier.border(1.dp, Ui.ink, shape) else Modifier)
                    .clickable { onReact(emoji) }
                    .padding(start = 8.dp, end = if (list.size > 1) 8.dp else 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    Text(emoji, fontSize = fs(16f))
                }
                if (list.size > 1) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${list.size}",
                        fontSize = fs(12f),
                        fontWeight = FontWeight.Bold,
                        color = Ui.muted
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
    onOnce: () -> Unit = {},
    pinnedTop: Boolean = false,
    pinnedBottom: Boolean = false
) {
    val m = item.msg
    val deleted = m.deleted
    val isVoice = !deleted && item.media != null && m.kind == "voice"
    val isOnce = !deleted && item.media != null && item.ttl != 0 && (m.kind == "image" || m.kind == "video")
    val isVideo = !deleted && item.media != null && m.kind == "video"
    val hasMedia = !deleted && item.media != null && (m.kind == "image" || m.kind == "file")
    val haptic = LocalHapticFeedback.current
    val cfg = LocalConfiguration.current
    val mediaBubble = isVideo || (hasMedia && m.kind == "image")
    val stickerSide = (minOf(cfg.screenWidthDp, cfg.screenHeightDp) * 0.5f).dp
    val isSticker = !deleted && m.kind == "sticker" && !m.body.isNullOrBlank()
    // Corners 17, near corners 6 on the sender's side (top when pinnedTop, bottom when pinnedBottom).
    // The last bubble of a run has no bottom corner there: the tail takes its place (drawn below).
    val topNear = if (pinnedTop) 6.dp else 17.dp
    val hasTail = !deleted && !pinnedBottom
    val bottomNear = if (hasTail) 0.dp else 6.dp
    val tailColor = if (mine) Ui.mine else Ui.theirs
    val shape = if (mine) {
        RoundedCornerShape(17.dp, topNear, bottomNear, 17.dp)
    } else {
        RoundedCornerShape(topNear, 17.dp, 17.dp, bottomNear)
    }
    val plainText = !(isVoice || isOnce || isVideo || hasMedia)
    val showReactions = reactions.isNotEmpty() && !deleted
    val meta = (if (item.editedAt != null && !deleted) "edited  " else "") +
        clock(m.createdAt) + (if (mine && !deleted) "  $tick" else "")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (pinnedTop) 1.dp else 3.dp, bottom = if (pinnedBottom) 1.dp else 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        if (isSticker) {
            // a sticker has no bubble: it sits on the chat background, time in a small pill below
            Box(
                modifier = Modifier
                    .size(stickerSide)
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
                fontSize = fs(11f),
                color = Ui.muted,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .glassSurface(RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        } else Surface(
            color = if (deleted) Color.Transparent else if (mine) Ui.mine else Ui.theirs,
            contentColor = if (deleted) Ui.muted else if (mine) Ui.onMine else Ui.ink,
            shape = shape,
            border = if (deleted) BorderStroke(1.dp, Ui.line) else null,
            modifier = Modifier
                .widthIn(max = (if (mediaBubble) cfg.screenWidthDp - 46 else cfg.screenWidthDp - 62).dp) // text: screen - 80 plus 18 padding; photo/video: screen - 64 plus 18 padding
                .drawBehind {
                    // Tail: the side edge runs down to 11dp above the bottom, then flares out
                    // along a 13 x 20dp ellipse (-83 degrees) to a tip 5.4dp outside the body at the bottom edge.
                    if (hasTail) {
                        val x0 = if (mine) size.width else 0f
                        val y0 = size.height
                        val dir = if (mine) 1f else -1f
                        val p = Path().apply {
                            moveTo(x0, y0 - 11.dp.toPx())
                            if (mine) {
                                arcTo(Rect(x0, y0 - 21.dp.toPx(), x0 + 13.dp.toPx(), y0 - 1.dp.toPx()), 180f, -83f, false)
                            } else {
                                arcTo(Rect(x0 - 13.dp.toPx(), y0 - 21.dp.toPx(), x0, y0 - 1.dp.toPx()), 0f, 83f, false)
                            }
                            lineTo(x0 + dir * 5.4.dp.toPx(), y0)
                            lineTo(x0, y0)
                            close()
                        }
                        drawPath(p, tailColor)
                    }
                }
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
                modifier = Modifier.padding(
                    start = 11.dp,
                    end = 7.dp,
                    top = if (senderName != null) 7.dp else if (pinnedTop) 6.dp else 5.dp, // name at 10dp from cell top, text right under it
                    bottom = if (showReactions && plainText) 8.dp else if (pinnedBottom) 10.5.dp else 7.5.dp
                ),
                horizontalAlignment = Alignment.End
            ) {
                if (senderName != null) {
                    Row(
                        modifier = Modifier.align(Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            senderName,
                            fontSize = fs(14f),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Badge(m.senderId, fs(14f), name = senderName)
                    }
                }
                if (!deleted && item.fwdFrom != null) {
                    Text(
                        "Forwarded from ${item.fwdFrom}",
                        fontSize = fs(14f),
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
                            Text(replyName, fontSize = fs(14f), fontWeight = FontWeight.Medium)
                            Text(
                                if (reply == null || reply.deleted) "Message unavailable"
                                else msgPreview(reply.kind, reply.body),
                                fontSize = fs(14f),
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
                        Text(m.body, fontSize = fs(16f), modifier = Modifier.align(Alignment.Start))
                    }
                } else if (hasMedia) {
                    Box(modifier = Modifier.align(Alignment.Start)) {
                        MediaBlock(cid, item, progress, onImage, onFile)
                    }
                    if (!m.body.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(m.body, fontSize = fs(16f), modifier = Modifier.align(Alignment.Start))
                    }
                } else if (showReactions) {
                    // reactions inside the bubble, 7.5dp under the text, time on the same bottom row
                    Text(
                        m.body ?: kindLabel(m.kind),
                        fontSize = fs(16f),
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(Modifier.height(7.5.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        ReactionChips(reactions, myId, onReact, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            meta,
                            fontSize = fs(12f),
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                            modifier = Modifier.offset(y = 5.5.dp)
                        )
                    }
                } else {
                    TextWithMeta(
                        text = if (deleted) "This message was deleted" else (m.body ?: kindLabel(m.kind)),
                        meta = meta,
                        italic = deleted,
                        metaColor = LocalContentColor.current.copy(alpha = 0.6f),
                        incoming = !mine
                    )
                }
                if (!plainText) {
                    if (showReactions) {
                        Spacer(Modifier.height(6.dp))
                        ReactionChips(reactions, myId, onReact, Modifier.align(Alignment.Start))
                    }
                    Text(
                        meta,
                        fontSize = fs(12f),
                        color = LocalContentColor.current.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
