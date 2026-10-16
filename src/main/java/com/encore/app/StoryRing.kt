package com.encore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Encore's own story ring: one yellow arc per story, an arc turns grey once it has been watched
internal val StoryAccent = Color(0xFFFFC400)

// who has stories right now; the chat list, header and profile all read from here
object StoryStore {
    var groups by mutableStateOf<List<StoryGroup>>(emptyList())
        private set

    fun of(userId: String): StoryGroup? =
        groups.firstOrNull { !it.mine && it.user.id == userId && it.stories.isNotEmpty() }

    fun mine(): StoryGroup? = groups.firstOrNull { it.mine && it.stories.isNotEmpty() }

    fun refresh() {
        runIo({ StoriesApi.feed() }) { r -> r.onSuccess { groups = it } }
    }
}

internal fun Modifier.storyRing(stories: List<Story>, width: Dp, seenColor: Color): Modifier = this.drawBehind {
    val n = stories.size.coerceIn(1, 24)
    val w = width.toPx()
    val side = this.size.minDimension - w
    val step = 360f / n
    val gap = if (n == 1) 0f else minOf(12f, step * 0.3f)
    for (i in 0 until n) {
        val s = stories.getOrNull(i)
        val c = if (s != null && !s.seen) StoryAccent else seenColor
        drawArc(
            color = c,
            startAngle = -90f + i * step + gap / 2f,
            sweepAngle = step - gap,
            useCenter = false,
            topLeft = Offset(w / 2f, w / 2f),
            size = Size(side, side),
            style = Stroke(width = w)
        )
    }
}

// an avatar that grows the story ring when the person has stories; tapping the ring opens them
@Composable
fun StoryAvatar(
    id: String,
    name: String,
    size: Dp,
    onOpenStory: (List<StoryGroup>, Int, Int) -> Unit,
    mine: Boolean = false,
    onAdd: (() -> Unit)? = null
) {
    val g = if (mine) StoryStore.mine() else StoryStore.of(id)

    if (g == null) {
        val tap = if (mine && onAdd != null) Modifier.clip(CircleShape).clickable { onAdd() } else Modifier
        Box(modifier = Modifier.size(size).then(tap)) {
            Avatar(id, name, size)
            if (mine && onAdd != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(StoryAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add story", tint = Color(0xFF111111), modifier = Modifier.size(12.dp))
                }
            }
        }
        return
    }

    val small = size < 48.dp
    val ringW = if (small) 2.dp else 2.5.dp
    val gapW = if (small) 2.dp else 3.dp
    val inner = size - (ringW + gapW) * 2
    val seen = Ui.muted.copy(alpha = 0.45f)

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable {
                val i = StoryStore.groups.indexOf(g)
                if (i >= 0) onOpenStory(StoryStore.groups, i, -1)
            }
            .storyRing(g.stories, ringW, seen),
        contentAlignment = Alignment.Center
    ) {
        Avatar(id, name, inner)
    }
}
