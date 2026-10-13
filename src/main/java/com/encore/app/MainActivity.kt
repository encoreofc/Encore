package com.encore.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeLight()
        Session.load(this)
        setContent {
            EncoreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var user by remember { mutableStateOf(Session.user) }
                    val ctx = LocalContext.current
                    val current = user
                    if (current == null) {
                        AuthScreen { token, u ->
                            Session.save(ctx, token, u)
                            user = u
                        }
                    } else {
                        ChatApp(current) {
                            val t = Session.token
                            if (t != null) runIo({ runCatching { Api.logout(t) } }) { }
                            Session.clear(ctx)
                            user = null
                        }
                    }
                }
            }
        }
    }
}

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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Encore", fontSize = 32.sp, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))

        if (ng != null) {
            Text("Choose your username", fontSize = 18.sp)
            if (ng.email != null) {
                Text(ng.email, color = Ui.muted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text("3-20 characters: letters, numbers, underscore", fontSize = 12.sp, color = Ui.muted)
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { completeGoogle(ng.ticket) },
                enabled = !busy && username.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Please wait" else "Continue") }
            TextButton(onClick = { newGoogle = null; error = null }, enabled = !busy) { Text("Cancel") }
        } else {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            if (creating) {
                Spacer(Modifier.height(6.dp))
                Text("At least 8 characters", fontSize = 12.sp, color = Ui.muted)
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Please wait" else if (creating) "Create account" else "Log in")
            }
            TextButton(onClick = { creating = !creating; error = null }, enabled = !busy) {
                Text(if (creating) "I already have an account" else "Create a new account")
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
                modifier = Modifier.fillMaxWidth()
            ) { Text("Continue with Google") }
        }
    }
}
