package com.encore.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*

sealed interface Route {
    data class Chat(val conv: Conv) : Route
    data object MyProfile : Route
    data class UserInfo(val conv: Conv) : Route
    data object NewGroup : Route
    data class GroupInfo(val conv: Conv) : Route
}

@Composable
fun ChatApp(me: User, onLogout: () -> Unit) {
    val stack = remember { mutableStateListOf<Route>() }

    DisposableEffect(me.id) {
        Rt.start()
        onDispose { Rt.stop() }
    }

    fun back() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    val top = stack.lastOrNull()
    if (top != null) BackHandler { back() }

    when (top) {
        null -> ChatListScreen(
            me,
            onOpen = { stack.add(Route.Chat(it)) },
            onLogout = onLogout,
            onProfile = { stack.add(Route.MyProfile) },
            onNewGroup = { stack.add(Route.NewGroup) }
        )
        is Route.Chat -> ConversationScreen(
            me, top.conv,
            onBack = { back() },
            onLogout = onLogout,
            onInfo = {
                stack.add(if (top.conv.type == "group") Route.GroupInfo(top.conv) else Route.UserInfo(top.conv))
            }
        )
        Route.MyProfile -> ProfileScreen(me, onBack = { back() }, onLogout = onLogout)
        is Route.UserInfo -> UserInfoScreen(top.conv, onBack = { back() })
        Route.NewGroup -> CreateGroupScreen(
            onBack = { back() },
            onCreated = { c ->
                back()
                stack.add(Route.Chat(c))
            }
        )
        is Route.GroupInfo -> GroupInfoScreen(
            me, top.conv,
            onBack = { back() },
            onLeft = { stack.clear() },
            onChanged = { c ->
                val i = stack.lastIndex
                stack[i] = Route.GroupInfo(c)
                if (i > 0 && stack[i - 1] is Route.Chat) stack[i - 1] = Route.Chat(c)
            }
        )
    }
}
