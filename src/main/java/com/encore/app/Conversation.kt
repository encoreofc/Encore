package com.encore.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun ConversationHeader(
    peer: Peer?,
    convId: String,
    subtitle: String,
    typing: Boolean,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    soon: SoonState,
    onCall: () -> Unit
) {
    // Telegram chat header: 56dp bar, back button 54dp wide with a 24dp icon, 42dp avatar starting 60dp from the edge,
    // title 18 / subtitle 14 starting 13dp after the avatar, menu buttons 48dp with 24dp icons
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp, 56.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ui.ink, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(6.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable { onInfo() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(peer?.id ?: convId, peer?.displayName ?: "?", 42.dp)
            Spacer(Modifier.width(13.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        peer?.displayName ?: "Unknown",
                        fontSize = fs(18f),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Badge(peer?.id, name = peer?.displayName)
                }
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, fontSize = fs(14f), color = if (typing) Ui.ink else Ui.muted, maxLines = 1)
                }
            }
        }
        IconButton(onClick = { soon.show("Video calls") }, modifier = Modifier.size(48.dp)) { VideoIcon(Ui.ink) }
        IconButton(onClick = onCall, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Call, contentDescription = "Call", tint = Ui.ink, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Composer(
    st: ConversationState,
    voice: VoiceController,
    panelOpen: Boolean,
    focus: FocusRequester,
    onSticker: () -> Unit,
    onFieldTap: () -> Unit,
    onAttach: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    LaunchedEffect(src) {
        src.interactions.collect { if (it is PressInteraction.Release) onFieldTap() }
    }
    if (voice.recording) {
        RecordingBar(voice)
        return
    }
    // Telegram input bar: buttons 44x44dp, field at least 44dp tall with 18 text, 24dp icons
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        IconButton(onClick = onAttach, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Attach", tint = Ui.ink, modifier = Modifier.size(24.dp))
        }
        BasicTextField(
            value = st.text,
            onValueChange = { st.onTextChange(it) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = fs(18f), color = Ui.ink),
            cursorBrush = SolidColor(Ui.ink),
            interactionSource = src,
            maxLines = 4,
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Ui.soft),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // sticker button inside the field, left side; turns into a keyboard button while the panel is open
                    IconButton(onClick = onSticker, modifier = Modifier.size(44.dp)) {
                        Crossfade(panelOpen, animationSpec = tween(150), label = "stk") { open ->
                            if (open) KeyboardGlyph(Ui.ink) else StickerGlyph(Ui.ink)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 16.dp, top = 11.dp, bottom = 11.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (st.text.isEmpty()) {
                            Text("Message", color = Ui.muted, fontSize = fs(18f))
                        }
                        inner()
                    }
                }
            }
        )
        if (st.text.isBlank()) {
            IconButton(onClick = { voice.onMicClick() }, modifier = Modifier.size(44.dp)) { MicIcon(Ui.ink) }
        } else {
            IconButton(onClick = { st.send() }, modifier = Modifier.size(44.dp)) {
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
fun ConversationScreen(
    me: User,
    conv: Conv,
    startJump: String?,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onInfo: () -> Unit
) {
    val ctx = LocalContext.current

    // Keyboard fix: make the window resize (not pan) while this chat is open, so the
    // content never gets pushed up above the keyboard. Restored when leaving the chat.
    val activity = remember(ctx) { ctx.findActivity() }
    DisposableEffect(activity) {
        val w = activity?.window
        val old = w?.attributes?.softInputMode
        w?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose {
            if (w != null && old != null) w.setSoftInputMode(old)
        }
    }

    val st = remember(conv.id) { ConversationState(me, conv, ctx.applicationContext, onLogout) }
    DisposableEffect(st) {
        onDispose { st.close() }
    }
    val voice = rememberVoice(st)
    val peer = conv.peer
    val isGroup = conv.type == "group"
    var members by remember { mutableStateOf<Map<String, Member>>(emptyMap()) }
    var selected by remember { mutableStateOf<MsgItem?>(null) }
    var confirmDelete by remember { mutableStateOf<MsgItem?>(null) }
    var forwarding by remember { mutableStateOf<MsgItem?>(null) }
    var viewer by remember { mutableStateOf<MsgItem?>(null) }
    var player by remember { mutableStateOf<MsgItem?>(null) }
    var once by remember { mutableStateOf<OnceSession?>(null) }
    var attach by remember { mutableStateOf(false) }
    var jumpTo by remember { mutableStateOf(startJump) }
    val soon = remember { SoonState() }
    val micGate = rememberMicGate()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var gallery by remember { mutableStateOf(false) }
    var packFor by remember { mutableStateOf<String?>(null) }
    var toEdit by remember { mutableStateOf<List<GalleryItem>?>(null) }

    // ---- sticker panel: takes the keyboard's place at the same height, no jump ----
    // 0 = keyboard / closed, 1 = panel open, 2 = going back to the keyboard
    var stickerMode by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeNet = with(density) {
        (WindowInsets.ime.getBottom(density) - WindowInsets.navigationBars.getBottom(density))
            .coerceAtLeast(0).toDp()
    }
    var kbH by remember { mutableStateOf(StickerPrefs.keyboardDp(ctx).dp) }
    LaunchedEffect(imeNet) {
        if (imeNet > 150.dp && imeNet >= kbH) kbH = imeNet
        if (stickerMode == 2 && imeNet >= kbH - 8.dp) stickerMode = 0
    }
    LaunchedEffect(stickerMode) {
        if (stickerMode == 1) StickerPrefs.setKeyboardDp(ctx, kbH.value)
        if (stickerMode == 2) {
            kotlinx.coroutines.delay(700)
            if (stickerMode == 2) stickerMode = 0
        }
    }
    val reveal by animateFloatAsState(
        if (stickerMode == 0) 0f else 1f,
        tween(240, easing = FastOutSlowInEasing),
        label = "stickerReveal"
    )
    // while the keyboard slides, the panel gives up exactly as much height as the keyboard takes
    val panelH = (kbH * reveal - imeNet).coerceAtLeast(0.dp)
    if (stickerMode != 0) BackHandler { stickerMode = 0 }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val isVideo = ctx.contentResolver.getType(uri)?.startsWith("video/") == true
            st.sendMedia(uri, if (isVideo) "video" else "image")
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) st.sendMedia(uri, "file")
    }

    Ticker(20000L, "poll-" + conv.id) { st.load() }
    Ticker(20000L, "presence-" + conv.id) { if (!isGroup) st.refreshPresence() }
    OnRealtime { st.onEvent(it) }

    val lastId = st.items.lastOrNull()?.msg?.id
    LaunchedEffect(lastId) { st.markReadIfNeeded() }

    val systemCount = st.items.count { it.msg.kind == "system" }
    LaunchedEffect(conv.id, systemCount) {
        if (isGroup) {
            runIo({ Api.members(conv.id) }) { r -> r.onSuccess { l -> members = l.associateBy { it.id } } }
        }
    }

    val byId = remember(st.items) { st.items.associateBy { it.msg.id } }
    val rows = remember(st.items, st.pending) { buildRows(st.items + st.pending) }

    // Newest message is index 0 (reverseLayout), so the chat always opens at the bottom
    // with no jump, and stays pinned to the bottom when the keyboard opens.
    val rev = remember(rows) { rows.reversed() }

    var wasNear by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collect { (scrolling, idx) -> if (scrolling) wasNear = idx <= 1 }
    }

    val newest = rows.lastOrNull()
    val newestKey: Any? = if (newest is MsgItem) newest.msg.id else newest
    LaunchedEffect(newestKey) {
        if (newest != null && jumpTo == null) {
            val mineNew = newest is MsgItem && newest.msg.senderId == me.id
            if (wasNear || mineNew) listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(jumpTo, rows.size) {
        val id = jumpTo
        if (id != null && rows.isNotEmpty()) {
            val idx = rev.indexOfFirst { it is MsgItem && it.msg.id == id }
            if (idx >= 0) {
                listState.animateScrollToItem(idx, -(listState.layoutInfo.viewportSize.height / 3))
            } else {
                Toast.makeText(ctx, "That message is older. Scroll up to find it.", Toast.LENGTH_SHORT).show()
                listState.scrollToItem(0)
            }
            jumpTo = null
        }
    }

    val typing = st.typingAt > 0L
    val lastSeen = st.lastSeen
    val subtitle = when {
        typing -> "typing…"
        isGroup -> (if (members.isNotEmpty()) members.size else conv.memberCount).toString() + " members"
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
                ConversationHeader(peer, conv.id, subtitle, typing, onBack, onInfo, soon) {
                if (isGroup) soon.show("Group calls")
                else micGate { CallManager.start(conv.id, peer?.id ?: "", peer?.displayName ?: "") }
            }
            HorizontalDivider(color = Ui.line)

            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(rev, key = { if (it is MsgItem) it.msg.id else it }) { row ->
                    if (row is String) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // Telegram-style date pill: 14sp bold text, 11dp corners
                            Text(
                                row,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ui.muted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(Ui.soft)
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    } else if (row is MsgItem) {
                        val m = row.msg
                        if (m.kind == "system") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp, horizontal = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // Telegram-style service pill: 14sp bold, 11dp corners, max width 272dp
                                Text(
                                    m.body ?: "",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Ui.muted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .widthIn(max = 272.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(Ui.soft)
                                        .padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        } else {
                            val mine = m.senderId == me.id
                            val local = m.id.startsWith("local-")
                            val seen = mine && !isGroup && !local && st.peerRead.isNotEmpty() && m.id <= st.peerRead
                            val tick = if (!mine) "" else if (local) "…" else if (seen) "✓✓" else "✓"
                            val rid = row.replyTo
                            val reply = row.reply ?: rid?.let { id ->
                                byId[id]?.let { t -> ReplyInfo(id, t.msg.senderId, t.msg.body, t.msg.deleted, t.msg.kind) }
                            }
                            val replyName = if (reply?.senderId == me.id) "You"
                            else if (isGroup) (members[reply?.senderId]?.displayName ?: "Member")
                            else (peer?.displayName ?: "Unknown")
                            val senderName = if (isGroup && !mine) (members[m.senderId]?.displayName ?: "…") else null
                            Bubble(
                                cid = conv.id,
                                item = row,
                                mine = mine,
                                tick = tick,
                                senderName = senderName,
                                reply = reply,
                                replyName = replyName,
                                reactions = st.reacts[m.id] ?: emptyList(),
                                myId = me.id,
                                progress = st.progress[m.id],
                                onLongPress = { selected = row },
                                onReact = { e -> st.react(m.id, e) },
                                onQuote = { if (rid != null) jumpTo = rid },
                                onImage = { viewer = row },
                                onFile = { row.media?.let { Uploads.download(ctx, conv.id, m.id, it.name) } },
                                onVideo = { player = row },
                                onOnce = { st.openOnce(row) { once = it } }
                            )
                        }
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
                    msgPreview(rp.msg.kind, rp.msg.body)
                ) { st.replyTo = null }
            }

            Composer(
                st = st,
                voice = voice,
                panelOpen = stickerMode == 1,
                focus = focus,
                onSticker = {
                    if (stickerMode == 1) {
                        stickerMode = 2
                        focus.requestFocus()
                        keyboard?.show()
                    } else {
                        stickerMode = 1
                        keyboard?.hide()
                    }
                },
                onFieldTap = {
                    if (stickerMode == 1) {
                        stickerMode = 2
                        keyboard?.show()
                    }
                },
                onAttach = { attach = true }
            )

            if (panelH > 0.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(panelH)
                        .clipToBounds(),
                    contentAlignment = Alignment.TopStart
                ) {
                    StickerPanel(Modifier.fillMaxWidth().requiredHeight(kbH)) { s -> st.sendSticker(s) }
                }
            }
        }

        SoonToast(soon, 80.dp)

        if (gallery) {
            MediaPicker(
                onClose = { gallery = false },
                onDenied = {
                    // No gallery permission: fall back to the system photo picker (needs none)
                    gallery = false
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onEdit = { picked ->
                    gallery = false
                    toEdit = picked
                },
                onSend = { picked ->
                    gallery = false
                    scope.launch { quickExport(ctx, picked).forEach { st.sendEdited(it) } }
                }
            )
        }
        toEdit?.let { list ->
            MediaEditor(
                items = list,
                onClose = { toEdit = null },
                onSend = { out ->
                    toEdit = null
                    out.forEach { st.sendEdited(it) }
                }
            )
        }
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
                gallery = true
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

    packFor?.let { sid ->
        StickerPackSheet(sid, onClose = { packFor = null }, onChanged = { StickerCache.refresh(ctx) })
    }

    viewer?.let { v ->
        ImageViewer(conv.id, v, onClose = { viewer = null })
    }

    player?.let { v ->
        VideoPlayerDialog(conv.id, v, onClose = { player = null })
    }

    once?.let { s ->
        OnceViewer(conv.id, s, onClose = { once = null })
    }

    confirmDelete?.let { x ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Ui.bg,
            title = { Text("Delete message?") },
            text = { Text("This removes it for everyone in the chat.") },
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
