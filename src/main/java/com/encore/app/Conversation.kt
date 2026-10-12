package com.encore.app

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val EMOJIS = listOf("🔥", "🙌", "😭", "🙈", "🙏", "😖", "❤️", "👍", "😂", "😮")
private val convHandler = Handler(Looper.getMainLooper())

private fun clock(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

private fun seenLabel(ms: Long): String =
    if (System.currentTimeMillis() - ms < 24L * 60 * 60 * 1000) clock(ms)
    else java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(java.util.Date(ms))

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

private fun addItem(list: List<MsgItem>, x: MsgItem): List<MsgItem> =
    (list.filter { it.msg.id != x.msg.id } + x).sortedBy { it.msg.id }

private fun setReaction(
    map: Map<String, List<Pair<String, String>>>,
    mid: String,
    uid: String,
    emoji: String?
): Map<String, List<Pair<String, String>>> {
    val rest = (map[mid] ?: emptyList()).filter { it.first != uid }
    val next = if (emoji == null) rest else rest + (uid to emoji)
    return if (next.isEmpty()) map - mid else map + (mid to next)
}

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
    item: MsgItem,
    mine: Boolean,
    tick: String,
    reply: ReplyInfo?,
    replyName: String,
    reactions: List<Pair<String, String>>,
    myId: String,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
    onQuote: () -> Unit
) {
    val m = item.msg
    val deleted = m.deleted
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
                                if (reply == null || reply.deleted) "Message unavailable" else (reply.body ?: ""),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = LocalContentColor.current.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                Text(
                    if (deleted) "This message was deleted" else (m.body ?: ""),
                    fontSize = 16.sp,
                    fontStyle = if (deleted) FontStyle.Italic else FontStyle.Normal
                )
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
    item: MsgItem,
    mine: Boolean,
    myEmoji: String?,
    onReact: (String) -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
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
                    item.msg.body ?: "",
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
            if (mine && item.msg.kind == "text") {
                HorizontalDivider(color = Ui.line)
                MenuRow("Edit", Ui.ink, onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = Ui.ink)
                }
            }
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
private fun ForwardDialog(onPick: (Conv) -> Unit, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf<List<Conv>?>(null) }
    LaunchedEffect(Unit) {
        runIo({ Api.conversations() }) { r -> r.onSuccess { list = it } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui.bg,
        title = { Text("Forward to") },
        text = {
            val l = list
            if (l == null) {
                Text("Loading…", color = Ui.muted)
            } else if (l.isEmpty()) {
                Text("No chats yet", color = Ui.muted)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(l, key = { it.id }) { c ->
                        val p = c.peer
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(c) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(p?.id ?: c.id, p?.displayName ?: "?", 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(p?.displayName ?: "Unknown")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ComposerBanner(title: String, body: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 4.dp, top = 6.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(Ui.ink)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, fontSize = 13.sp, color = Ui.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Ui.muted)
        }
    }
}

@Composable
fun ConversationScreen(me: User, conv: Conv, onBack: () -> Unit, onLogout: () -> Unit) {
    val peer = conv.peer
    var items by remember { mutableStateOf<List<MsgItem>>(emptyList()) }
    var pending by remember { mutableStateOf<List<MsgItem>>(emptyList()) }
    var reacts by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var peerRead by remember { mutableStateOf(conv.peerRead) }
    var marked by remember { mutableStateOf(conv.myRead) }
    var typingAt by remember { mutableStateOf(0L) }
    var typedAt by remember { mutableStateOf(0L) }
    var online by remember { mutableStateOf<Boolean?>(null) }
    var lastSeen by remember { mutableStateOf(peer?.lastSeen) }
    var replyTo by remember { mutableStateOf<MsgItem?>(null) }
    var editing by remember { mutableStateOf<MsgItem?>(null) }
    var selected by remember { mutableStateOf<MsgItem?>(null) }
    var confirmDelete by remember { mutableStateOf<MsgItem?>(null) }
    var forwarding by remember { mutableStateOf<MsgItem?>(null) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    val soon = remember { SoonState() }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    fun load() {
        runIo({ Features.sync(conv.id) }) { r ->
            r.onSuccess { s ->
                items = s.items
                reacts = s.reactions
            }.onFailure {
                if ((it as? ApiException)?.code == "unauthorized") onLogout()
            }
        }
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty()) return
        val ed = editing
        if (ed != null) {
            editing = null
            text = ""
            runIo({ Features.edit(ed.msg.id, body) }) { r ->
                r.onSuccess {
                    items = items.map { x ->
                        if (x.msg.id == ed.msg.id) x.copy(msg = x.msg.copy(body = body), editedAt = System.currentTimeMillis()) else x
                    }
                }.onFailure { problem = it.message }
            }
            return
        }
        val rep = replyTo
        val cid = java.util.UUID.randomUUID().toString()
        val local = MsgItem(
            Msg("local-$cid", conv.id, me.id, "text", body, System.currentTimeMillis(), false, cid),
            null, null, rep?.msg?.id, null
        )
        pending = pending + local
        text = ""
        replyTo = null
        typedAt = 0L
        Rt.send(JSONObject().put("type", "typing").put("c", conv.id).put("s", false))
        runIo({ Features.send(conv.id, body, cid, rep?.msg?.id) }) { r ->
            pending = pending.filter { it.msg.clientId != cid }
            r.onSuccess { m -> items = addItem(items, m) }.onFailure {
                problem = it.message
                if (text.isBlank()) text = body
            }
        }
    }

    fun react(id: String, emoji: String) {
        val mine = reacts[id]?.firstOrNull { it.first == me.id }?.second
        reacts = setReaction(reacts, id, me.id, if (mine == emoji) null else emoji)
        runIo({ Features.react(id, emoji) }) { r ->
            r.onFailure {
                problem = it.message
                load()
            }
        }
    }

    fun delete(x: MsgItem) {
        runIo({ Features.delete(x.msg.id) }) { r ->
            r.onSuccess {
                items = items.map { y ->
                    if (y.msg.id == x.msg.id) y.copy(msg = y.msg.copy(deleted = true, body = null)) else y
                }
                reacts = reacts - x.msg.id
            }.onFailure { problem = it.message }
        }
    }

    Ticker(20000L, "poll-" + conv.id) { load() }
    Ticker(20000L, "presence-" + conv.id) {
        if (peer != null) {
            runIo({ Api.presence(peer.id) }) { r ->
                r.onSuccess {
                    online = it.first
                    if (it.second != null) lastSeen = it.second
                }
            }
        }
    }

    OnRealtime { j ->
        when (j.optString("type")) {
            "hello" -> load()
            "message" -> {
                val x = parseItem(j.getJSONObject("message"))
                if (x.msg.conversationId == conv.id) {
                    pending = pending.filter { it.msg.clientId != x.msg.clientId }
                    items = addItem(items, x)
                    if (x.msg.senderId != me.id) typingAt = 0L
                }
            }
            "read" -> {
                val up = j.optString("up_to")
                if (j.optString("c") == conv.id && j.optString("u") != me.id && up > peerRead) peerRead = up
            }
            "typing" -> {
                if (j.optString("c") == conv.id && j.optString("u") != me.id) {
                    if (j.optBoolean("s", true)) {
                        typingAt = System.currentTimeMillis()
                        convHandler.postDelayed({
                            if (System.currentTimeMillis() - typingAt >= 5000L) typingAt = 0L
                        }, 5100L)
                    } else {
                        typingAt = 0L
                    }
                }
            }
            "reaction" -> {
                if (j.optString("c") == conv.id) {
                    val e = if (j.isNull("e")) null else j.optString("e")
                    reacts = setReaction(reacts, j.optString("m"), j.optString("u"), e)
                }
            }
            "delete" -> {
                if (j.optString("c") == conv.id) {
                    val id = j.optString("id")
                    items = items.map { y ->
                        if (y.msg.id == id) y.copy(msg = y.msg.copy(deleted = true, body = null)) else y
                    }
                    reacts = reacts - id
                }
            }
            "edit" -> {
                if (j.optString("c") == conv.id) {
                    val id = j.optString("id")
                    val body = j.optString("body")
                    val at = j.optLong("edited_at", 0L)
                    items = items.map { y ->
                        if (y.msg.id == id) y.copy(msg = y.msg.copy(body = body), editedAt = at) else y
                    }
                }
            }
            else -> {}
        }
    }

    val lastMsg = items.lastOrNull()?.msg
    LaunchedEffect(lastMsg?.id) {
        val l = lastMsg
        if (l != null && l.senderId != me.id && l.id > marked) {
            marked = l.id
            runIo({ Api.markRead(conv.id, l.id) }) { }
        }
    }

    val byId = remember(items) { items.associateBy { it.msg.id } }
    val rows = remember(items, pending) {
        val out = ArrayList<Any>()
        var lastDay = ""
        for (x in items + pending) {
            val d = dayLabel(x.msg.createdAt)
            if (d != lastDay) {
                out.add(d)
                lastDay = d
            }
            out.add(x)
        }
        out
    }

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.scrollToItem(rows.size - 1)
    }

    LaunchedEffect(jumpTo) {
        val id = jumpTo
        if (id != null) {
            val idx = rows.indexOfFirst { it is MsgItem && it.msg.id == id }
            if (idx >= 0) listState.animateScrollToItem(idx)
            jumpTo = null
        }
    }

    val subtitle = when {
        typingAt > 0L -> "typing…"
        online == true -> "online"
        lastSeen != null -> "last seen " + seenLabel(lastSeen!!)
        else -> ""
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
                Avatar(peer?.id ?: conv.id, peer?.displayName ?: "?", 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        peer?.displayName ?: "Unknown",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            fontSize = 12.sp,
                            color = if (typingAt > 0L) Ui.ink else Ui.muted
                        )
                    }
                }
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
                items(rows, key = { if (it is MsgItem) it.msg.id else it }) { row ->
                    if (row is String) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(row, fontSize = 12.sp, color = Ui.muted)
                        }
                    } else if (row is MsgItem) {
                        val m = row.msg
                        val mine = m.senderId == me.id
                        val local = m.id.startsWith("local-")
                        val seen = mine && !local && peerRead.isNotEmpty() && m.id <= peerRead
                        val tick = if (!mine) "" else if (local) "…" else if (seen) "✓✓" else "✓"
                        val rid = row.replyTo
                        val reply = row.reply ?: rid?.let { id ->
                            byId[id]?.let { t -> ReplyInfo(id, t.msg.senderId, t.msg.body, t.msg.deleted) }
                        }
                        val replyName = if (reply?.senderId == me.id) "You" else (peer?.displayName ?: "Unknown")
                        Bubble(
                            item = row,
                            mine = mine,
                            tick = tick,
                            reply = reply,
                            replyName = replyName,
                            reactions = reacts[m.id] ?: emptyList(),
                            myId = me.id,
                            onLongPress = { selected = row },
                            onReact = { e -> react(m.id, e) },
                            onQuote = { if (rid != null) jumpTo = rid }
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

            val ed = editing
            val rp = replyTo
            if (ed != null) {
                ComposerBanner("Editing message", ed.msg.body ?: "") {
                    editing = null
                    text = ""
                }
            } else if (rp != null) {
                ComposerBanner(
                    "Reply to " + (if (rp.msg.senderId == me.id) "yourself" else (peer?.displayName ?: "message")),
                    rp.msg.body ?: ""
                ) { replyTo = null }
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
                    onValueChange = {
                        text = it
                        problem = null
                        val now = System.currentTimeMillis()
                        if (editing == null && it.isNotBlank() && now - typedAt > 3000L) {
                            typedAt = now
                            Rt.send(JSONObject().put("type", "typing").put("c", conv.id).put("s", true))
                        }
                    },
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
                    IconButton(onClick = { send() }) {
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

    selected?.let { x ->
        MessageSheet(
            item = x,
            mine = x.msg.senderId == me.id,
            myEmoji = reacts[x.msg.id]?.firstOrNull { it.first == me.id }?.second,
            onReact = { e ->
                selected = null
                react(x.msg.id, e)
            },
            onCopy = {
                clipboard.setText(AnnotatedString(x.msg.body ?: ""))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                selected = null
            },
            onReply = {
                selected = null
                editing = null
                replyTo = x
            },
            onForward = {
                selected = null
                forwarding = x
            },
            onEdit = {
                selected = null
                replyTo = null
                editing = x
                text = x.msg.body ?: ""
            },
            onDelete = {
                selected = null
                confirmDelete = x
            },
            onDismiss = { selected = null }
        )
    }

    confirmDelete?.let { x ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Ui.bg,
            title = { Text("Delete message?") },
            text = { Text("This removes it for both of you.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    delete(x)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    forwarding?.let { x ->
        ForwardDialog(
            onPick = { c ->
                forwarding = null
                runIo({ Features.forward(x.msg.id, c.id) }) { r ->
                    r.onSuccess {
                        Toast.makeText(ctx, "Forwarded", Toast.LENGTH_SHORT).show()
                    }.onFailure { problem = it.message }
                }
            },
            onDismiss = { forwarding = null }
        )
    }
}
