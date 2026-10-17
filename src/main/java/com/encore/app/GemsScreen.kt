package com.encore.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** The balance, shared so Settings and the Gems page always show the same number. */
object GemsStore {
    var balance by mutableStateOf<Int?>(null)

    fun refresh() {
        runIo({ GiftsApi.gems() }) { r -> r.onSuccess { balance = it.balance } }
    }
}

fun formatGems(n: Int): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(n)

private fun txTitle(reason: String): String = when (reason) {
    "gift_send" -> "Gift sent"
    "gift_refund" -> "Refund"
    "gift_convert" -> "Gift turned into Gems"
    "admin_grant" -> "Gems from Encore"
    "admin_take" -> "Taken by Encore"
    else -> "Gems"
}

private fun txDate(ms: Long): String =
    java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

@Composable
private fun TxGlyph(incoming: Boolean, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.width / 24f
        val w = 1.9f * s
        drawLine(color, Offset(12f * s, 5f * s), Offset(12f * s, 19f * s), strokeWidth = w, cap = StrokeCap.Round)
        if (incoming) {
            drawLine(color, Offset(6f * s, 13f * s), Offset(12f * s, 19f * s), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(color, Offset(18f * s, 13f * s), Offset(12f * s, 19f * s), strokeWidth = w, cap = StrokeCap.Round)
        } else {
            drawLine(color, Offset(6f * s, 11f * s), Offset(12f * s, 5f * s), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(color, Offset(18f * s, 11f * s), Offset(12f * s, 5f * s), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun TxRow(tx: GemTx) {
    val incoming = tx.delta > 0
    val tint = if (incoming) Color(0xFF3BA55D) else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { TxGlyph(incoming, tint) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(txTitle(tx.reason), fontSize = 16.sp, color = Ui.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(txDate(tx.at), fontSize = 13.sp, color = Ui.muted)
        }
        Text(
            (if (incoming) "+" else "-") + formatGems(abs(tx.delta)),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
        Spacer(Modifier.width(6.dp))
        GemGlyph(16.dp)
    }
}

/** Full-screen Gems page: balance on top, every change below. Shown over whatever opened it. */
@Composable
fun GemsScreen(onBack: () -> Unit, onSendGift: (() -> Unit)? = null) {
    BackHandler(onBack = onBack)
    var info by remember { mutableStateOf<GemsInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        error = null
        runIo({ GiftsApi.gems() }) { r ->
            r.onSuccess {
                info = it
                GemsStore.balance = it.balance
            }.onFailure { error = it.message ?: "Couldn't load" }
        }
    }
    LaunchedEffect(Unit) { load() }

    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            ScreenBar("Gems", onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .background(Brush.radialGradient(listOf(Color(0x38FFC400), Color.Transparent)), CircleShape),
                    contentAlignment = Alignment.Center
                ) { GemGlyph(84.dp) }
                Text("Gems", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ui.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "What you send gifts with. Gifts you receive stay on your profile.",
                    fontSize = 14.sp,
                    color = Ui.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp)
                        .glassSurface(shape)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GemGlyph(30.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            info?.let { formatGems(it.balance) } ?: "…",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ui.ink
                        )
                    }
                    Text("your balance", fontSize = 14.sp, color = Ui.muted)
                    if (onSendGift != null) {
                        Spacer(Modifier.height(16.dp))
                        GiftDarkButton(
                            "Send a Gift",
                            onSendGift,
                            Modifier.fillMaxWidth(),
                            icon = { GiftGlyph(Ui.onInk, 20.dp) }
                        )
                    }
                }

                Text(
                    "HISTORY",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.8.sp,
                    color = Ui.muted,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 26.dp, bottom = 8.dp)
                )
                val h = info?.history
                when {
                    h == null && error != null -> {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                        TextButton(onClick = { load() }) { Text("Try again") }
                    }
                    h == null -> CircularProgressIndicator(Modifier.padding(24.dp).size(28.dp), strokeWidth = 2.5.dp)
                    h.isEmpty() -> Text(
                        "Nothing here yet.",
                        color = Ui.muted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassSurface(shape)
                            .padding(horizontal = 16.dp)
                    ) {
                        h.forEachIndexed { i, tx ->
                            if (i > 0) HorizontalDivider(color = Ui.line, modifier = Modifier.padding(start = 52.dp))
                            TxRow(tx)
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
