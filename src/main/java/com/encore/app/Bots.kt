package com.encore.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
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
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

data class BotButton(val id: String, val label: String)

data class Bot(
    val id: String,
    val handle: String,
    val name: String,
    val description: String?,
    val verified: Boolean,
    val buttons: List<BotButton>,
    val monthlyUsers: Int?,
    val added: Boolean
)

data class BotMsg(
    val id: String,
    val sender: String,
    val kind: String,
    val body: String?,
    val ref: String?,
    val createdAt: Long
)

private fun JSONObject.sOrNull(key: String): String? = if (isNull(key)) null else getString(key)

object BotApi {
    private fun bot(o: JSONObject): Bot {
        val arr = o.optJSONArray("buttons") ?: JSONArray()
        val buttons = List(arr.length()) {
            val b = arr.getJSONObject(it)
            BotButton(b.getString("id"), b.getString("label"))
        }
        return Bot(
            o.getString("id"),
            o.getString("handle"),
            o.getString("name"),
            o.sOrNull("description"),
            o.optBoolean("verified"),
            buttons,
            if (o.isNull("monthly_users")) null else o.optInt("monthly_users"),
            o.optBoolean("added")
        )
    }

    private fun list(j: JSONObject): List<Bot> {
        val a = j.getJSONArray("bots")
        return List(a.length()) { bot(a.getJSONObject(it)) }
    }

    fun parseMsg(o: JSONObject) = BotMsg(
        o.getString("id"),
        o.getString("sender"),
        o.getString("kind"),
        o.sOrNull("body"),
        o.sOrNull("ref"),
        o.getLong("created_at")
    )

    fun search(q: String): List<Bot> = list(Api.call("GET", "/v1/bots?q=" + Uri.encode(q)))

    fun mine(): List<Bot> = list(Api.call("GET", "/v1/bots/mine"))

    fun add(id: String): Bot = bot(Api.call("PUT", "/v1/bots/$id/add").getJSONObject("bot"))

    fun remove(id: String) {
        Api.call("DELETE", "/v1/bots/$id/add")
    }

    fun messages(id: String): List<BotMsg> {
        val a = Api.call("GET", "/v1/bots/$id/messages?limit=100").getJSONArray("messages")
        return List(a.length()) { parseMsg(a.getJSONObject(it)) }
    }

    fun action(id: String, action: String) {
        Api.call("POST", "/v1/bots/$id/action", JSONObject().put("action", action))
    }
}

@Composable
private fun BotSectionLabel(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = Ui.muted,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun BotListRow(b: Bot, busy: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(b.id, b.name, 46.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    b.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (b.verified) {
                    Spacer(Modifier.width(4.dp))
                    Text("\u2B50", fontSize = 13.sp)
                }
            }
            val users = b.monthlyUsers?.let { " \u00B7 $it monthly users" } ?: ""
            Text("@" + b.handle + users, fontSize = 13.sp, color = Ui.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!b.description.isNullOrBlank()) {
                Text(b.description, fontSize = 13.sp, color = Ui.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (busy) "..." else if (b.added) "Open" else "Add",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (b.added) Ui.ink else Ui.onInk,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (b.added) Ui.soft else Ui.ink)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun BotsScreen(onBack: () -> Unit, onOpen: (Bot) -> Unit) {
    var q by remember { mutableStateOf("") }
    var mine by remember { mutableStateOf<List<Bot>>(emptyList()) }
    var found by remember { mutableStateOf<List<Bot>?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runIo({ BotApi.mine() }) { r -> r.onSuccess { mine = it } }
    }

    LaunchedEffect(q) {
        val t = q.trim()
        if (t.isNotEmpty()) delay(350)
        runIo({ BotApi.search(t) }) { r ->
            if (q.trim() == t) {
                r.onSuccess {
                    found = it
                    problem = null
                }.onFailure { problem = it.message }
            }
        }
    }

    fun openOrAdd(b: Bot) {
        if (b.added) {
            onOpen(b)
            return
        }
        busyId = b.id
        runIo({ BotApi.add(b.id) }) { r ->
            busyId = null
            r.onSuccess { onOpen(it) }.onFailure { problem = it.message }
        }
    }

    val searching = q.trim().isNotEmpty()
    val shown = found ?: emptyList()
    val discover = if (searching) shown else shown.filter { !it.added }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
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
            TextField(
                value = q,
                onValueChange = { q = it },
                placeholder = { Text("Search bots", color = Ui.muted) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Ui.soft,
                    unfocusedContainerColor = Ui.soft,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f)
            )
            if (q.isNotEmpty()) {
                IconButton(onClick = { q = "" }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = Ui.muted)
                }
            }
        }

        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        if (searching && found != null && discover.isEmpty()) {
            Text("No bots found", color = Ui.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (!searching && mine.isNotEmpty()) {
                item { BotSectionLabel("My bots") }
                items(mine, key = { "m" + it.id }) { b ->
                    BotListRow(b, busyId == b.id) { openOrAdd(b) }
                }
            }
            if (discover.isNotEmpty()) {
                item { BotSectionLabel(if (searching) "Results" else "Discover") }
                items(discover, key = { "d" + it.id }) { b ->
                    BotListRow(b, busyId == b.id) { openOrAdd(b) }
                }
            }
        }
    }
}

@Composable
private fun BotBubble(m: BotMsg) {
    val mine = m.sender == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Text(
            m.body ?: "",
            color = if (mine) Ui.onInk else Ui.ink,
            fontSize = 15.sp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (mine) Ui.ink else Ui.soft)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
fun BotChatScreen(bot: Bot, onBack: () -> Unit) {
    var messages by remember { mutableStateOf<List<BotMsg>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(bot.id) {
        runIo({ BotApi.messages(bot.id) }) { r ->
            r.onSuccess { messages = it }.onFailure { problem = it.message }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    OnRealtime { e ->
        if (e.optString("type") == "bot_message") {
            val m = e.optJSONObject("message")
            if (m != null && m.optString("bot_id") == bot.id) {
                val bm = BotApi.parseMsg(m)
                if (messages.none { it.id == bm.id }) messages = messages + bm
            }
        }
    }

    fun press(b: BotButton) {
        if (busy) return
        busy = true
        problem = null
        runIo({
            BotApi.action(bot.id, b.id)
            BotApi.messages(bot.id)
        }) { r ->
            busy = false
            r.onSuccess { messages = it }.onFailure { problem = it.message }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
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
            Avatar(bot.id, bot.name, 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bot.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (bot.verified) {
                        Spacer(Modifier.width(4.dp))
                        Text("\u2B50", fontSize = 13.sp)
                    }
                }
                Text("bot", fontSize = 12.sp, color = Ui.muted)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Ui.ink)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove bot") },
                        onClick = {
                            menu = false
                            runIo({ BotApi.remove(bot.id) }) { r ->
                                r.onSuccess { onBack() }.onFailure { problem = it.message }
                            }
                        }
                    )
                }
            }
        }
        HorizontalDivider(color = Ui.line)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { m -> BotBubble(m) }
        }

        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        HorizontalDivider(color = Ui.line)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            bot.buttons.chunked(2).forEach { rowButtons ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowButtons.forEach { b ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Ui.soft)
                                .clickable(enabled = !busy) { press(b) }
                                .padding(vertical = 13.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                b.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Ui.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (rowButtons.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
