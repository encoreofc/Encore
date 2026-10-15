package com.encore.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box

class MainActivity : ComponentActivity() {
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // true while the app is allowed to show over the lock screen because of an incoming call
    private var overLock = false
    private var sawCall = false

    /** opened from the "incoming call" notification: show the call even on the lock screen */
    private fun handleCallIntent(i: Intent?) {
        if (i == null) return
        val id = i.getStringExtra(CallNotification.EXTRA_CALL) ?: return
        val peer = i.getStringExtra(CallNotification.EXTRA_PEER) ?: ""
        val name = i.getStringExtra(CallNotification.EXTRA_NAME) ?: "Unknown"
        val accept = i.getBooleanExtra(CallNotification.EXTRA_ACCEPT, false) // Answer was pressed on the banner
        i.removeExtra(CallNotification.EXTRA_CALL)
        i.removeExtra(CallNotification.EXTRA_ACCEPT)
        CallNotification.cancel(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            overLock = true
            sawCall = false
            // if the call turns out to be over already, do not stay visible over the lock screen
            window.decorView.postDelayed({ if (CallManager.phase == CallPhase.Idle) clearOverLock() }, 20000L)
        }
        CallManager.incomingFromPush(id, peer, name, accept)
    }

    private fun clearOverLock() {
        if (!overLock) return
        overLock = false
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
    }

    private fun onCallPhase(phase: CallPhase) {
        if (phase != CallPhase.Idle) sawCall = true
        else if (sawCall) {
            sawCall = false
            clearOverLock()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeLight()
        Session.load(this)
        CallManager.init(this)
        handleCallIntent(intent)
        setContent {
            EncoreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LaunchedEffect(CallManager.phase) { onCallPhase(CallManager.phase) }
                    var user by remember { mutableStateOf(Session.user) }
                    val ctx = LocalContext.current
                    val current = user
                    LaunchedEffect(current?.id) {
                        if (current != null) {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            Push.register()
                            Sync.start()
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (current == null) {
                            AuthScreen { token, u ->
                                Session.save(ctx, token, u)
                                user = u
                            }
                        } else {
                            ChatApp(current) {
                                val t = Session.token
                                if (t != null) runIo({ Push.unregister(t); runCatching { Api.logout(t) } }) { }
                                Sync.wipe()
                                Session.clear(ctx)
                                user = null
                            }
                            CallHost()
                        }
                    }
                }
            }
        }
    }
}

private val AuthYellow = Color(0xFFFFC400)
private val AuthOnYellow = Color(0xFF111111)

// Google "G" mark as a vector (standard four-colour paths on a 24x24 canvas), no image file needed
private val GoogleLogo: ImageVector by lazy {
    ImageVector.Builder(
        name = "GoogleG",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    )
        .addPath(
            pathData = addPathNodes(
                "M22.56,12.25c0,-0.78 -0.07,-1.53 -0.2,-2.25H12v4.26h5.92c-0.26,1.37 -1.04,2.53 -2.21,3.31v2.77h3.57c2.08,-1.92 3.28,-4.74 3.28,-8.09z"
            ),
            fill = SolidColor(Color(0xFF4285F4))
        )
        .addPath(
            pathData = addPathNodes(
                "M12,23c2.97,0 5.46,-0.98 7.28,-2.66l-3.57,-2.77c-0.98,0.66 -2.23,1.06 -3.71,1.06 -2.86,0 -5.29,-1.93 -6.16,-4.53H2.18v2.84C3.99,20.53 7.7,23 12,23z"
            ),
            fill = SolidColor(Color(0xFF34A853))
        )
        .addPath(
            pathData = addPathNodes(
                "M5.84,14.09c-0.22,-0.66 -0.35,-1.36 -0.35,-2.09s0.13,-1.43 0.35,-2.09V7.07H2.18C1.43,8.55 1,10.22 1,12s0.43,3.45 1.18,4.93l2.85,-2.22 0.81,-0.62z"
            ),
            fill = SolidColor(Color(0xFFFBBC05))
        )
        .addPath(
            pathData = addPathNodes(
                "M12,5.38c1.62,0 3.06,0.56 4.21,1.64l3.15,-3.15C17.45,2.09 14.97,1 12,1 7.7,1 3.99,3.47 2.18,7.07l3.66,2.84c0.87,-2.6 3.3,-4.53 6.16,-4.53z"
            ),
            fill = SolidColor(Color(0xFFEA4335))
        )
        .build()
}

// soft filled fields, a yellow border only while a field is focused
@Composable
private fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Ui.soft,
    unfocusedContainerColor = Ui.soft,
    focusedBorderColor = AuthYellow,
    unfocusedBorderColor = Color.Transparent,
    focusedLabelColor = Ui.muted,
    unfocusedLabelColor = Ui.muted,
    focusedTextColor = Ui.ink,
    unfocusedTextColor = Ui.ink,
    focusedLeadingIconColor = Ui.ink,
    unfocusedLeadingIconColor = Ui.muted,
    cursorColor = AuthYellow
)

@Composable
private fun AuthScreen(onDone: (String, User) -> Unit) {
    val ctx = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newGoogle by remember { mutableStateOf<GoogleResult.NewUser?>(null) }

    fun submit() {
        busy = true
        error = null
        val path = if (creating) "/v1/auth/register" else "/v1/auth/login"
        val u = username
        val p = password
        runIo({ Api.auth(path, u, p) }) { r ->
            busy = false
            r.onSuccess { onDone(it.first, it.second) }.onFailure {
                error = if (it is ApiException) it.message else "Something went wrong"
            }
        }
    }

    fun google() {
        busy = true
        error = null
        runIo({ GoogleSignIn.clientId() }) { r ->
            r.onSuccess { cid ->
                if (cid == null) {
                    busy = false
                    error = "Google sign-in isn't set up yet"
                    return@onSuccess
                }
                GoogleSignIn.getIdToken(ctx, cid) { res ->
                    res.onSuccess { tok ->
                        runIo({ GoogleSignIn.login(tok) }) { r2 ->
                            busy = false
                            r2.onSuccess { g ->
                                when (g) {
                                    is GoogleResult.Logged -> onDone(g.token, g.user)
                                    is GoogleResult.NewUser -> {
                                        newGoogle = g
                                        username = ""
                                    }
                                }
                            }.onFailure { error = it.message }
                        }
                    }.onFailure {
                        busy = false
                        if ((it as? ApiException)?.code != "cancelled") error = it.message
                    }
                }
            }.onFailure {
                busy = false
                error = it.message
            }
        }
    }

    fun completeGoogle(ticket: String) {
        busy = true
        error = null
        val name = username
        runIo({ GoogleSignIn.complete(ticket, name) }) { r ->
            busy = false
            r.onSuccess { onDone(it.first, it.second) }.onFailure { error = it.message }
        }
    }

    val ng = newGoogle
    val fieldShape = RoundedCornerShape(14.dp)
    val primaryColors = ButtonDefaults.buttonColors(
        containerColor = AuthYellow,
        contentColor = AuthOnYellow,
        disabledContainerColor = Ui.soft,
        disabledContentColor = Ui.muted
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        // brand block: a simple mark (swap in the real logo later) and the app name
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AuthYellow),
            contentAlignment = Alignment.Center
        ) {
            Text("E", color = AuthOnYellow, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Encore",
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.headlineMedium
        )
        if (ng == null) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (creating) "Create an account to get started" else "Log in to continue",
                color = Ui.muted,
                fontSize = 15.sp
            )
        }
        Spacer(Modifier.height(32.dp))

        if (ng != null) {
            Text("Choose your username", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            if (ng.email != null) {
                Text(ng.email, color = Ui.muted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                shape = fieldShape,
                colors = authFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text("3-20 characters: letters, numbers, underscore", fontSize = 12.sp, color = Ui.muted)
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { completeGoogle(ng.ticket) },
                enabled = !busy && username.isNotBlank(),
                shape = RoundedCornerShape(26.dp),
                colors = primaryColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) { Text(if (busy) "Please wait" else "Continue", fontSize = 16.sp, fontWeight = FontWeight.Medium) }
            TextButton(
                onClick = { newGoogle = null; error = null },
                enabled = !busy,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Cancel", color = Ui.ink) }
        } else {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                shape = fieldShape,
                colors = authFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = fieldShape,
                colors = authFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            if (creating) {
                Spacer(Modifier.height(6.dp))
                Text("At least 8 characters", fontSize = 12.sp, color = Ui.muted)
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                shape = RoundedCornerShape(26.dp),
                colors = primaryColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    if (busy) "Please wait" else if (creating) "Create account" else "Log in",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            TextButton(
                onClick = { creating = !creating; error = null },
                enabled = !busy,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    if (creating) "I already have an account" else "Create a new account",
                    color = Ui.ink,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Ui.line)
                Text("  or  ", color = Ui.muted, fontSize = 13.sp)
                HorizontalDivider(modifier = Modifier.weight(1f), color = Ui.line)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { google() },
                enabled = !busy,
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.dp, Ui.line),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ui.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Image(GoogleLogo, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Continue with Google", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
