package com.encore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CreateGroupScreen(onBack: () -> Unit, onCreated: (Conv) -> Unit) {
    var title by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun addName() {
        val n = input.trim().lowercase().removePrefix("@")
        if (n.isNotEmpty() && n !in names) names = names + n
        input = ""
    }

    fun create() {
        busy = true
        problem = null
        val t = title
        val l = names
        runIo({ Api.createGroup(t, l) }) { r ->
            busy = false
            r.onSuccess { onCreated(it) }.onFailure { problem = it.message }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        ScreenBar("New group", onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Group name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            Text("Members", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Add by username") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { addName() }, enabled = input.isNotBlank()) { Text("Add") }
            }
            names.forEach { n ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("@$n", fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { names = names - n }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Ui.muted)
                    }
                }
            }
            problem?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
        Button(
            onClick = { create() },
            enabled = !busy && title.isNotBlank() && names.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) { Text(if (busy) "Creating" else "Create group") }
    }
}

@Composable
fun GroupInfoScreen(
    me: User,
    conv: Conv,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    onChanged: (Conv) -> Unit
) {
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var title by remember { mutableStateOf(conv.peer?.displayName ?: "Group") }
    var muted by remember { mutableStateOf(conv.muted) }
    var msg by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Member?>(null) }

    fun loadMembers() {
        runIo({ Api.members(conv.id) }) { r ->
            r.onSuccess { members = it }.onFailure { msg = it.message }
        }
    }

    LaunchedEffect(conv.id) { loadMembers() }
    OnRealtime { e -> if (e.optString("type") == "message") loadMembers() }

    val myRole = members.firstOrNull { it.id == me.id }?.role
    val admin = myRole == "owner" || myRole == "admin"

    val pick = rememberAvatarPicker(
        onPicked = { bytes, type ->
            runIo({ Api.setGroupAvatar(conv.id, bytes, type) }) { r -> r.onFailure { msg = it.message } }
        },
        onError = { msg = it }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenBar("Group info", onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(60.dp))
                    .then(if (admin) Modifier.clickable { pick() } else Modifier)
            ) {
                Avatar(conv.id, title, 112.dp)
            }
            if (admin) Text("Change photo", color = Ui.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (admin) Modifier.clickable { rename = true } else Modifier)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                (if (members.isNotEmpty()) members.size else conv.memberCount).toString() + " members",
                color = Ui.muted,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Ui.line)
            SettingRow("Mute notifications") {
                Switch(
                    checked = muted,
                    onCheckedChange = { on ->
                        muted = on
                        runIo({ Api.mute(conv.id, on) }) { r ->
                            r.onFailure {
                                muted = !on
                                msg = it.message
                            }
                        }
                    }
                )
            }
            HorizontalDivider(color = Ui.line)
            if (admin) {
                SettingRow("Add members", onClick = { addDialog = true })
                HorizontalDivider(color = Ui.line)
            }
            Spacer(Modifier.height(8.dp))
            Text("Members", fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(4.dp))
            members.forEach { m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(m.id, m.displayName, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (m.id == me.id) m.displayName + " (you)" else m.displayName,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Badge(m.id, 14.sp, name = m.displayName)
                        }
                        Text("@" + m.username, color = Ui.muted, fontSize = 13.sp)
                    }
                    if (m.role == "owner" || m.role == "admin") {
                        Text(m.role, color = Ui.muted, fontSize = 12.sp)
                    } else if (admin && m.id != me.id) {
                        IconButton(onClick = { removing = m }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = Ui.muted)
                        }
                    }
                }
            }
            HorizontalDivider(color = Ui.line)
            SettingRow("Leave group", color = MaterialTheme.colorScheme.error, onClick = { leave = true })
            msg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (rename) {
        var t by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { rename = false },
            containerColor = Ui.bg,
            title = { Text("Group name") },
            text = {
                OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = t.isNotBlank(),
                    onClick = {
                        rename = false
                        val nt = t.trim()
                        runIo({ Api.renameGroup(conv.id, nt) }) { r ->
                            r.onSuccess {
                                title = nt
                                onChanged(conv.copy(peer = conv.peer?.copy(displayName = nt)))
                            }.onFailure { msg = it.message }
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } }
        )
    }

    if (addDialog) {
        var name by remember { mutableStateOf("") }
        var problem by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            containerColor = Ui.bg,
            title = { Text("Add member") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Username") },
                        singleLine = true
                    )
                    problem?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val n = name.trim().lowercase().removePrefix("@")
                        runIo({ Api.addMembers(conv.id, listOf(n)) }) { r ->
                            r.onSuccess {
                                addDialog = false
                                loadMembers()
                            }.onFailure { problem = it.message }
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Cancel") } }
        )
    }

    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Ui.bg,
            title = { Text("Remove ${m.displayName}?") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    runIo({ Api.removeMember(conv.id, m.id) }) { r ->
                        r.onSuccess { loadMembers() }.onFailure { msg = it.message }
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }

    if (leave) {
        AlertDialog(
            onDismissRequest = { leave = false },
            containerColor = Ui.bg,
            title = { Text("Leave this group?") },
            confirmButton = {
                TextButton(onClick = {
                    leave = false
                    runIo({ Api.removeMember(conv.id, me.id) }) { r ->
                        r.onSuccess { onLeft() }.onFailure { msg = it.message }
                    }
                }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { leave = false }) { Text("Cancel") } }
        )
    }
}
