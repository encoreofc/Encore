package com.encore.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*

@Composable
fun ChatApp(user: User, onLogout: () -> Unit) {
    var open by remember { mutableStateOf<Contact?>(null) }
    val chat = open
    if (chat == null) {
        ChatListScreen(user, onOpen = { open = it }, onLogout = onLogout)
    } else {
        BackHandler { open = null }
        ConversationScreen(user, chat, onBack = { open = null })
    }
}
