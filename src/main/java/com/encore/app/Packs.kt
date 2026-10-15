package com.encore.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/* ------------------------------------------------------------------ */
/* sticker packs: models + api (matches /v1/packs in bots.js)           */
/* ------------------------------------------------------------------ */

data class Pack(
    val id: String,
    val shortName: String,
    val title: String,
    val kind: String,          // "sticker" | "emoji"
    val isPublic: Boolean,
    val mine: Boolean,
    val installed: Boolean,
    val count: Int,
    val installs: Int?,
    val icon: String?,
    val iconKind: String
)

data class PackItem(val id: String, val emoji: String, val kind: String)

data class PackStats(val installs: Int, val installs30: Int)

object PackApi {
    private fun pack(o: JSONObject) = Pack(
        o.getString("id"),
        o.getString("short_name"),
        o.getString("title"),
        o.optString("kind", "sticker"),
        o.optBoolean("public"),
        o.optBoolean("mine"),
        o.optBoolean("installed"),
        o.optInt("stickers"),
        if (o.isNull("installs")) null else o.optInt("installs"),
        if (o.isNull("icon")) null else o.getString("icon"),
        if (o.isNull("icon_kind")) "static" else o.getString("icon_kind")
    )

    private fun packs(j: JSONObject): List<Pack> {
        val a = j.getJSONArray("packs")
        return List(a.length()) { pack(a.getJSONObject(it)) }
    }

    private fun items(j: JSONObject): List<PackItem> {
        val a = j.optJSONArray("stickers") ?: return emptyList()
        return List(a.length()) {
            val o = a.getJSONObject(it)
            PackItem(o.getString("id"), o.optString("emoji", "🙂"), o.getString("kind"))
        }
    }

    fun mine(): List<Pack> = packs(Api.call("GET", "/v1/packs/mine"))
    fun installed(): List<Pack> = packs(Api.call("GET", "/v1/packs/installed"))
    fun search(q: String): List<Pack> = packs(Api.call("GET", "/v1/packs?q=" + Uri.encode(q)))

    fun get(ref: String): Pair<Pack, List<PackItem>> {
        val j = Api.call("GET", "/v1/packs/" + Uri.encode(ref))
        return pack(j.getJSONObject("pack")) to items(j)
    }

    /** the public pack (or my own) this sticker belongs to; null when it is in none */
    fun forSticker(stickerId: String): Pair<Pack?, List<PackItem>> {
        val j = Api.call("GET", "/v1/stickers/$stickerId/pack")
        val p = j.optJSONObject("pack") ?: return null to emptyList()
        return pack(p) to items(j)
    }

    fun create(title: String, kind: String): Pack {
        val j = Api.call("POST", "/v1/packs", JSONObject().put("title", title).put("kind", kind))
        return pack(j.getJSONObject("pack"))
    }

    fun rename(id: String, title: String) {
        Api.call("PATCH", "/v1/packs/$id", JSONObject().put("title", title))
    }

    fun publish(id: String, on: Boolean) {
        Api.call("PATCH", "/v1/packs/$id", JSONObject().put("public", on))
    }

    fun setIcon(id: String, stickerId: String?) {
        Api.call("PATCH", "/v1/packs/$id", JSONObject().put("icon_sticker", stickerId ?: JSONObject.NULL))
    }

    fun delete(id: String) {
        Api.call("DELETE", "/v1/packs/$id")
    }

    fun addSticker(packId: String, stickerId: String, emoji: String? = null) {
        val b = JSONObject().put("sticker_id", stickerId)
        if (emoji != null) b.put("emoji", emoji)
        Api.call("POST", "/v1/packs/$packId/stickers", b)
    }

    fun setEmoji(packId: String, stickerId: String, emoji: String) {
        Api.call("PATCH", "/v1/packs/$packId/stickers/$stickerId", JSONObject().put("emoji", emoji))
    }

    fun removeSticker(packId: String, stickerId: String) {
        Api.call("DELETE", "/v1/packs/$packId/stickers/$stickerId")
    }

    fun order(packId: String, ids: List<String>) {
        val a = JSONArray()
        ids.forEach { a.put(it) }
        Api.call("PUT", "/v1/packs/$packId/order", JSONObject().put("ids", a))
    }

    fun install(packId: String, on: Boolean) {
        Api.call(if (on) "PUT" else "DELETE", "/v1/packs/$packId/install")
    }

    fun stats(packId: String): PackStats {
        val s = Api.call("GET", "/v1/packs/$packId/stats").getJSONObject("stats")
        return PackStats(s.optInt("installs"), s.optInt("installs_30d"))
    }

    /** the pack new stickers (made with the Sticker Maker) go into, or null */
    fun active(): Pack? {
        val p = Api.call("GET", "/v1/me/pack/active").optJSONObject("pack") ?: return null
        return pack(p)
    }

    fun setActive(packId: String) {
        Api.call("PUT", "/v1/me/pack/active", JSONObject().put("pack_id", packId))
    }

    fun clearActive() {
        Api.call("DELETE", "/v1/me/pack/active")
    }
}

/* ------------------------------------------------------------------ */
/* small pieces                                                         */
/* ------------------------------------------------------------------ */

private val Modifier.eatTaps: Modifier get() = this.pointerInput(Unit) { detectTapGestures { } }

@Composable
fun PackIcon(p: Pack, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(Ui.soft)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        val ic = p.icon
        if (ic != null) StickerView(ic, p.iconKind, Modifier.fillMaxSize())
        else Text(p.title.take(1).uppercase(), fontWeight = FontWeight.SemiBold, color = Ui.muted)
    }
}

@Composable
private fun DialogAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = if (danger) MaterialTheme.colorScheme.error else Ui.ink,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun TextPrompt(title: String, initial: String, hint: String, max: Int, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui.bg,
        title = { Text(title) },
        text = {
            TextField(
                value = v,
                onValueChange = { if (it.length <= max) v = it },
                singleLine = true,
                placeholder = { Text(hint, color = Ui.muted) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Ui.soft,
                    unfocusedContainerColor = Ui.soft,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        },
        confirmButton = { TextButton(onClick = { onDone(v.trim()) }, enabled = v.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PackRow(p: Pack, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PackIcon(p, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val vis = if (p.mine) (if (p.isPublic) " · Public" else " · Private") else ""
            val users = p.installs?.let { " · $it added" } ?: ""
            Text(
                "@" + p.shortName + " · " + p.count + " stickers" + vis + users,
                fontSize = 12.sp,
                color = Ui.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!p.mine && p.installed) Text("Added", fontSize = 12.sp, color = Ui.muted)
    }
}

/* ------------------------------------------------------------------ */
/* pack list: Mine / Added / Find                                       */
/* ------------------------------------------------------------------ */

@Composable
fun PackListScreen(
    version: Int,
    startTab: Int,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onChanged: () -> Unit
) {
    var tab by remember { mutableIntStateOf(startTab) }
    var q by remember { mutableStateOf("") }
    var list by remember(tab, q, version) { mutableStateOf<List<Pack>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<String?>(null) } // "sticker" | "emoji" while the name dialog is open
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(tab, q, version) {
        val t = tab
        val s = q.trim()
        if (t == 2 && s.isNotEmpty()) delay(350)
        runIo({
            when (t) {
                0 -> PackApi.mine()
                1 -> PackApi.installed()
                else -> PackApi.search(s)
            }
        }) { r ->
            if (t == tab && s == q.trim()) {
                r.onSuccess {
                    list = it
                    problem = null
                }.onFailure { problem = it.message }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .eatTaps
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        ScreenBar("Sticker packs", onBack)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Mine", "Added", "Find").forEachIndexed { i, label ->
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (tab == i) Ui.onInk else Ui.ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (tab == i) Ui.ink else Ui.soft)
                        .clickable { tab = i }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }

        if (tab == 2) {
            TextField(
                value = q,
                onValueChange = { q = it },
                placeholder = { Text("Search packs", color = Ui.muted) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Ui.soft,
                    unfocusedContainerColor = Ui.soft,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        if (tab == 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("sticker" to "New pack", "emoji" to "New emoji pack").forEach { (k, label) ->
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Ui.onInk,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Ui.ink)
                            .clickable(enabled = !busy) { naming = k }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        val l = list
        if (l == null) {
            Text("Loading…", color = Ui.muted, modifier = Modifier.padding(20.dp))
        } else if (l.isEmpty()) {
            Text(
                when (tab) {
                    0 -> "You have no packs yet. Tap New pack."
                    1 -> "Packs you add from others show up here."
                    else -> "No packs found."
                },
                color = Ui.muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(l, key = { it.id }) { p -> PackRow(p) { onOpen(p.id) } }
            }
        }
    }

    naming?.let { kind ->
        TextPrompt(
            title = if (kind == "emoji") "Name your emoji pack" else "Name your pack",
            initial = "",
            hint = "Pack name",
            max = 64,
            onDone = { name ->
                naming = null
                busy = true
                problem = null
                runIo({ PackApi.create(name, kind) }) { r ->
                    busy = false
                    r.onSuccess {
                        onChanged()
                        onOpen(it.id)
                    }.onFailure { problem = it.message }
                }
            },
            onDismiss = { naming = null }
        )
    }
}

/* ------------------------------------------------------------------ */
/* picker: one of my stickers -> into this pack                         */
/* ------------------------------------------------------------------ */

@Composable
private fun PickStickerDialog(exclude: Set<String>, noVideo: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf<List<Sticker>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runIo({ BotApi.stickers("mine") }) { r ->
            r.onSuccess { l -> list = l.filter { it.id !in exclude && !(noVideo && it.kind == "video") } }
                .onFailure { problem = it.message }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Ui.bg)
                .padding(16.dp)
        ) {
            Text("Add a sticker", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            val l = list
            when {
                problem != null -> Text(problem ?: "", color = MaterialTheme.colorScheme.error)
                l == null -> Text("Loading…", color = Ui.muted)
                l.isEmpty() -> Text("No other stickers of yours to add. Make one with the Sticker Maker.", color = Ui.muted, fontSize = 14.sp)
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    gridItems(l, key = { it.id }) { s ->
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Ui.soft)
                                .clickable { onPick(s.id) }
                                .padding(8.dp)
                        ) { StickerView(s.id, s.kind, Modifier.fillMaxSize()) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            DialogAction("Close", onClick = onDismiss)
        }
    }
}

/* ------------------------------------------------------------------ */
/* one pack: owner can edit everything, others can add / remove it      */
/* ------------------------------------------------------------------ */

@Composable
fun PackDetailScreen(ref: String, onBack: () -> Unit, onChanged: () -> Unit) {
    var pack by remember(ref) { mutableStateOf<Pack?>(null) }
    var items by remember(ref) { mutableStateOf<List<PackItem>>(emptyList()) }
    var stats by remember(ref) { mutableStateOf<PackStats?>(null) }
    var activeId by remember(ref) { mutableStateOf<String?>(null) }
    var problem by remember(ref) { mutableStateOf<String?>(null) }
    var busy by remember(ref) { mutableStateOf(false) }
    var renaming by remember(ref) { mutableStateOf(false) }
    var confirmDelete by remember(ref) { mutableStateOf(false) }
    var picking by remember(ref) { mutableStateOf(false) }
    var selected by remember(ref) { mutableStateOf<PackItem?>(null) }
    var emojiFor by remember(ref) { mutableStateOf<PackItem?>(null) }

    fun reload() {
        runIo({
            val (p, its) = PackApi.get(ref)
            val st = if (p.mine) PackApi.stats(p.id) else null
            val act = if (p.mine) PackApi.active()?.id else null
            Triple(p to its, st, act)
        }) { r ->
            r.onSuccess { (pi, st, act) ->
                pack = pi.first
                items = pi.second
                stats = st
                activeId = act
            }.onFailure { problem = it.message }
        }
    }

    LaunchedEffect(ref) { reload() }

    fun act(call: () -> Unit) {
        busy = true
        problem = null
        runIo(call) { r ->
            busy = false
            r.onSuccess {
                reload()
                onChanged()
            }.onFailure { problem = it.message }
        }
    }

    val p = pack
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .eatTaps
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenBar(p?.title ?: "Pack", onBack)
        if (p == null) {
            Text(problem ?: "Loading…", color = if (problem != null) MaterialTheme.colorScheme.error else Ui.muted, modifier = Modifier.padding(20.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth()) {
                        val users = p.installs?.let { " · $it added" } ?: ""
                        Text(
                            "@" + p.shortName + " · " + p.count + " stickers" + users,
                            fontSize = 13.sp,
                            color = Ui.muted
                        )
                        stats?.let {
                            Text("Last 30 days: ${it.installs30} added", fontSize = 12.sp, color = Ui.muted)
                        }
                        Spacer(Modifier.height(12.dp))
                        if (p.mine) {
                            DialogAction("Rename") { renaming = true }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Ui.soft)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Public", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (p.isPublic) "Anyone can find and add this pack" else "Only you can see it",
                                        fontSize = 12.sp,
                                        color = Ui.muted
                                    )
                                }
                                Switch(
                                    checked = p.isPublic,
                                    enabled = !busy,
                                    onCheckedChange = { on -> act { PackApi.publish(p.id, on) } },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = Ui.ink,
                                        checkedThumbColor = Ui.onInk,
                                        uncheckedTrackColor = Ui.soft,
                                        uncheckedThumbColor = Ui.muted,
                                        uncheckedBorderColor = Ui.line
                                    )
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Ui.soft)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (activeId == p.id) "New stickers you make go into this pack" else "Make new stickers go into this pack",
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (activeId == p.id) "Stop" else "Fill",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Ui.onInk,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Ui.ink)
                                        .clickable(enabled = !busy) {
                                            if (activeId == p.id) act { PackApi.clearActive() } else act { PackApi.setActive(p.id) }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 7.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Add one of my stickers",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Ui.onInk,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Ui.ink)
                                    .clickable(enabled = !busy) { picking = true }
                                    .padding(vertical = 12.dp)
                            )
                        } else {
                            val label = when {
                                busy -> "..."
                                p.installed -> "Remove ${p.count} stickers"
                                else -> "Add ${p.count} stickers"
                            }
                            Text(
                                label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (p.installed) MaterialTheme.colorScheme.error else Ui.onInk,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (p.installed) Ui.soft else Ui.ink)
                                    .clickable(enabled = !busy) { act { PackApi.install(p.id, !p.installed) } }
                                    .padding(vertical = 14.dp)
                            )
                        }
                        problem?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }

                gridItems(items, key = { it.id }) { s ->
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Ui.soft)
                            .clickable(enabled = p.mine) { selected = s }
                            .padding(6.dp)
                    ) {
                        StickerView(s.id, s.kind, Modifier.fillMaxSize())
                        Text(s.emoji, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomEnd))
                    }
                }

                if (p.mine) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (confirmDelete) "Tap again to delete this pack" else "Delete pack",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = !busy) {
                                    if (!confirmDelete) {
                                        confirmDelete = true
                                    } else {
                                        busy = true
                                        runIo({ PackApi.delete(p.id) }) { r ->
                                            busy = false
                                            r.onSuccess {
                                                onChanged()
                                                onBack()
                                            }.onFailure { problem = it.message }
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }

    if (p != null && p.mine) {
        if (renaming) {
            TextPrompt("Rename pack", p.title, "Pack name", 64, onDone = { t ->
                renaming = false
                act { PackApi.rename(p.id, t) }
            }, onDismiss = { renaming = false })
        }

        if (picking) {
            PickStickerDialog(
                exclude = items.map { it.id }.toSet(),
                noVideo = p.kind == "emoji",
                onPick = { sid ->
                    picking = false
                    act { PackApi.addSticker(p.id, sid) }
                },
                onDismiss = { picking = false }
            )
        }

        emojiFor?.let { s ->
            TextPrompt("Emoji for this sticker", s.emoji, "Emoji", 8, onDone = { e ->
                emojiFor = null
                act { PackApi.setEmoji(p.id, s.id, e) }
            }, onDismiss = { emojiFor = null })
        }

        selected?.let { s ->
            val idx = items.indexOfFirst { it.id == s.id }
            Dialog(onDismissRequest = { selected = null }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Ui.bg)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Ui.soft)
                            .padding(14.dp)
                    ) { StickerView(s.id, s.kind, Modifier.fillMaxSize()) }
                    Spacer(Modifier.height(8.dp))
                    Text("Emoji: " + s.emoji, fontSize = 14.sp, color = Ui.muted)
                    Spacer(Modifier.height(6.dp))
                    DialogAction("Change emoji") {
                        emojiFor = s
                        selected = null
                    }
                    if (s.kind != "video") {
                        DialogAction("Use as pack picture") {
                            selected = null
                            act { PackApi.setIcon(p.id, s.id) }
                        }
                    }
                    if (idx > 0) {
                        DialogAction("Move earlier") {
                            selected = null
                            val ids = items.map { it.id }.toMutableList()
                            java.util.Collections.swap(ids, idx, idx - 1)
                            act { PackApi.order(p.id, ids) }
                        }
                    }
                    if (idx >= 0 && idx < items.lastIndex) {
                        DialogAction("Move later") {
                            selected = null
                            val ids = items.map { it.id }.toMutableList()
                            java.util.Collections.swap(ids, idx, idx + 1)
                            act { PackApi.order(p.id, ids) }
                        }
                    }
                    DialogAction("Remove from pack", danger = true) {
                        selected = null
                        act { PackApi.removeSticker(p.id, s.id) }
                    }
                    DialogAction("Close") { selected = null }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* tap a sticker in a chat -> its pack -> Add / Remove (like Telegram)  */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPackSheet(stickerId: String, onClose: () -> Unit, onChanged: () -> Unit = {}) {
    var res by remember(stickerId) { mutableStateOf<Pair<Pack?, List<PackItem>>?>(null) }
    var problem by remember(stickerId) { mutableStateOf<String?>(null) }
    var busy by remember(stickerId) { mutableStateOf(false) }

    LaunchedEffect(stickerId) {
        runIo({ PackApi.forSticker(stickerId) }) { r ->
            r.onSuccess { res = it }.onFailure { problem = it.message }
        }
    }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = Ui.bg) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
        ) {
            val cur = res
            val pack = cur?.first
            when {
                cur == null && problem == null -> {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Ui.muted)
                    }
                }
                cur == null -> {
                    Text(problem ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
                pack == null -> {
                    Text("This sticker is not part of a public pack.", color = Ui.muted, modifier = Modifier.padding(vertical = 12.dp))
                    Box(Modifier.size(160.dp).align(Alignment.CenterHorizontally).padding(8.dp)) {
                        StickerView(stickerId, "static", Modifier.fillMaxSize())
                    }
                }
                else -> {
                    Text(pack.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    val users = pack.installs?.let { " · $it added" } ?: ""
                    Text("@" + pack.shortName + " · " + pack.count + " stickers" + users, fontSize = 13.sp, color = Ui.muted)
                    Spacer(Modifier.height(12.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)
                    ) {
                        gridItems(cur.second, key = { it.id }) { s ->
                            Box(Modifier.aspectRatio(1f).padding(2.dp)) {
                                StickerView(s.id, s.kind, Modifier.fillMaxSize())
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (pack.mine) {
                        Text("This is your pack", color = Ui.muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    } else {
                        val label = when {
                            busy -> "..."
                            pack.installed -> "Remove ${pack.count} stickers"
                            else -> "Add ${pack.count} stickers"
                        }
                        Text(
                            label,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (pack.installed) MaterialTheme.colorScheme.error else Ui.onInk,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (pack.installed) Ui.soft else Ui.ink)
                                .clickable(enabled = !busy) {
                                    busy = true
                                    problem = null
                                    val want = !pack.installed
                                    runIo({ PackApi.install(pack.id, want) }) { r ->
                                        busy = false
                                        r.onSuccess {
                                            res = pack.copy(installed = want) to cur.second
                                            onChanged()
                                        }.onFailure { problem = it.message }
                                    }
                                }
                                .padding(vertical = 14.dp)
                        )
                    }
                    problem?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}
