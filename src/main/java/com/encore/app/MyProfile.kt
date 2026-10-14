package com.encore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
fun ProfileScreen(me: User, onBack: () -> Unit, onLogout: () -> Unit) {
    val appCtx = LocalContext.current.applicationContext
    val cached = remember(me.id) { ProfileCache.get(me.id) }
    var name by remember { mutableStateOf(me.displayName) }
    var bio by remember { mutableStateOf(cached?.bio ?: "") }
    var location by remember { mutableStateOf(cached?.location ?: "") }
    // the bio / location boxes are only drawn once their text is known, so the labels never jump
    var ready by remember { mutableStateOf(cached != null) }
    var touched by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1200)
        ready = true
    }

    LaunchedEffect(Unit) {
        // 1) what is saved on the phone, instantly
        runIo({ Db.dao(appCtx).profileNow(me.id) }) { r ->
            r.onSuccess { p ->
                if (p != null) {
                    if (!touched) {
                        bio = p.bio ?: ""
                        location = p.location ?: ""
                    }
                    ProfileCache.put(p)
                    ready = true
                }
            }
        }
        // 2) refresh from the server (never overwrites something the user already started typing)
        runIo({ Api.call("GET", "/v1/me").getJSONObject("user") }) { r ->
            r.onSuccess { u ->
                val b = if (u.isNull("bio")) "" else u.getString("bio")
                val l = if (u.isNull("location")) "" else u.getString("location")
                if (!touched) {
                    name = u.optString("display_name", name)
                    bio = b
                    location = l
                }
                ready = true
                val fresh = ProfileEntity(me.id, b.ifBlank { null }, l.ifBlank { null }, false)
                ProfileCache.put(fresh)
                runIo({ Db.dao(appCtx).putProfileNow(fresh) }) { }
            }.onFailure {
                ready = true
                if ((it as? ApiException)?.code == "unauthorized") onLogout()
            }
        }
    }

    val pick = rememberAvatarPicker(
        onPicked = { bytes, type ->
            busy = true
            msg = null
            runIo({ Api.setAvatar(bytes, type) }) { r ->
                busy = false
                r.onFailure { msg = it.message }
            }
        },
        onError = { msg = it }
    )

    fun save() {
        busy = true
        msg = null
        val n = name
        val b = bio
        val l = location
        runIo({
            Api.call(
                "PATCH", "/v1/me",
                JSONObject().put("display_name", n).put("bio", b).put("location", l)
            )
        }) { r ->
            busy = false
            r.onSuccess {
                msg = "Saved"
                val fresh = ProfileEntity(me.id, b.ifBlank { null }, l.ifBlank { null }, false)
                ProfileCache.put(fresh)
                runIo({ Db.dao(appCtx).putProfileNow(fresh) }) { }
            }.onFailure { msg = it.message }
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
        ScreenBar("Profile", onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.clip(RoundedCornerShape(60.dp)).clickable { pick() }) {
                Avatar(me.id, name.ifBlank { me.username }, 112.dp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Change photo", color = Ui.muted, fontSize = 13.sp)
            Text("@" + me.username, color = Ui.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = name,
                onValueChange = {
                    touched = true
                    name = it
                },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            if (ready) {
                OutlinedTextField(
                    value = bio,
                    onValueChange = {
                        touched = true
                        bio = it
                    },
                    label = { Text("Bio") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Spacer(Modifier.height(56.dp))
            }
            Spacer(Modifier.height(12.dp))
            if (ready) {
                OutlinedTextField(
                    value = location,
                    onValueChange = {
                        touched = true
                        location = it
                    },
                    label = { Text("Location") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Spacer(Modifier.height(56.dp))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { save() },
                enabled = !busy && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Please wait" else "Save") }
            msg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = if (it == "Saved") Ui.muted else MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
