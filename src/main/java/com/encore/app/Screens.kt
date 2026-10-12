package com.encore.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*

@Composable
fun ChatApp(me: User, onLogout: () -> Unit) {
    var open by remember { mutableStateOf<Conv?>(null) }

    DisposableEffect(me.id) {
        Rt.start()
        onDispose { Rt.stop() }
    }

    val c = open
    if (c == null) {
        ChatListScreen(me, onOpen = { open = it }, onLogout = onLogout)
    } else {
        BackHandler { open = null }
        ConversationScreen(me, c, onBack = { open = null }, onLogout = onLogout)
    }
}
