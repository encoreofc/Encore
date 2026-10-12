package com.encore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val EMOJIS = listOf("🔥", "🙌", "😭", "🙈", "🙏", "😖", "❤️", "👍", "😂", "😮")

@Composable
internal fun MenuRow(label: String, color: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
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
internal fun MessageSheet(
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
                    item.msg.body ?: kindLabel(item.msg.kind),
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
            if (!item.msg.body.isNullOrBlank()) {
                MenuRow("Copy", Ui.ink, onCopy) { CopyIcon(Ui.ink) }
                HorizontalDivider(color = Ui.line)
            }
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
internal fun ForwardDialog(onPick: (Conv) -> Unit, onDismiss: () -> Unit) {
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
internal fun ComposerBanner(title: String, body: String, onClose: () -> Unit) {
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
