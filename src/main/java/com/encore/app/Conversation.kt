package com.encore.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun ConversationHeader(
    peer: Peer?,
    convId: String,
    subtitle: String,
    typing: Boolean,
    onBack: () -> Unit,
    soon: SoonState
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
        Avatar(peer?.id ?: convId, peer?.displayName ?: "?", 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                peer?.displayName ?: "Unknown",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 12.sp, color = if (typing) Ui.ink else Ui.muted)
            }
        }
        IconButton(onClick = { soon.show("Video calls") }) { VideoIcon(Ui.ink) }
        IconButton(onClick = { soon.show("Calls") }) {
            Icon(Icons.Default.Call, contentDescription = "Call", tint = Ui.ink)
        }
    }
}

@Composable
private fun Composer(st: ConversationState, soon: SoonState, onAttach: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onAttach) {
            Icon(Icons.Default.Add, contentDescription = "Attach", tint = Ui.ink)
        }
        TextField(
            value = st.text,
            onValueChange = { st.onTextChange(it) },
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
        if (st.text.isBlank()) {
            IconButton(onClick = { soon.show("Voice messages") }) { MicIcon(Ui.ink) }
        } else {
            IconButton(onClick = { st.send() }) {
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

@Composable
fun ConversationScreen(me: User, conv: Conv, onBack: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val st = remember(conv.id) { ConversationState(me, conv, ctx.applicationContext, onLogout) }
    val peer = conv.peer
    var selected by remember { mutableStateOf<MsgItem?>(null) }
    var confirmDelete by remember { mutableStateOf<MsgItem?>(null) }
    var forwarding by remember { mutableStateOf<MsgItem?>(null) }
    var viewer by remember { mutableStateOf<MsgItem?>(null) }
    var attach by remember { mutableStateOf(false) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    val soon = remember { SoonState() }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) st.sendMedia(uri, "image")
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) st.sendMedia(uri, "file")
    }

    Ticker(20000L, "poll-" + conv.id) { st.load() }
    Ticker(20000L, "presence-" + conv.id) { st.refreshPresence() }
    OnRealtime { st.onEvent(it) }

    val lastId = st.items.lastOrNull()?.msg?.id
    LaunchedEffect(lastId) { st.markReadIfNeeded() }

    val byId = remember(st.items) { st.items.associateBy { it.msg.id } }
    val rows = remember(st.items, st.pending) { buildRows(st.items + st.pending) }

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

    val typing = st.typingAt > 0L
    val lastSeen = st.lastSeen
    val subtitle = when {
        typing -> "typing…"
        st.online == true -> "online"
        lastSeen != null -> "last seen " + seenLabel(lastSeen)
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
            ConversationHeader(peer, conv.id, subtitle, typing, onBack, soon)
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
                        val seen = mine && !local && st.peerRead.isNotEmpty() && m.id <= st.peerRead
                        val tick = if (!mine) "" else if (local) "…" else if (seen) "✓✓" else "✓"
                        val rid = row.replyTo
                        val reply = row.reply ?: rid?.let { id ->
                            byId[id]?.let { t -> ReplyInfo(id, t.msg.senderId, t.msg.body, t.msg.deleted, t.msg.kind) }
                        }
                        val replyName = if (reply?.senderId == me.id) "You" else (peer?.displayName ?: "Unknown")
                        Bubble(
                            cid = conv.id,
                            item = row,
                            mine = mine,
                            tick = tick,
                            reply = reply,
                            replyName = replyName,
                            reactions = st.reacts[m.id] ?: emptyList(),
                            myId = me.id,
                            progress = st.progress[m.id],
                            onLongPress = { selected = row },
                            onReact = { e -> st.react(m.id, e) },
                            onQuote = { if (rid != null) jumpTo = rid },
                            onImage = { viewer = row },
                            onFile = { row.media?.let { Uploads.download(ctx, conv.id, m.id, it.name) } }
                        )
                    }
                }
            }

            st.problem?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            val ed = st.editing
            val rp = st.replyTo
            if (ed != null) {
                ComposerBanner("Editing message", ed.msg.body ?: "") { st.cancelEdit() }
            } else if (rp != null) {
                ComposerBanner(
                    "Reply to " + (if (rp.msg.senderId == me.id) "yourself" else (peer?.displayName ?: "message")),
                    rp.msg.body ?: kindLabel(rp.msg.kind)
                ) { st.replyTo = null }
            }

            Composer(st, soon) { attach = true }
        }

        SoonToast(soon, 80.dp)
    }

    selected?.let { x ->
        MessageSheet(
            item = x,
            mine = x.msg.senderId == me.id,
            myEmoji = st.reacts[x.msg.id]?.firstOrNull { it.first == me.id }?.second,
            onReact = { e ->
                selected = null
                st.react(x.msg.id, e)
            },
            onCopy = {
                clipboard.setText(AnnotatedString(x.msg.body ?: ""))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                selected = null
            },
            onReply = {
                selected = null
                st.startReply(x)
            },
            onForward = {
                selected = null
                forwarding = x
            },
            onEdit = {
                selected = null
                st.startEdit(x)
            },
            onDelete = {
                selected = null
                confirmDelete = x
            },
            onDismiss = { selected = null }
        )
    }

    if (attach) {
        AttachSheet(
            onPhoto = {
                attach = false
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onFile = {
                attach = false
                pickFile.launch(arrayOf("*/*"))
            },
            onSoon = {
                attach = false
                soon.show(it)
            },
            onDismiss = { attach = false }
        )
    }

    viewer?.let { v ->
        ImageViewer(conv.id, v, onClose = { viewer = null })
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
                    st.delete(x)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    forwarding?.let { x ->
        ForwardDialog(
            onPick = { c ->
                forwarding = null
                st.forward(x, c)
            },
            onDismiss = { forwarding = null }
        )
    }
}
