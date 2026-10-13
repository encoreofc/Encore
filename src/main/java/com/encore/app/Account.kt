package com.encore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

data class SessionInfo(val id: String, val device: String?, val createdAt: Long, val current: Boolean)

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
fun SettingsScreen(onBack: () -> Unit, onSessions: () -> Unit, onLogout: () -> Unit) {
    var theme by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val soon = remember { SoonState() }
    val mode = when (ThemeStore.mode) {
        "light" -> "Light"
        "dark" -> "Dark"
        else -> "System default"
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
        ) {
            ScreenBar("Settings", onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                SettingRow("Theme", sub = mode, onClick = { theme = true })
                HorizontalDivider(color = Ui.line)
                SettingRow("Active devices", sub = "See where you are logged in", onClick = onSessions)
                HorizontalDivider(color = Ui.line)
                SettingRow("Notifications", sub = "Sounds and alerts", onClick = { soon.show("Notifications") })
                HorizontalDivider(color = Ui.line)
                SettingRow("Privacy", sub = "Who can see your info", onClick = { soon.show("Privacy") })
                HorizontalDivider(color = Ui.line)
                SettingRow("About", sub = "Encore 0.1")
                HorizontalDivider(color = Ui.line)
                SettingRow("Delete account", color = MaterialTheme.colorScheme.error, onClick = { delete = true })
                HorizontalDivider(color = Ui.line)
                SettingRow("Log out", color = MaterialTheme.colorScheme.error, onClick = onLogout)
                Spacer(Modifier.height(24.dp))
            }
        }
        SoonToast(soon, 40.dp)
    }

    if (theme) ThemeDialog(onDismiss = { theme = false })
    if (delete) DeleteAccountDialog(onDismiss = { delete = false }, onDeleted = onLogout)
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
