package com.encore.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Contacts: people you chose to keep. They are saved on the server, so they
// follow you to every device. Nothing here depends on a phone number: a person
// is found by their username.
// ---------------------------------------------------------------------------

private val ContactGold = Color(0xFFFFC400)
private val ContactOnGold = Color(0xFF111111)

/** One person in your contacts. [firstName] and [lastName] are the name you gave them; empty means their own name is used. */
data class ContactEntry(
    val id: String,
    val username: String,
    val displayName: String,
    val firstName: String,
    val lastName: String,
    val addedAt: Long
) {
    /** The name shown everywhere for this person: yours if you set one, theirs otherwise. */
    val name: String
        get() {
            val mine = (firstName + " " + lastName).trim()
            return mine.ifEmpty { displayName.ifEmpty { "@$username" } }
        }
}

data class ContactLookup(val user: ContactEntry, val isContact: Boolean)

/** Talks to the Encore Worker. All calls block, so run them inside runIo. */
object ContactsApi {
    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

    fun parse(o: JSONObject): ContactEntry = ContactEntry(
        id = o.getString("id"),
        username = o.str("username"),
        displayName = o.str("display_name"),
        firstName = o.str("first_name"),
        lastName = o.str("last_name"),
        addedAt = o.optLong("added_at")
    )

    fun list(): List<ContactEntry> {
        val arr = Api.call("GET", "/v1/contacts").getJSONArray("contacts")
        return List(arr.length()) { parse(arr.getJSONObject(it)) }
    }

    /** Finds a person by username. Throws when there is no such person. */
    fun lookup(username: String): ContactLookup {
        val o = Api.call("GET", "/v1/contacts/lookup?username=" + java.net.URLEncoder.encode(username, "UTF-8"))
        return ContactLookup(parse(o.getJSONObject("user")), o.optBoolean("is_contact"))
    }

    /** Adds the person, or renames them when they are already a contact. */
    fun save(userId: String, first: String, last: String): ContactEntry {
        val b = JSONObject().put("first_name", first.trim()).put("last_name", last.trim())
        return parse(Api.call("PUT", "/v1/contacts/$userId", b).getJSONObject("contact"))
    }

    fun remove(userId: String) {
        Api.call("DELETE", "/v1/contacts/$userId")
    }

    /** Makes sure the 1-to-1 chat with this person exists, and returns its conversation id. */
    fun chatId(userId: String): String =
        Api.call("POST", "/v1/contacts/$userId/chat", JSONObject()).getString("conversation_id")
}

/**
 * The contacts of the signed-in account. Reads are state, so every screen that asks
 * "is this a contact" or "what do I call them" updates by itself when something changes.
 * The last known list is kept on the phone, so contacts show instantly and offline.
 */
object ContactsStore {
    private val map = mutableStateMapOf<String, ContactEntry>()
    private var prefs: android.content.SharedPreferences? = null
    private var owner: String? = null

    var loaded by mutableStateOf(false)
        private set

    val count: Int get() = map.size

    fun has(id: String?): Boolean = id != null && map.containsKey(id)

    fun get(id: String?): ContactEntry? = if (id == null) null else map[id]

    /** The name to show for a person: the one you gave them, or [fallback]. */
    fun nameFor(id: String?, fallback: String): String = get(id)?.name ?: fallback

    fun sorted(): List<ContactEntry> = map.values.sortedBy { it.name.lowercase() }

    /** Loads what was saved on the phone for the signed-in account. Safe to call again and again. */
    fun attach(ctx: Context) {
        val me = Session.user?.id ?: return
        if (owner == me) return
        owner = me
        val p = ctx.getSharedPreferences("encore_contacts", Context.MODE_PRIVATE)
        prefs = p
        map.clear()
        loaded = false
        val raw = p.getString("list_$me", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val e = ContactsApi.parse(arr.getJSONObject(i))
                map[e.id] = e
            }
            loaded = true
        } catch (_: Exception) {
            map.clear()
        }
    }

    private fun persist() {
        val me = owner ?: return
        val arr = JSONArray()
        map.values.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("username", it.username)
                    .put("display_name", it.displayName)
                    .put("first_name", it.firstName)
                    .put("last_name", it.lastName)
                    .put("added_at", it.addedAt)
            )
        }
        prefs?.edit()?.putString("list_$me", arr.toString())?.apply()
    }

    /** Asks the server for the full list and replaces what is held. */
    fun refresh() {
        runIo({ ContactsApi.list() }) { r ->
            r.onSuccess { list ->
                val fresh = list.associateBy { it.id }
                map.keys.toList().filter { it !in fresh }.forEach { map.remove(it) }
                fresh.forEach { (k, v) -> map[k] = v }
                loaded = true
                persist()
            }
        }
    }

    fun save(userId: String, first: String, last: String, onDone: (Result<ContactEntry>) -> Unit) {
        runIo({ ContactsApi.save(userId, first, last) }) { r ->
            r.onSuccess {
                map[it.id] = it
                persist()
            }
            onDone(r)
        }
    }

    fun remove(userId: String, onDone: (Result<Unit>) -> Unit) {
        runIo({ ContactsApi.remove(userId) }) { r ->
            r.onSuccess {
                map.remove(userId)
                persist()
            }
            onDone(r)
        }
    }
}

/** "Ada Lovelace" -> "Ada" and "Lovelace". */
internal fun splitName(full: String): Pair<String, String> {
    val t = full.trim()
    val i = t.indexOf(' ')
    return if (i < 0) t to "" else t.substring(0, i) to t.substring(i + 1).trim()
}

// ---------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun ContactButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(52.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(26.dp))
            .background(Ui.ink)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Ui.onInk, fontSize = fs(15f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ContactRow(c: ContactEntry, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 11.dp, end = 15.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(c.id, c.name, 52.dp)
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.name,
                        fontSize = fs(16f),
                        fontWeight = FontWeight.Medium,
                        color = Ui.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Badge(c.id, name = c.name)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "@" + c.username,
                    color = Ui.muted,
                    fontSize = fs(15f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 72.dp), thickness = 0.5.dp, color = Ui.line)
    }
}

private fun letterOf(name: String): String {
    val c = name.trim().firstOrNull()?.uppercaseChar()
    return if (c != null && c.isLetter()) c.toString() else "#"
}

// ---------------------------------------------------------------------------
// The contacts list
// ---------------------------------------------------------------------------

/**
 * Full-screen contacts list: people grouped under sticky letters, a search that
 * matches the name you gave, their own name and their username, and a button to add someone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onOpen: (ContactEntry) -> Unit,
    onAdd: () -> Unit
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    BackHandler {
        if (searching) {
            searching = false
            query = ""
        } else {
            onBack()
        }
    }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    val all by remember { derivedStateOf { ContactsStore.sorted() } }
    val q = query.trim().removePrefix("@").lowercase()
    val shown = if (q.isEmpty()) all else all.filter {
        it.name.lowercase().contains(q) || it.displayName.lowercase().contains(q) || it.username.lowercase().contains(q)
    }
    val groups = shown.groupBy { letterOf(it.name) }
    val letters = groups.keys.sortedWith(compareBy<String> { if (it == "#") 1 else 0 }.thenBy { it })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (searching) {
                            searching = false
                            query = ""
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ui.ink, modifier = Modifier.size(24.dp))
                }
                if (searching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it.take(40) },
                        placeholder = { Text("Search contacts", color = Ui.muted, fontSize = fs(16f)) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = fs(16f), color = Ui.ink),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Ui.ink
                        ),
                        modifier = Modifier.weight(1f).focusRequester(focus)
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Ui.muted, modifier = Modifier.size(24.dp))
                        }
                    }
                } else {
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("Contacts", fontSize = fs(20f), fontWeight = FontWeight.SemiBold, color = Ui.ink)
                        if (all.isNotEmpty()) {
                            Text(
                                if (all.size == 1) "1 contact" else "${all.size} contacts",
                                fontSize = fs(13f),
                                color = Ui.muted
                            )
                        }
                    }
                    IconButton(onClick = { searching = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Ui.ink, modifier = Modifier.size(24.dp))
                    }
                    IconButton(onClick = onAdd, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "New contact", tint = Ui.ink, modifier = Modifier.size(24.dp))
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = Ui.line)

            if (ContactsStore.loaded && all.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("No contacts yet", fontSize = fs(20f), fontWeight = FontWeight.SemiBold, color = Ui.ink)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add people by their username and they will be here on every device you use.",
                        fontSize = fs(15f),
                        color = Ui.muted,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    ContactButton("New contact", true, onAdd, Modifier.widthIn(min = 180.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (!searching) {
                        item(key = "new") {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = onAdd)
                                        .padding(start = 11.dp, end = 15.dp, top = 9.dp, bottom = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier.size(52.dp).clip(CircleShape).background(ContactGold),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = ContactOnGold, modifier = Modifier.size(26.dp))
                                    }
                                    Spacer(Modifier.width(13.dp))
                                    Text("New contact", fontSize = fs(16f), fontWeight = FontWeight.Medium, color = Ui.ink)
                                }
                                HorizontalDivider(modifier = Modifier.padding(start = 72.dp), thickness = 0.5.dp, color = Ui.line)
                            }
                        }
                    }
                    if (searching && q.isNotEmpty() && shown.isEmpty()) {
                        item(key = "none") {
                            Text(
                                "No contacts found",
                                color = Ui.muted,
                                fontSize = fs(16f),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                            )
                        }
                    }
                    letters.forEach { letter ->
                        stickyHeader(key = "h_$letter") {
                            Text(
                                letter,
                                fontSize = fs(14f),
                                fontWeight = FontWeight.SemiBold,
                                color = Ui.muted,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Ui.bg)
                                    .padding(start = 18.dp, top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(groups[letter].orEmpty(), key = { it.id }) { c ->
                            ContactRow(c) { onOpen(c) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Add and edit
// ---------------------------------------------------------------------------

/** The name form shared by "add" and "edit". Saves to the server, then reports the saved contact. */
@Composable
private fun ContactForm(
    userId: String,
    username: String,
    displayName: String,
    initialFirst: String,
    initialLast: String,
    editing: Boolean,
    onSaved: (ContactEntry) -> Unit
) {
    var first by remember(userId) { mutableStateOf(initialFirst) }
    var last by remember(userId) { mutableStateOf(initialLast) }
    var saving by remember(userId) { mutableStateOf(false) }
    var error by remember(userId) { mutableStateOf<String?>(null) }

    fun submit() {
        if (saving) return
        if (first.isBlank() && last.isBlank()) {
            error = "Enter a name"
            return
        }
        saving = true
        error = null
        ContactsStore.save(userId, first, last) { r ->
            saving = false
            r.onSuccess(onSaved).onFailure { error = it.message ?: "Couldn't save" }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (editing) "Edit contact" else "New contact",
            fontSize = fs(20f),
            fontWeight = FontWeight.Bold,
            color = Ui.ink
        )
        Spacer(Modifier.height(18.dp))
        Avatar(userId, displayName.ifEmpty { username }, 84.dp)
        Spacer(Modifier.height(10.dp))
        Text("@$username", fontSize = fs(14f), color = Ui.muted)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = first,
            onValueChange = { first = it.take(48) },
            label = { Text("First name") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().height(58.dp)
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = last,
            onValueChange = { last = it.take(48) },
            label = { Text("Last name (optional)") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().height(58.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Only you can see the name you give. They keep their own name.",
            fontSize = fs(13f),
            color = Ui.muted,
            modifier = Modifier.fillMaxWidth()
        )
        val err = error
        if (err != null) {
            Text(err, color = MaterialTheme.colorScheme.error, fontSize = fs(13f), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Spacer(Modifier.height(18.dp))
        ContactButton(
            when {
                saving -> "Saving…"
                editing -> "Save"
                else -> "Add contact"
            },
            enabled = !saving,
            onClick = { submit() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Add this person to your contacts, or rename them when they already are one. Used from a profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditSheet(
    userId: String,
    username: String,
    displayName: String,
    onDismiss: () -> Unit,
    onSaved: (ContactEntry) -> Unit = {}
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val existing = remember(userId) { ContactsStore.get(userId) }
    val init = remember(userId) {
        if (existing != null && (existing.firstName.isNotBlank() || existing.lastName.isNotBlank())) {
            existing.firstName to existing.lastName
        } else {
            splitName(displayName)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Ui.bg) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            ContactForm(
                userId = userId,
                username = username,
                displayName = displayName,
                initialFirst = init.first,
                initialLast = init.second,
                editing = existing != null,
                onSaved = {
                    onSaved(it)
                    onDismiss()
                }
            )
        }
    }
}

/** Find a person by username, then name them and add them. Used from the "New Contact" button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactSheet(onDismiss: () -> Unit, onAdded: (ContactEntry) -> Unit = {}) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<ContactLookup?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    val myId = Session.user?.id

    fun clean(s: String) = s.trim().removePrefix("@").lowercase()

    LaunchedEffect(query) {
        val q = clean(query)
        found = null
        error = null
        searching = false
        if (q.length < 3) return@LaunchedEffect
        delay(450)
        searching = true
        runIo({ ContactsApi.lookup(q) }) { r ->
            if (clean(query) != q) return@runIo
            searching = false
            r.onSuccess { found = it }.onFailure { error = it.message ?: "No one has that username" }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Ui.bg) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            val hit = found
            if (naming && hit != null) {
                val existing = ContactsStore.get(hit.user.id)
                val init = if (existing != null && (existing.firstName.isNotBlank() || existing.lastName.isNotBlank())) {
                    existing.firstName to existing.lastName
                } else {
                    splitName(hit.user.displayName)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { naming = false }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ui.ink)
                    }
                }
                ContactForm(
                    userId = hit.user.id,
                    username = hit.user.username,
                    displayName = hit.user.displayName,
                    initialFirst = init.first,
                    initialLast = init.second,
                    editing = existing != null,
                    onSaved = {
                        onAdded(it)
                        onDismiss()
                    }
                )
            } else {
                Text("New contact", fontSize = fs(20f), fontWeight = FontWeight.Bold, color = Ui.ink)
                Spacer(Modifier.height(4.dp))
                Text("Find a person by their username.", fontSize = fs(14f), color = Ui.muted)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(32) },
                    placeholder = { Text("username") },
                    leadingIcon = { Text("@", color = Ui.muted, fontSize = fs(16f)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                when {
                    searching -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                    }
                    hit != null && hit.user.id == myId -> Text(
                        "That is your own account.",
                        color = Ui.muted,
                        fontSize = fs(14f),
                        modifier = Modifier.padding(8.dp)
                    )
                    hit != null -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Ui.soft)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(hit.user.id, hit.user.displayName.ifEmpty { hit.user.username }, 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                hit.user.displayName.ifEmpty { "@" + hit.user.username },
                                fontSize = fs(16f),
                                fontWeight = FontWeight.SemiBold,
                                color = Ui.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("@" + hit.user.username, fontSize = fs(14f), color = Ui.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Ui.ink)
                                .clickable { naming = true }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (hit.isContact) "Edit" else "Add",
                                color = Ui.onInk,
                                fontSize = fs(14f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    error != null -> Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = fs(14f),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}
