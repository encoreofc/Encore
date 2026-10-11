package com.encore.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val EncoreColors = darkColorScheme(
    primary = Color(0xFF3D8BEB),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFF17191C),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF17191C),
    onSurface = Color(0xFFE6E6E6),
    error = Color(0xFFE5665C)
)

private val mainHandler = Handler(Looper.getMainLooper())

private fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
    Thread {
        val result = runCatching(work)
        mainHandler.post { done(result) }
    }.start()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("encore", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = EncoreColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var user by remember {
                        mutableStateOf<User?>(
                            if (prefs.contains("uid")) {
                                User(prefs.getLong("uid", 0L), prefs.getString("uname", "") ?: "")
                            } else null
                        )
                    }
                    val current = user
                    if (current == null) {
                        AuthScreen { u ->
                            prefs.edit().putLong("uid", u.id).putString("uname", u.username).apply()
                            user = u
                        }
                    } else {
                        ChatApp(current) {
                            prefs.edit().clear().apply()
                            user = null
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthScreen(onDone: (User) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        busy = true
        error = null
        background({
            if (creating) Db.signUp(username, password) else Db.login(username, password)
        }) { r ->
            busy = false
            r.onSuccess { onDone(it) }.onFailure {
                error = if (it is DbException) it.message
                else "Can't reach the server. Check your connection."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Encore", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))
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
    }
}

@Composable
private fun HomeScreen(user: User, onLogout: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp)
    ) {
        Text("Signed in as @${user.username}")
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onLogout) { Text("Log out") }
    }
}
