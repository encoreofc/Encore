package com.encore.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*

sealed interface Route {
    data class Chat(val conv: Conv, val jump: String? = null) : Route
    data object MyProfile : Route
    data class UserInfo(val conv: Conv) : Route
    data object NewGroup : Route
    data class GroupInfo(val conv: Conv) : Route
    data object Search : Route
    data object Settings : Route
    data object Sessions : Route
    data class StoryViewer(val groups: List<StoryGroup>, val gi: Int, val si: Int) : Route
    data object StoryCreate : Route
    data class ChatSearch(val conv: Conv) : Route
    data class Gallery(val conv: Conv) : Route
    data object Bots : Route
    data class BotChat(val bot: Bot) : Route
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
            onNewGroup = { stack.add(Route.NewGroup) },
            onSearch = { stack.add(Route.Search) },
            onBots = { stack.add(Route.Bots) },
            onSettings = { stack.add(Route.Settings) },
            onStories = { g, gi, si -> stack.add(Route.StoryViewer(g, gi, si)) },
            onAddStory = { stack.add(Route.StoryCreate) }
        )
        is Route.Chat -> ConversationScreen(
            me, top.conv, top.jump,
            onBack = { back() },
            onLogout = onLogout,
            onInfo = {
                stack.add(if (top.conv.type == "group") Route.GroupInfo(top.conv) else Route.UserInfo(top.conv))
            }
        )
        Route.MyProfile -> ProfileScreen(me, onBack = { back() }, onLogout = onLogout)
        is Route.UserInfo -> UserPageScreen(
            me, top.conv,
            onBack = { back() },
            onStories = { g, gi, si -> stack.add(Route.StoryViewer(g, gi, si)) },
            onSearch = { stack.add(Route.ChatSearch(top.conv)) },
            onMedia = { stack.add(Route.Gallery(top.conv)) }
        )
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
        Route.Search -> SearchScreen(
            me,
            onBack = { back() },
            onOpen = { c ->
                back()
                stack.add(Route.Chat(c))
            }
        )
        Route.Settings -> SettingsScreen(
            onBack = { back() },
            onSessions = { stack.add(Route.Sessions) },
            onLogout = onLogout
        )
        Route.Sessions -> SessionsScreen(onBack = { back() }, onLogout = onLogout)
        is Route.StoryViewer -> StoryViewerScreen(me, top.groups, top.gi, top.si, onClose = { back() })
        Route.StoryCreate -> StoryCreateScreen(onBack = { back() }, onPosted = { back() })
        is Route.ChatSearch -> ChatSearchScreen(
            top.conv,
            onBack = { back() },
            onJump = { id ->
                repeat(3) { back() }
                stack.add(Route.Chat(top.conv, id))
            }
        )
        is Route.Gallery -> GalleryScreen(top.conv, onBack = { back() })
        Route.Bots -> BotsScreen(onBack = { back() }, onOpen = { stack.add(Route.BotChat(it)) })
        is Route.BotChat -> BotChatScreen(top.bot, onBack = { back() })
    }
}
