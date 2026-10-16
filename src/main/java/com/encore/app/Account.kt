package com.encore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import org.json.JSONObject

data class SessionInfo(val id: String, val device: String?, val createdAt: Long, val current: Boolean)

private data class Acct(val id: String, val hasPassword: Boolean, val googleEmail: String?)

private fun fetchAcct(): Acct {
    val u = Api.call("GET", "/v1/me").getJSONObject("user")
    return Acct(
        u.optString("id"),
        u.optBoolean("has_password", true),
        if (u.isNull("google_email")) null else u.getString("google_email")
    )
}

private fun fetchSessions(): List<SessionInfo> {
    val arr = Api.call("GET", "/v1/sessions").getJSONArray("sessions")
    return List(arr.length()) {
        val o = arr.getJSONObject(it)
        SessionInfo(
            o.getString("id"),
            if (o.isNull("device")) null else o.getString("device"),
            o.getLong("created_at"),
            o.optBoolean("current")
        )
    }
}

private fun dateTime(ms: Long): String =
    java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

@Composable
private fun PasswordSetDialog(hasPassword: Boolean, onDismiss: () -> Unit, onDone: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun go() {
        busy = true
        problem = null
        val o = old
        val n = new
        runIo({
            val b = JSONObject().put("new_password", n)
            if (hasPassword) b.put("old_password", o)
            Api.call("POST", "/v1/auth/password", b)
        }) { r ->
            busy = false
            r.onSuccess { onDone() }.onFailure { problem = it.message }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui.bg,
        title = { Text(if (hasPassword) "Change password" else "Set a password") },
        text = {
            Column {
                if (hasPassword) {
                    OutlinedTextField(
                        value = old,
                        onValueChange = { old = it },
                        label = { Text("Current password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = new,
                    onValueChange = { new = it },
                    label = { Text("New password (8+ characters)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { go() },
                enabled = !busy && new.length >= 8 && (!hasPassword || old.isNotEmpty())
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// glass card used only on the Settings screen
private fun Modifier.settingsSheen(alpha: Float): Modifier = drawWithContent {
    drawContent()
    val inset = 24.dp.toPx()
    drawLine(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = alpha), Color.Transparent)
        ),
        start = Offset(inset, 1.dp.toPx()),
        end = Offset(size.width - inset, 1.dp.toPx()),
        strokeWidth = 1.dp.toPx()
    )
}

// ---------- Encore settings icons: official Material icons + real Google "G", gold-glass tile ----------
private enum class SIcon { Theme, Badge, Google, Password, Devices, Notifications, Privacy, About, Delete, Logout }

private val IcLight = Color(0xFFFFE08C)
private val IcGold = Color(0xFFF2B136)
private val IcDeep = Color(0xFFC98400)

@Composable
private fun SettingsIconTile(icon: SIcon, danger: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    val err = MaterialTheme.colorScheme.error
    val tint = if (danger) Brush.verticalGradient(listOf(err.copy(alpha = 0.30f), err.copy(alpha = 0.08f)))
    else Brush.verticalGradient(listOf(IcGold.copy(alpha = 0.34f), IcDeep.copy(alpha = 0.10f)))
    val rim = if (danger) Brush.linearGradient(listOf(err.copy(alpha = 0.85f), err.copy(alpha = 0.25f)))
    else Brush.linearGradient(listOf(IcLight, IcDeep.copy(alpha = 0.6f)))
    val ink = if (danger) Brush.linearGradient(listOf(Color(0xFFFFC2BA), err))
    else Brush.linearGradient(listOf(IcLight, IcGold))
    Box(
        modifier = Modifier
            .size(38.dp)
            .glassSurface(shape)
            .background(tint)
            .border(1.dp, rim, shape)
            .settingsSheen(0.7f),
        contentAlignment = Alignment.Center
    ) {
        SettingsGlyph(icon, ink)
    }
}

@Composable
private fun IconRow(icon: SIcon, danger: Boolean = false, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsIconTile(icon, danger)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

private fun glyphOf(icon: SIcon): ImageVector? = when (icon) {
    SIcon.Theme -> Icons.Outlined.Palette
    SIcon.Badge -> Icons.Outlined.Verified
    SIcon.Google -> null
    SIcon.Password -> Icons.Outlined.Key
    SIcon.Devices -> Icons.Outlined.Devices
    SIcon.Notifications -> Icons.Outlined.Notifications
    SIcon.Privacy -> Icons.Outlined.Lock
    SIcon.About -> Icons.Outlined.Info
    SIcon.Delete -> Icons.Outlined.Delete
    SIcon.Logout -> Icons.AutoMirrored.Outlined.Logout
}

// Google's official "G" mark: four brand-colour SVG paths, 48x48 viewBox
private val GoogleG = listOf(
    Color(0xFFEA4335) to "M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z",
    Color(0xFF4285F4) to "M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z",
    Color(0xFFFBBC05) to "M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z",
    Color(0xFF34A853) to "M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"
)

@Composable
private fun SettingsGlyph(icon: SIcon, ink: Brush) {
    val vector = glyphOf(icon)
    if (vector == null) {
        val paths = remember { GoogleG.map { (c, d) -> c to PathParser().parsePathString(d).toPath() } }
        Canvas(Modifier.size(20.dp)) {
            val k = size.width / 48f
            withTransform({ scale(k, k, Offset.Zero) }) {
                paths.forEach { (c, p) -> drawPath(p, c) }
            }
        }
    } else {
        Icon(
            imageVector = vector,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(ink, blendMode = BlendMode.SrcIn)
                }
        )
    }
}

@Composable
private fun SettingsGroup(title: String?, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        if (title != null) {
            Text(
                title.uppercase(),
                fontSize = fs(12f),
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                color = Ui.muted,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(shape)
                .settingsSheen(0.5f)
                .padding(horizontal = 16.dp),
            content = content
        )
    }
}

@Composable
private fun GroupDivider() = HorizontalDivider(color = Ui.line, modifier = Modifier.padding(start = 52.dp))

@Composable
fun SettingsScreen(onBack: () -> Unit, onSessions: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    var theme by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf(false) }
    var unlink by remember { mutableStateOf(false) }
    var badgePick by remember { mutableStateOf(false) }
    var acct by remember { mutableStateOf<Acct?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val soon = remember { SoonState() }
    val mode = when (ThemeStore.mode) {
        "light" -> "Light"
        "dark" -> "Dark"
        else -> "System default"
    }

    fun load() {
        runIo({ fetchAcct() }) { r ->
            r.onSuccess { acct = it }.onFailure {
                if ((it as? ApiException)?.code == "unauthorized") onLogout()
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    fun linkGoogle() {
        busy = true
        msg = null
        runIo({ GoogleSignIn.clientId() }) { r ->
            r.onSuccess { cid ->
                if (cid == null) {
                    busy = false
                    msg = "Google sign-in isn't set up yet"
                    return@onSuccess
                }
                GoogleSignIn.getIdToken(ctx, cid) { res ->
                    res.onSuccess { tok ->
                        runIo({ GoogleSignIn.link(tok) }) { r2 ->
                            busy = false
                            r2.onSuccess { load() }.onFailure { msg = it.message }
                        }
                    }.onFailure {
                        busy = false
                        if ((it as? ApiException)?.code != "cancelled") msg = it.message
                    }
                }
            }.onFailure {
                busy = false
                msg = it.message
            }
        }
    }

    val a = acct
    // only verified accounts are in the badge store, so the Badge row below shows only for them
    LaunchedEffect(a?.id) { a?.id?.takeIf { it.isNotEmpty() }?.let { BadgeStore.want(it) } }
    val myBadge = a?.let { BadgeStore.map[it.id] }

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
        ) {
            ScreenBar("Settings", onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                SettingsGroup("Appearance") {
                    IconRow(SIcon.Theme) { SettingRow("Theme", sub = mode, onClick = { theme = true }) }
                    if (myBadge != null) {
                        GroupDivider()
                        IconRow(SIcon.Badge) {
                            SettingRow("Badge", sub = "Shown next to your name", onClick = { badgePick = true }) {
                                a?.id?.let { BadgeOf(it, 26.dp, shimmer = false) }
                            }
                        }
                    }
                }
                SettingsGroup("Account") {
                    IconRow(SIcon.Google) {
                        SettingRow(
                            "Google account",
                            sub = if (a == null) "…" else (a.googleEmail ?: "Not linked. Tap to link"),
                            onClick = {
                                if (!busy && a != null) {
                                    if (a.googleEmail != null) unlink = true else linkGoogle()
                                }
                            }
                        )
                    }
                    GroupDivider()
                    IconRow(SIcon.Password) {
                        SettingRow(
                            if (a != null && !a.hasPassword) "Set a password" else "Change password",
                            sub = if (a != null && !a.hasPassword) "You signed up with Google" else null,
                            onClick = { if (a != null) pw = true }
                        )
                    }
                    GroupDivider()
                    IconRow(SIcon.Devices) { SettingRow("Active devices", sub = "See where you are logged in", onClick = onSessions) }
                }
                SettingsGroup("Preferences") {
                    IconRow(SIcon.Notifications) { SettingRow("Notifications", sub = "Sounds and alerts", onClick = { soon.show("Notifications") }) }
                    GroupDivider()
                    IconRow(SIcon.Privacy) { SettingRow("Privacy", sub = "Who can see your info", onClick = { soon.show("Privacy") }) }
                }
                SettingsGroup(null) {
                    IconRow(SIcon.About) { SettingRow("About", sub = "Encore 0.1") }
                }
                Spacer(Modifier.height(16.dp))
                SettingsGroup(null) {
                    IconRow(SIcon.Delete, danger = true) {
                        SettingRow("Delete account", color = MaterialTheme.colorScheme.error, onClick = { delete = true })
                    }
                    GroupDivider()
                    IconRow(SIcon.Logout, danger = true) {
                        SettingRow("Log out", color = MaterialTheme.colorScheme.error, onClick = onLogout)
                    }
                }
                msg?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
        SoonToast(soon, 40.dp)
    }

    if (theme) ThemeDialog(onDismiss = { theme = false })
    if (badgePick && a != null) BadgePickerDialog(a.id) { badgePick = false }
    if (delete) DeleteAccountDialog(onDismiss = { delete = false }, onDeleted = onLogout)
    if (pw && a != null) {
        PasswordSetDialog(
            hasPassword = a.hasPassword,
            onDismiss = { pw = false },
            onDone = {
                pw = false
                msg = null
                load()
            }
        )
    }
    if (unlink) {
        AlertDialog(
            onDismissRequest = { unlink = false },
            containerColor = Ui.bg,
            title = { Text("Unlink Google?") },
            text = { Text("You will need your username and password to log in.") },
            confirmButton = {
                TextButton(onClick = {
                    unlink = false
                    runIo({ GoogleSignIn.unlink() }) { r ->
                        r.onSuccess { load() }.onFailure { msg = it.message }
                    }
                }) { Text("Unlink", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { unlink = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun DeleteAccountDialog(onDismiss: () -> Unit, onDeleted: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun go() {
        busy = true
        problem = null
        val p = pw
        runIo({ Api.call("DELETE", "/v1/me", JSONObject().put("password", p)) }) { r ->
            busy = false
            r.onSuccess { onDeleted() }.onFailure { problem = it.message }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui.bg,
        title = { Text("Delete account?") },
        text = {
            Column {
                Text("This signs you out everywhere and removes your name and photo. It cannot be undone.", fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Your password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { go() }, enabled = !busy && pw.isNotEmpty()) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SessionsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    var list by remember { mutableStateOf<List<SessionInfo>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun load() {
        runIo({ fetchSessions() }) { r ->
            r.onSuccess { list = it }.onFailure {
                if ((it as? ApiException)?.code == "unauthorized") onLogout() else msg = it.message
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenBar("Active devices", onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            val l = list
            if (l == null) {
                Text("Loading…", color = Ui.muted, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                l.forEach { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.device ?: "Unknown device", fontSize = 16.sp)
                            Text(
                                (if (s.current) "This device · " else "") + dateTime(s.createdAt),
                                fontSize = 13.sp,
                                color = Ui.muted
                            )
                        }
                        if (!s.current) {
                            TextButton(onClick = {
                                runIo({ Api.call("DELETE", "/v1/sessions/${s.id}") }) { r ->
                                    r.onSuccess { load() }.onFailure { msg = it.message }
                                }
                            }) { Text("End", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    HorizontalDivider(color = Ui.line)
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        runIo({ Api.call("POST", "/v1/auth/logout-all") }) { r ->
                            r.onSuccess { onLogout() }.onFailure { msg = it.message }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Log out of all devices", color = MaterialTheme.colorScheme.error) }
            }
            msg?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
