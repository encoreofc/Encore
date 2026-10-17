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

// ---------- Encore settings icons: drawn by hand, gold-glass tile ----------
private enum class SIcon { Theme, Badge, Google, Password, Devices, Notifications, Privacy, About, Delete, Logout, Gems }

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
        Canvas(Modifier.size(22.dp)) { drawSettingsGlyph(icon, ink) }
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

private fun DrawScope.drawSettingsGlyph(icon: SIcon, brush: Brush) {
    val s = size.width / 24f
    val st = Stroke(width = 1.7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun o(x: Float, y: Float) = Offset(x * s, y * s)
    fun path(build: Path.() -> Unit) = Path().apply(build)
    when (icon) {
        SIcon.Theme -> {
            drawCircle(brush, radius = 8.5f * s, center = o(12f, 12f), style = st)
            val half = path {
                moveTo(12f * s, 6f * s)
                arcTo(Rect(o(6f, 6f), Size(12f * s, 12f * s)), -90f, 180f, false)
                close()
            }
            drawPath(half, brush)
        }
        SIcon.Badge -> {
            drawPath(path {
                moveTo(7f * s, 5f * s); lineTo(17f * s, 5f * s); lineTo(21.5f * s, 10f * s)
                lineTo(12f * s, 20f * s); lineTo(2.5f * s, 10f * s); close()
            }, brush, style = st)
            drawLine(brush, o(2.5f, 10f), o(21.5f, 10f), strokeWidth = 1.4f * s, cap = StrokeCap.Round)
            drawPath(path { moveTo(7f * s, 5f * s); lineTo(9.5f * s, 10f * s); lineTo(12f * s, 20f * s) }, brush, style = Stroke(1.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path { moveTo(17f * s, 5f * s); lineTo(14.5f * s, 10f * s); lineTo(12f * s, 20f * s) }, brush, style = Stroke(1.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        SIcon.Google -> {
            drawCircle(brush, radius = 5.5f * s, center = o(8.5f, 12f), style = st)
            drawCircle(brush, radius = 5.5f * s, center = o(15.5f, 12f), style = st)
        }
        SIcon.Password -> {
            drawCircle(brush, radius = 4f * s, center = o(7.5f, 12f), style = st)
            drawCircle(brush, radius = 1.1f * s, center = o(7.5f, 12f))
            drawLine(brush, o(11.5f, 12f), o(21f, 12f), strokeWidth = 1.7f * s, cap = StrokeCap.Round)
            drawLine(brush, o(17f, 12f), o(17f, 15.5f), strokeWidth = 1.7f * s, cap = StrokeCap.Round)
            drawLine(brush, o(20.5f, 12f), o(20.5f, 14.5f), strokeWidth = 1.7f * s, cap = StrokeCap.Round)
        }
        SIcon.Devices -> {
            drawRoundRect(brush, o(10f, 4f), Size(11f * s, 8f * s), CornerRadius(2f * s), style = st)
            drawLine(brush, o(15.5f, 12f), o(15.5f, 16f), strokeWidth = 1.7f * s, cap = StrokeCap.Round)
            drawLine(brush, o(12.5f, 16f), o(18.5f, 16f), strokeWidth = 1.7f * s, cap = StrokeCap.Round)
            drawRoundRect(brush, o(3f, 10f), Size(6f * s, 10f * s), CornerRadius(1.8f * s), style = st)
            drawCircle(brush, radius = 0.8f * s, center = o(6f, 17.5f))
        }
        SIcon.Notifications -> {
            drawPath(path {
                moveTo(6f * s, 16f * s)
                cubicTo(6f * s, 16f * s, 7f * s, 14.5f * s, 7f * s, 10.5f * s)
                cubicTo(7f * s, 7.5f * s, 9f * s, 5f * s, 12f * s, 5f * s)
                cubicTo(15f * s, 5f * s, 17f * s, 7.5f * s, 17f * s, 10.5f * s)
                cubicTo(17f * s, 14.5f * s, 18f * s, 16f * s, 18f * s, 16f * s)
                close()
            }, brush, style = st)
            drawLine(brush, o(12f, 3f), o(12f, 5f), strokeWidth = 1.7f * s, cap = StrokeCap.Round)
            drawPath(path { moveTo(10f * s, 19f * s); quadraticBezierTo(12f * s, 21.2f * s, 14f * s, 19f * s) }, brush, style = st)
        }
        SIcon.Privacy -> {
            drawPath(path {
                moveTo(12f * s, 3f * s); lineTo(19f * s, 6f * s); lineTo(19f * s, 12f * s)
                cubicTo(19f * s, 16.5f * s, 16f * s, 19.5f * s, 12f * s, 21f * s)
                cubicTo(8f * s, 19.5f * s, 5f * s, 16.5f * s, 5f * s, 12f * s)
                lineTo(5f * s, 6f * s); close()
            }, brush, style = st)
            drawCircle(brush, radius = 1.7f * s, center = o(12f, 10.5f), style = st)
            drawLine(brush, o(12f, 12.4f), o(12f, 15.5f), strokeWidth = 1.7f * s, cap = StrokeCap.Round)
        }
        SIcon.About -> {
            drawArc(brush, startAngle = -40f, sweepAngle = 320f, useCenter = false, topLeft = o(3.5f, 3.5f), size = Size(17f * s, 17f * s), style = st)
            drawCircle(brush, radius = 1.15f * s, center = o(12f, 8f))
            drawLine(brush, o(12f, 11f), o(12f, 16.5f), strokeWidth = 1.7f * s, cap = StrokeCap.Round)
        }
        SIcon.Delete -> {
            drawLine(brush, o(5f, 7f), o(19f, 7f), strokeWidth = 1.7f * s, cap = StrokeCap.Round)
            drawPath(path { moveTo(9.5f * s, 7f * s); lineTo(9.5f * s, 4.8f * s); lineTo(14.5f * s, 4.8f * s); lineTo(14.5f * s, 7f * s) }, brush, style = st)
            drawPath(path {
                moveTo(7f * s, 7f * s); lineTo(8f * s, 19.5f * s)
                quadraticBezierTo(8.1f * s, 20.5f * s, 9.2f * s, 20.5f * s)
                lineTo(14.8f * s, 20.5f * s)
                quadraticBezierTo(15.9f * s, 20.5f * s, 16f * s, 19.5f * s)
                lineTo(17f * s, 7f * s)
            }, brush, style = st)
            drawLine(brush, o(10.5f, 10.5f), o(10.5f, 17f), strokeWidth = 1.4f * s, cap = StrokeCap.Round)
            drawLine(brush, o(13.5f, 10.5f), o(13.5f, 17f), strokeWidth = 1.4f * s, cap = StrokeCap.Round)
        }
        SIcon.Logout -> {
            drawPath(path {
                moveTo(10f * s, 4f * s); lineTo(5.5f * s, 4f * s)
                quadraticBezierTo(4f * s, 4f * s, 4f * s, 5.5f * s)
                lineTo(4f * s, 18.5f * s)
                quadraticBezierTo(4f * s, 20f * s, 5.5f * s, 20f * s)
                lineTo(10f * s, 20f * s)
            }, brush, style = st)
            drawLine(brush, o(9f, 12f), o(20f, 12f), strokeWidth = 1.7f * s, cap = StrokeCap.Round)
            drawPath(path { moveTo(16f * s, 8f * s); lineTo(20f * s, 12f * s); lineTo(16f * s, 16f * s) }, brush, style = st)
        }
        SIcon.Gems -> {
            drawPath(path {
                moveTo(12f * s, 3f * s); lineTo(19.8f * s, 7.5f * s); lineTo(19.8f * s, 16.5f * s)
                lineTo(12f * s, 21f * s); lineTo(4.2f * s, 16.5f * s); lineTo(4.2f * s, 7.5f * s); close()
            }, brush, style = st)
            val facet = Stroke(1.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(path { moveTo(4.2f * s, 7.5f * s); lineTo(12f * s, 12f * s); lineTo(19.8f * s, 7.5f * s) }, brush, style = facet)
            drawLine(brush, o(12f, 12f), o(12f, 21f), strokeWidth = 1.2f * s, cap = StrokeCap.Round)
        }
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
    var gemsOpen by remember { mutableStateOf(false) }
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
    LaunchedEffect(Unit) { GemsStore.refresh() }

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
                SettingsGroup(null) {
                    IconRow(SIcon.Gems) {
                        SettingRow("Gems", sub = "Balance and history", onClick = { gemsOpen = true }) {
                            val b = GemsStore.balance
                            if (b != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    GemGlyph(16.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(formatGems(b), color = Ui.muted, fontSize = 15.sp)
                                }
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
        if (gemsOpen) GemsScreen(onBack = { gemsOpen = false; GemsStore.refresh() })
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
