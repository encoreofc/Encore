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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(
    cid: String,
    item: MsgItem,
    mine: Boolean,
    tick: String,
    reply: ReplyInfo?,
    replyName: String,
    reactions: List<Pair<String, String>>,
    myId: String,
    progress: Float?,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
    onQuote: () -> Unit,
    onImage: () -> Unit,
    onFile: () -> Unit
) {
    val m = item.msg
    val deleted = m.deleted
    val hasMedia = !deleted && item.media != null && (m.kind == "image" || m.kind == "file")
    val haptic = LocalHapticFeedback.current
    val shape = if (mine) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        Surface(
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
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.End
            ) {
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
                                else (reply.body ?: kindLabel(reply.kind)),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = LocalContentColor.current.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                if (hasMedia) {
                    Box(modifier = Modifier.align(Alignment.Start)) {
                        MediaBlock(cid, item, progress, onImage, onFile)
                    }
                    if (!m.body.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(m.body, fontSize = 16.sp, modifier = Modifier.align(Alignment.Start))
                    }
                } else {
                    Text(
                        if (deleted) "This message was deleted" else (m.body ?: kindLabel(m.kind)),
                        fontSize = 16.sp,
                        fontStyle = if (deleted) FontStyle.Italic else FontStyle.Normal
                    )
                }
                val meta = (if (item.editedAt != null && !deleted) "edited  " else "") +
                    clock(m.createdAt) + (if (mine && !deleted) "  $tick" else "")
                Text(
                    meta,
                    fontSize = 11.sp,
                    color = LocalContentColor.current.copy(alpha = 0.6f)
                )
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
