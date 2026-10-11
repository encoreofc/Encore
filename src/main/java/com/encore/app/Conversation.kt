package com.encore.app

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val EMOJIS = listOf("🔥", "🙌", "😭", "🙈", "🙏", "😖", "❤️", "👍", "😂", "😮")

private fun clock(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

private fun dayLabel(ms: Long): String {
    val cal = java.util.Calendar.getInstance()
    fun key(t: Long): Int {
        cal.timeInMillis = t
        return cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
    }
    val d = key(ms)
    val now = System.currentTimeMillis()
    return when (d) {
        key(now) -> "Today"
        key(now - 24L * 60 * 60 * 1000) -> "Yesterday"
        else -> java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}

private fun errorText(e: Throwable): String =
    if (e is DbException) e.message ?: "Something went wrong"
    else "Can't reach the server. Check your connection."

private fun fetch(me: Long, other: Long): Pair<List<Message>, Map<Long, List<Reaction>>> =
    ChatDb.messages(me, other) to ReactDb.reactions(me, other)

@Composable
private fun MicIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.38f, h * 0.1f),
            size = Size(w * 0.24f, h * 0.5f),
            cornerRadius = CornerRadius(w * 0.12f)
        )
        drawArc(
            color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.25f, h * 0.3f),
            size = Size(w * 0.5f, h * 0.4f),
            style = Stroke(width = w * 0.07f)
        )
        drawLine(color, Offset(w * 0.5f, h * 0.7f), Offset(w * 0.5f, h * 0.88f), strokeWidth = w * 0.07f)
    }
}

@Composable
private fun VideoIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.08f, h * 0.27f),
            size = Size(w * 0.6f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.1f),
            style = Stroke(width = w * 0.08f)
        )
        val tri = Path().apply {
            moveTo(w * 0.74f, h * 0.45f)
            lineTo(w * 0.94f, h * 0.32f)
            lineTo(w * 0.94f, h * 0.68f)
            lineTo(w * 0.74f, h * 0.55f)
            close()
        }
        drawPath(tri, color)
    }
}

@Composable
private fun CopyIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.34f, w * 0.1f),
            size = Size(w * 0.54f, w * 0.54f),
            cornerRadius = CornerRadius(w * 0.1f),
            style = Stroke(width = w * 0.08f)
        )
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.12f, w * 0.34f),
            size = Size(w * 0.54f, w * 0.54f),
            cornerRadius = CornerRadius(w * 0.1f),
            style = Stroke(width = w * 0.08f)
        )
    }
}

@Composable
private fun TurnIcon(color: Color, forward: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        scale(if (forward) -1f else 1f, 1f, pivot = center) {
            val head = Path().apply {
                moveTo(w * 0.08f, h * 0.45f)
                lineTo(w * 0.42f, h * 0.18f)
                lineTo(w * 0.42f, h * 0.72f)
                close()
            }
            drawPath(head, color)
            val tail = Path().apply {
                moveTo(w * 0.4f, h * 0.45f)
                lineTo(w * 0.6f, h * 0.45f)
                quadraticBezierTo(w * 0.92f, h * 0.45f, w * 0.92f, h * 0.8f)
            }
            drawPath(tail, color, style = Stroke(width = w * 0.09f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Message,
    mine: Boolean,
    delivered: Boolean,
    reactions: List<Reaction>,
    myId: Long,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit
) {
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
            color = if (mine) Ui.mine else Ui.theirs,
            contentColor = if (mine) Ui.onMine else Ui.ink,
            shape = shape,
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(shape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(m.body, fontSize = 16.sp)
                Text(
                    clock(m.createdAt),
                    fontSize = 11.sp,
                    color = LocalContentColor.current.copy(alpha = 0.6f)
                )
            }
        }
        if (reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                reactions.groupBy { it.emoji }.forEach { (emoji, list) ->
                    val chosen = list.any { it.userId == myId }
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
        if (delivered) {
            Text(
                "Delivered",
                fontSize = 12.sp,
                color = Ui.muted,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

@Composable
private fun MenuRow(label: String, color: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = color, fontSize = 16.sp, modifier = Modifier.weight(1f))
        icon()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageSheet(
    message: Message,
    mine: Boolean,
    myEmoji: String?,
    onReact: (String) -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ui.bg) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Surface(
                color = Ui.soft,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    message.body,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(14.dp)
                )
            }
            Spacer(Modifier.height(18.dp))
            Text("React", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(EMOJIS) { e ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (e == myEmoji) Ui.soft else Color.Transparent)
                            .clickable { onReact(e) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(e, fontSize = 28.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Ui.line)
            MenuRow("Copy", Ui.ink, onCopy) { CopyIcon(Ui.ink) }
            HorizontalDivider(color = Ui.line)
            MenuRow("Reply", Ui.ink, onReply) { TurnIcon(Ui.ink, forward = false) }
            HorizontalDivider(color = Ui.line)
            MenuRow("Forward", Ui.ink, onForward) { TurnIcon(Ui.ink, forward = true) }
            if (mine) {
                HorizontalDivider(color = Ui.line)
                val red = MaterialTheme.colorScheme.error
                MenuRow("Delete", red, onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = red)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ConversationScreen(me: User, other: Contact, onBack: () -> Unit) {
    var msgs by remember { mutableStateOf<List<Message>>(emptyList()) }
    var reacts by remember { mutableStateOf<Map<Long, List<Reaction>>>(emptyMap()) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Message?>(null) }
    var confirmDelete by remember { mutableStateOf<Message?>(null) }
    val soon = remember { SoonState() }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    fun load() {
        runIo({ fetch(me.id, other.id) }) { r ->
            r.onSuccess {
                msgs = it.first
                reacts = it.second
            }
        }
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty()) return
        sending = true
        problem = null
        runIo({
            ChatDb.send(me.id, other.id, body)
            fetch(me.id, other.id)
        }) { r ->
            sending = false
            r.onSuccess {
                text = ""
                msgs = it.first
                reacts = it.second
            }.onFailure { problem = errorText(it) }
        }
    }

    fun react(id: Long, emoji: String) {
        runIo({
            ReactDb.react(id, me.id, emoji)
            fetch(me.id, other.id)
        }) { r ->
            r.onSuccess {
                msgs = it.first
                reacts = it.second
            }.onFailure { problem = errorText(it) }
        }
    }

    fun delete(m: Message) {
        runIo({
            ReactDb.deleteMessage(m.id, me.id)
            fetch(me.id, other.id)
        }) { r ->
            r.onSuccess {
                msgs = it.first
                reacts = it.second
            }.onFailure { problem = errorText(it) }
        }
    }

    Ticker(3000L, other.id) { load() }

    val rows = remember(msgs) {
        val out = ArrayList<Any>()
        var lastDay = ""
        for (m in msgs) {
            val d = dayLabel(m.createdAt)
            if (d != lastDay) {
                out.add(d)
                lastDay = d
            }
            out.add(m)
        }
        out
    }
    val last = msgs.lastOrNull()

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.scrollToItem(rows.size - 1)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ui.ink)
                }
                Avatar(other, 40.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    other.username,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { soon.show("Video calls") }) { VideoIcon(Ui.ink) }
                IconButton(onClick = { soon.show("Calls") }) {
                    Icon(Icons.Default.Call, contentDescription = "Call", tint = Ui.ink)
                }
            }
            HorizontalDivider(color = Ui.line)

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(rows, key = { if (it is Message) it.id else it }) { row ->
                    if (row is String) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(row, fontSize = 12.sp, color = Ui.muted)
                        }
                    } else if (row is Message) {
                        val mine = row.senderId == me.id
                        Bubble(
                            m = row,
                            mine = mine,
                            delivered = mine && last != null && last.id == row.id,
                            reactions = reacts[row.id] ?: emptyList(),
                            myId = me.id,
                            onLongPress = { selected = row },
                            onReact = { e -> react(row.id, e) }
                        )
                    }
                }
            }

            problem?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { soon.show("Attachments") }) {
                    Icon(Icons.Default.Add, contentDescription = "Attach", tint = Ui.ink)
                }
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Message", color = Ui.muted) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Ui.soft,
                        unfocusedContainerColor = Ui.soft,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (text.isBlank()) {
                    IconButton(onClick = { soon.show("Voice messages") }) { MicIcon(Ui.ink) }
                } else {
                    IconButton(onClick = { send() }, enabled = !sending) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Ui.ink),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = Ui.onInk,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        SoonToast(soon, 80.dp)
    }

    selected?.let { m ->
        MessageSheet(
            message = m,
            mine = m.senderId == me.id,
            myEmoji = reacts[m.id]?.firstOrNull { it.userId == me.id }?.emoji,
            onReact = { e ->
                selected = null
                react(m.id, e)
            },
            onCopy = {
                clipboard.setText(AnnotatedString(m.body))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                selected = null
            },
            onReply = {
                selected = null
                soon.show("Reply")
            },
            onForward = {
                selected = null
                soon.show("Forward")
            },
            onDelete = {
                selected = null
                confirmDelete = m
            },
            onDismiss = { selected = null }
        )
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Ui.bg,
            title = { Text("Delete message?") },
            text = { Text("This removes it for both of you.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    delete(m)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}
