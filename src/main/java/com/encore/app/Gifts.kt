package com.encore.app

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Gifts: models, the backend calls, hand-drawn icons and the pieces
// the profile page uses.
// ---------------------------------------------------------------------------

private val GemYellow = Color(0xFFFFC400)
private val GemLight = Color(0xFFFFE27A)
private val GemDark = Color(0xFFE0A200)

/** One gift a person has received. Limited editions carry a serial and a total. */
data class GiftItem(
    val id: String,
    val name: String,
    val artUrl: String?,          // null -> a drawn gift icon is shown instead
    val gems: Int,                // price in Gems
    val fromId: String?,          // null when the sender chose to stay hidden
    val fromName: String?,
    val sentAt: Long,             // epoch millis
    val message: String? = null,
    val serial: Int? = null,      // "#72" of a limited edition
    val totalSupply: Int? = null, // "of 5,000"
    val artKind: String = "image" // "lottie" (animated json) or "image"
)

/** One gift on sale in the shop. */
data class GiftType(
    val id: String,
    val name: String,
    val gems: Int,
    val artUrl: String?,
    val artKind: String,
    val limited: Boolean,
    val remaining: Int?,
    val soldOut: Boolean
)

data class ShopInfo(val gifts: List<GiftType>, val balance: Int)

data class GemTx(val id: String, val delta: Int, val balance: Int, val reason: String, val at: Long)
data class GemsInfo(val balance: Int, val history: List<GemTx>)

/** Talks to the Encore Worker: received gifts and the Gems wallet. All calls block, so run them inside runIo. */
object GiftsApi {
    // same Worker the rest of the app talks to; swap for the shared base URL constant if the app has one
    private const val HOST = "https://encore-api.jahidhasanofficial397.workers.dev"

    private fun JSONObject.text(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }

    private fun absolute(path: String?): String? = when {
        path == null -> null
        path.startsWith("http") -> path
        else -> HOST + path
    }

    fun parseGift(o: JSONObject): GiftItem {
        val from = o.optJSONObject("from")
        return GiftItem(
            id = o.getString("id"),
            name = o.optString("name", "Gift"),
            artUrl = absolute(o.text("art_url")),
            artKind = o.optString("art_kind", "image"),
            gems = o.optInt("gems"),
            fromId = from?.text("id"),
            fromName = from?.text("name"),
            sentAt = o.optLong("sent_at"),
            message = o.text("message"),
            serial = if (o.isNull("serial")) null else o.getInt("serial"),
            totalSupply = if (o.isNull("total_supply")) null else o.getInt("total_supply")
        )
    }

    private val artClient = OkHttpClient()
    private val artCache = object : android.util.LruCache<String, String>(2_000_000) {
        override fun sizeOf(key: String, value: String) = value.length
    }

    /** The Lottie json of a gift (needs the login header, so it is fetched here and kept in memory). Blocks. */
    fun artJson(url: String): String? {
        artCache.get(url)?.let { return it }
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${Session.token}").build()
        return try {
            artClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()?.also { artCache.put(url, it) }
            }
        } catch (e: java.io.IOException) {
            null
        }
    }

    /** The shop: gifts on sale now and my balance. */
    fun shop(): ShopInfo {
        val o = Api.call("GET", "/v1/gifts")
        val arr = o.getJSONArray("gifts")
        return ShopInfo(
            List(arr.length()) {
                val t = arr.getJSONObject(it)
                GiftType(
                    id = t.getString("id"),
                    name = t.optString("name", "Gift"),
                    gems = t.optInt("gems"),
                    artUrl = absolute(t.text("art_url")),
                    artKind = t.optString("art_kind", "image"),
                    limited = t.optBoolean("limited"),
                    remaining = if (t.isNull("remaining")) null else t.getInt("remaining"),
                    soldOut = t.optBoolean("sold_out")
                )
            },
            o.optInt("balance")
        )
    }

    /** Send a gift. Give [userId] or [username]. The same [clientId] twice never charges twice. Returns the gift and the new balance. */
    fun send(giftId: String, userId: String?, username: String?, message: String?, hideSender: Boolean, clientId: String): Pair<GiftItem, Int> {
        val b = JSONObject().put("gift_id", giftId).put("client_id", clientId).put("hide_sender", hideSender)
        if (userId != null) b.put("user_id", userId) else b.put("username", username ?: "")
        if (!message.isNullOrBlank()) b.put("message", message.trim())
        val o = Api.call("POST", "/v1/gifts/send", b)
        return parseGift(o.getJSONObject("gift")) to o.getInt("balance")
    }

    /** Gifts on someone's profile. */
    fun received(userId: String): List<GiftItem> {
        val arr = Api.call("GET", "/v1/users/$userId/gifts").getJSONArray("gifts")
        return List(arr.length()) { parseGift(arr.getJSONObject(it)) }
    }

    /** My balance and recent changes. */
    fun gems(): GemsInfo {
        val o = Api.call("GET", "/v1/me/gems")
        val arr = o.getJSONArray("history")
        return GemsInfo(
            o.getInt("balance"),
            List(arr.length()) {
                val t = arr.getJSONObject(it)
                GemTx(t.getString("id"), t.getInt("delta"), t.getInt("balance"), t.optString("reason"), t.getLong("at"))
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Icons (drawn by hand, like the other glyphs on the page)
// ---------------------------------------------------------------------------

/** The Gems currency icon: a flat six-sided cut stone in the brand yellow. */
@Composable
fun GemGlyph(boxSize: Dp = 16.dp) {
    Canvas(Modifier.size(boxSize)) {
        val w = this.size.width
        val c = Offset(w / 2f, w / 2f)
        val r = w * 0.48f
        fun pt(deg: Double): Offset {
            val a = Math.toRadians(deg)
            return Offset(c.x + (r * Math.cos(a)).toFloat(), c.y + (r * Math.sin(a)).toFloat())
        }
        val top = pt(-90.0)
        val tr = pt(-30.0)
        val br = pt(30.0)
        val bot = pt(90.0)
        val bl = pt(150.0)
        val tl = pt(210.0)
        val body = Path().apply {
            moveTo(top.x, top.y)
            lineTo(tr.x, tr.y)
            lineTo(br.x, br.y)
            lineTo(bot.x, bot.y)
            lineTo(bl.x, bl.y)
            lineTo(tl.x, tl.y)
            close()
        }
        drawPath(body, GemYellow)
        // lighter facet on top
        val lit = Path().apply {
            moveTo(tl.x, tl.y)
            lineTo(top.x, top.y)
            lineTo(tr.x, tr.y)
            lineTo(c.x, c.y)
            close()
        }
        drawPath(lit, GemLight)
        // darker facet on the lower right
        val shade = Path().apply {
            moveTo(c.x, c.y)
            lineTo(tr.x, tr.y)
            lineTo(br.x, br.y)
            lineTo(bot.x, bot.y)
            close()
        }
        drawPath(shade, GemDark)
    }
}

/** A gift box with a bow, drawn as lines so it takes any colour. */
@Composable
fun GiftGlyph(color: Color, boxSize: Dp = 24.dp) {
    Canvas(Modifier.size(boxSize)) {
        val w = this.size.width
        val sw = w * 0.08f
        // lid
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.12f, w * 0.34f),
            size = Size(w * 0.76f, w * 0.16f),
            cornerRadius = CornerRadius(w * 0.04f),
            style = Stroke(width = sw)
        )
        // box
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.18f, w * 0.50f),
            size = Size(w * 0.64f, w * 0.36f),
            cornerRadius = CornerRadius(w * 0.04f),
            style = Stroke(width = sw)
        )
        // ribbon
        drawLine(
            color,
            start = Offset(w * 0.5f, w * 0.34f),
            end = Offset(w * 0.5f, w * 0.86f),
            strokeWidth = sw
        )
        // bow
        drawOval(
            color,
            topLeft = Offset(w * 0.26f, w * 0.14f),
            size = Size(w * 0.24f, w * 0.20f),
            style = Stroke(width = sw)
        )
        drawOval(
            color,
            topLeft = Offset(w * 0.50f, w * 0.14f),
            size = Size(w * 0.24f, w * 0.20f),
            style = Stroke(width = sw)
        )
    }
}

// ---------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------

/** The dark rounded button used for the main gift actions. */
@Composable
fun GiftDarkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    icon: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Ui.ink)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            color = Ui.onInk,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The sender mark in a tile corner: their avatar, or a dark "?" when they stayed hidden. */
@Composable
private fun GiftSender(g: GiftItem, size: Dp) {
    if (g.fromId != null) {
        Avatar(g.fromId, g.fromName ?: "?", size)
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Ui.ink),
            contentAlignment = Alignment.Center
        ) {
            Text("?", color = Ui.onInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** An animated Lottie gift. [loop] false plays it once and holds the last frame (cheap for grids). */
@Composable
private fun LottieArt(url: String, size: Dp, loop: Boolean) {
    val json by produceState<String?>(null, url) { value = withContext(Dispatchers.IO) { GiftsApi.artJson(url) } }
    val j = json
    if (j == null) {
        Box(Modifier.size(size))
    } else {
        val composition by rememberLottieComposition(LottieCompositionSpec.JsonString(j))
        LottieAnimation(
            composition = composition,
            iterations = if (loop) LottieConstants.IterateForever else 1,
            modifier = Modifier.size(size)
        )
    }
}

/** The picture of a gift: Lottie or a real image when there is art (both need the login header), a drawn gift box otherwise. */
@Composable
private fun GiftArtView(url: String?, kind: String, name: String, size: Dp, loop: Boolean = true) {
    if (url != null && kind == "lottie") {
        LottieArt(url, size, loop)
    } else if (url != null) {
        val ctx = LocalContext.current
        val req = remember(url) {
            ImageRequest.Builder(ctx)
                .data(url)
                .addHeader("Authorization", "Bearer ${Session.token}")
                .crossfade(true)
                .build()
        }
        AsyncImage(
            model = req,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size)
        )
    } else {
        GiftGlyph(Ui.muted, size)
    }
}

@Composable
private fun GiftArt(g: GiftItem, size: Dp, loop: Boolean = true) = GiftArtView(g.artUrl, g.artKind, g.name, size, loop)

private val GiftLimitedTop = Color(0xFFA67BE8)
private val GiftLimitedBottom = Color(0xFF7B4CC8)
private val GiftRibbon = Color(0xFF5B3AA6)

/**
 * One gift in the profile grid (measured from Telegram's GiftCell): card corners 11dp, picture 80dp
 * in the middle, sender mark 20dp placed 2dp from the corner, a 50dp corner ribbon with the number on
 * limited editions. Cells are 114dp tall; three across with 7dp between them.
 */
@Composable
private fun GiftTile(g: GiftItem, fill: Color, modifier: Modifier, onClick: () -> Unit) {
    val serial = g.serial
    Box(
        modifier = modifier
            .height(114.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(
                if (serial != null) Brush.verticalGradient(listOf(GiftLimitedTop, GiftLimitedBottom))
                else Brush.verticalGradient(listOf(fill, fill))
            )
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.align(Alignment.Center)) { GiftArt(g, 80.dp, loop = false) }
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 2.dp, top = 2.dp)
        ) { GiftSender(g, 20.dp) }
        if (serial != null) {
            // 50dp square in the top-right corner; the band runs at 45 degrees across it
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 1.dp)
                    .size(50.dp)
                    .clipToBounds()
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val p = Path().apply {
                        moveTo(w - 36.dp.toPx(), 0f)
                        lineTo(w - 14.dp.toPx(), 0f)
                        lineTo(w, 14.dp.toPx())
                        lineTo(w, 36.dp.toPx())
                        close()
                    }
                    drawPath(p, GiftRibbon)
                }
                Box(
                    modifier = Modifier
                        .offset(x = 19.5.dp, y = 3.5.dp)
                        .size(36.dp, 18.dp)
                        .rotate(45f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "#" + java.text.NumberFormat.getInstance().format(serial),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The parts the profile page uses
// ---------------------------------------------------------------------------

/**
 * Telegram's "top panel" glass (BlurredBackgroundProviderImpl.topPanel): the page colour at 85% so what is
 * behind shows through, a 0.4dp edge that is a little darker at the bottom, and a very small shadow
 * (2.667dp blur, 0.85dp down). The values for dark mode are the ones Telegram uses for dark.
 */
@Composable
private fun GlassSurface(radius: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(radius)
    val dark = Ui.bg.luminance() < 0.5f
    val edgeTop = Color(if (dark) 0x06FFFFFF else 0x11000000)
    val edgeBottom = Color(if (dark) 0x11FFFFFF else 0x20000000)
    val shadowColor = Color(if (dark) 0x04FFFFFF else 0x20000000)
    Box(
        modifier = modifier
            .shadow(2.667.dp, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .background(Ui.bg.copy(alpha = 0.85f))
            .border(0.4.dp, Brush.verticalGradient(listOf(edgeTop, edgeBottom)), shape)
    ) { content() }
}

/**
 * The Gifts block at the bottom of a profile, like Telegram's tab: a frosted tab pill (36dp tall inside a
 * 50dp strip, corners 18dp, tab text 15sp bold, tab padding 16dp) reading "Gifts" with a few of the gift pictures,
 * then every gift in a grid. The grid is 9dp from the screen edges, cells 7dp apart across and 8dp down; the page's own 16dp side padding is
 * taken back so the grid reaches the same edges. The last row leaves room for the floating send button.
 */
@Composable
fun GiftsSection(gifts: List<GiftItem>?, onOpen: (GiftItem) -> Unit) {
    // the profile page pads its sides by 16dp; the grid wants 9dp
    val bleed = 7.dp
    Column(
        modifier = Modifier.fillMaxWidth().layout { measurable, constraints ->
            val extra = bleed.roundToPx()
            val p = measurable.measure(
                constraints.copy(minWidth = constraints.maxWidth + extra * 2, maxWidth = constraints.maxWidth + extra * 2)
            )
            layout(constraints.maxWidth, p.height) { p.place(-extra, 0) }
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // the strip is 50dp tall; the glass pill inside it is about 36dp (strip padding 7dp), corners 18dp
        Box(Modifier.height(50.dp), contentAlignment = Alignment.Center) {
            GlassSurface(18.dp) {
                // the selected tab: 28dp tall (4dp in from the pill edge), corners 14dp, the text colour at 15%
                Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Ui.ink.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // tab text is 15sp bold; the gift pictures follow it like emoji
                        Text("Gifts", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ui.ink)
                        gifts.orEmpty().distinctBy { it.name }.take(3).forEach { g ->
                            Spacer(Modifier.width(4.dp))
                            GiftArt(g, 18.dp, loop = false)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            gifts == null -> {
                // keeps the room of two rows while loading so the page does not jump
                Box(Modifier.fillMaxWidth().height(114.dp * 2 + 8.dp))
            }
            gifts.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) { GiftGlyph(Ui.muted, 88.dp) }
                    Spacer(Modifier.height(8.dp))
                    Text("No gifts yet", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ui.ink)
                    Spacer(Modifier.height(4.dp))
                    Text("Gifts they receive will show up here", fontSize = 14.sp, color = Ui.muted, textAlign = TextAlign.Center)
                }
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp + 3.5.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    gifts.chunked(3).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            row.forEach { g -> GiftTile(g, Ui.soft, Modifier.weight(1f)) { onOpen(g) } }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        // room under the last row for the floating button (30 + 56dp in Telegram)
        Spacer(Modifier.height(86.dp))
    }
}

/**
 * The floating "Send a gift" button: a 38dp pill (16dp sides) on the same glass as the tab, 8dp around it. Place it 11dp above the bottom edge (Telegram's 60dp button strip, button centred).
 */
@Composable
fun GiftSendPill(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(22.dp, modifier) {
        Box(Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(Ui.ink)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GiftGlyph(Ui.onInk, 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (name.isBlank()) "Send a gift" else "Send a gift to $name",
                    color = Ui.onInk,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

/** A row of the gift table (measured from Telegram's TableView): 14sp text, padding 12.66 / 9.33 / 10.66 / 9.33dp. */
@Composable
private fun GiftTableRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .fillMaxHeight()
                .background(Ui.soft)
                .padding(start = 12.66.dp, end = 4.dp, top = 9.33.dp, bottom = 9.33.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ui.ink, maxLines = 1)
        }
        VerticalDivider(thickness = 0.66.dp, color = Ui.line)
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.66.dp, end = 10.66.dp, top = 9.33.dp, bottom = 9.33.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * The sheet that opens when a gift is tapped: the picture (128dp), the name, then a bordered table
 * with who sent it, when, and what it is worth. Table 14dp from each side, corners 10dp, hairline 0.66dp.
 * Buttons are 48dp tall.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftDetailSheet(gift: GiftItem, onDismiss: () -> Unit, onSendOne: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val whenText = remember(gift.id) {
        java.text.SimpleDateFormat("d MMM yyyy 'at' h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(gift.sentAt))
    }
    val nf = remember { java.text.NumberFormat.getInstance() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Ui.bg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GiftArt(gift, 128.dp)
            Spacer(Modifier.height(16.dp))
            Text(gift.name, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Ui.ink, textAlign = TextAlign.Center)
            val serial = gift.serial
            if (serial != null) {
                val total = gift.totalSupply
                Spacer(Modifier.height(2.dp))
                Text(
                    if (total != null) "#${nf.format(serial)} of ${nf.format(total)}" else "#${nf.format(serial)}",
                    fontSize = 14.sp,
                    color = Ui.muted
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(0.66.dp, Ui.line, RoundedCornerShape(10.dp))
            ) {
                GiftTableRow("From") {
                    if (gift.fromId != null) {
                        GiftSender(gift, 24.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            gift.fromName ?: "Unknown",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ui.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text("Anonymous", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ui.ink)
                    }
                }
                HorizontalDivider(thickness = 0.66.dp, color = Ui.line)
                GiftTableRow("Date") { Text(whenText, fontSize = 14.sp, color = Ui.ink) }
                HorizontalDivider(thickness = 0.66.dp, color = Ui.line)
                GiftTableRow("Value") {
                    GemGlyph(16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(nf.format(gift.gems), fontSize = 14.sp, color = Ui.ink)
                }
                val note = gift.message
                if (!note.isNullOrBlank()) {
                    HorizontalDivider(thickness = 0.66.dp, color = Ui.line)
                    GiftTableRow("Note") { Text(note, fontSize = 14.sp, color = Ui.ink) }
                }
            }
            Spacer(Modifier.height(16.dp))
            GiftDarkButton("OK", onDismiss, Modifier.fillMaxWidth(), height = 48.dp)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Ui.soft)
                    .clickable(onClick = onSendOne),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GiftGlyph(Ui.ink, 22.dp)
                Spacer(Modifier.width(8.dp))
                Text("Send one too", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ui.ink)
            }
        }
    }
}

/** The bar fixed at the bottom of the profile, so sending a gift is always one tap away. */
@Composable
fun SendGiftBar(name: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(Ui.bg)) {
        HorizontalDivider(color = Ui.line)
        GiftDarkButton(
            "Send a gift to $name",
            onClick,
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) { GiftGlyph(Ui.onInk, 22.dp) }
    }
}

// ---------------------------------------------------------------------------
// The send flow: pick a person (when needed) -> pick a gift -> write a note and send
// ---------------------------------------------------------------------------

private enum class ShopStep { Person, Shop, Compose, Done }

private data class GiftTarget(val userId: String?, val username: String?, val name: String)

@Composable
private fun ShopTile(t: GiftType, modifier: Modifier, onClick: () -> Unit) {
    val nf = remember { java.text.NumberFormat.getInstance() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Ui.soft)
            .clickable(enabled = !t.soldOut, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.alpha(if (t.soldOut) 0.4f else 1f)) {
            GiftArtView(t.artUrl, t.artKind, t.name, 72.dp, loop = false)
        }
        Spacer(Modifier.height(6.dp))
        Text(t.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        if (t.soldOut) {
            Text("Sold out", fontSize = 12.sp, color = Ui.muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GemGlyph(13.dp)
                Spacer(Modifier.width(4.dp))
                Text(nf.format(t.gems), fontSize = 12.sp, color = Ui.muted, fontWeight = FontWeight.Medium)
            }
            val left = t.remaining
            if (t.limited && left != null) Text("$left left", fontSize = 11.sp, color = Ui.muted)
        }
    }
}

@Composable
private fun PersonRow(title: String, sub: String?, onClick: () -> Unit, lead: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        lead()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, fontSize = 13.sp, color = Ui.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SheetTitle(title: String, onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ui.ink) }
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/**
 * The gift flow as a bottom sheet.
 *  - [userId] given (from a profile): starts at the shop, the person is already chosen.
 *  - [userId] null (from the Gems page): starts by picking a person from recent chats, or by typing a username.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftShopSheet(userId: String?, peerName: String, onDismiss: () -> Unit, onSent: (GiftItem) -> Unit = {}) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val nf = remember { java.text.NumberFormat.getInstance() }
    var target by remember { mutableStateOf(if (userId != null) GiftTarget(userId, null, peerName.ifBlank { "them" }) else null) }
    var step by remember { mutableStateOf(if (userId != null) ShopStep.Shop else ShopStep.Person) }

    var people by remember { mutableStateOf<List<Peer>?>(null) }
    var query by remember { mutableStateOf("") }

    var shop by remember { mutableStateOf<ShopInfo?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<GiftType?>(null) }

    var note by remember { mutableStateOf("") }
    var hide by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    // one id per sheet, so a double tap or a retry can never charge twice
    val clientId = remember { java.util.UUID.randomUUID().toString() }

    fun loadShop() {
        loadError = null
        runIo({ GiftsApi.shop() }) { r ->
            r.onSuccess {
                shop = it
                GemsStore.balance = it.balance
            }.onFailure { loadError = it.message ?: "Couldn't load the shop" }
        }
    }
    LaunchedEffect(Unit) {
        loadShop()
        if (userId == null) {
            runIo({ Api.conversations() }) { r ->
                r.onSuccess { list ->
                    val me = Session.user?.id
                    people = list.filter { it.type == "dm" && it.peer != null }
                        .sortedByDescending { it.updatedAt }
                        .mapNotNull { it.peer }
                        .filter { it.id != me }
                        .distinctBy { it.id }
                }.onFailure { people = emptyList() }
            }
        }
    }

    fun send() {
        val g = picked ?: return
        val t = target ?: return
        if (sending) return
        sending = true
        sendError = null
        runIo({ GiftsApi.send(g.id, t.userId, t.username, note, hide, clientId) }) { r ->
            sending = false
            r.onSuccess { (gift, balance) ->
                GemsStore.balance = balance
                onSent(gift)
                step = ShopStep.Done
            }.onFailure { sendError = it.message ?: "Couldn't send" }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Ui.bg) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            when (step) {
                // ---- 1. who is it for ----
                ShopStep.Person -> {
                    SheetTitle("Who is it for?", null)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(32) },
                        placeholder = { Text("Search people or type a username") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    val q = query.trim().removePrefix("@").lowercase()
                    val list = people
                    val shown = list?.filter { q.isEmpty() || it.displayName.lowercase().contains(q) || it.username.lowercase().contains(q) }
                    if (q.isNotEmpty() && shown?.none { it.username.lowercase() == q } != false) {
                        PersonRow(
                            "Send to @$q",
                            "Gift someone by username",
                            onClick = {
                                target = GiftTarget(null, q, "@$q")
                                step = ShopStep.Shop
                            }
                        ) { Box(Modifier.size(44.dp).clip(CircleShape).background(Ui.soft), contentAlignment = Alignment.Center) { GiftGlyph(Ui.ink, 24.dp) } }
                    }
                    when {
                        shown == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                        }
                        shown.isEmpty() && q.isEmpty() -> Text(
                            "No recent chats yet. Type a username to send a gift.",
                            color = Ui.muted,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
                        )
                        else -> {
                            if (q.isEmpty()) {
                                Text("RECENT", fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp, color = Ui.muted,
                                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 4.dp))
                            }
                            shown.forEach { p ->
                                PersonRow(p.displayName, "@" + p.username, onClick = {
                                    target = GiftTarget(p.id, null, p.displayName)
                                    step = ShopStep.Shop
                                }) { Avatar(p.id, p.displayName, 44.dp) }
                            }
                        }
                    }
                }

                // ---- 2. which gift ----
                ShopStep.Shop -> {
                    val name = target?.name ?: ""
                    SheetTitle(
                        "Gift for $name",
                        if (userId == null) ({ step = ShopStep.Person }) else null
                    ) {
                        val bal = shop?.balance ?: GemsStore.balance
                        if (bal != null) {
                            GemGlyph(16.dp)
                            Spacer(Modifier.width(5.dp))
                            Text(nf.format(bal), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    val sh = shop
                    when {
                        sh == null && loadError != null -> {
                            Text(loadError ?: "", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                            TextButton(onClick = { loadShop() }) { Text("Try again") }
                        }
                        sh == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        }
                        sh.gifts.isEmpty() -> Text("No gifts on sale right now.", color = Ui.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            sh.gifts.chunked(3).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    row.forEach { t ->
                                        ShopTile(t, Modifier.weight(1f)) {
                                            picked = t
                                            sendError = null
                                            step = ShopStep.Compose
                                        }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }

                // ---- 3. a note, then send ----
                ShopStep.Compose -> {
                    val g = picked
                    val t = target
                    if (g != null && t != null) {
                        val bal = shop?.balance ?: GemsStore.balance
                        val enough = bal == null || bal >= g.gems
                        SheetTitle("Send ${g.name}", { if (!sending) step = ShopStep.Shop })
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Ui.soft)
                                .padding(vertical = 18.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            GiftArtView(g.artUrl, g.artKind, g.name, 130.dp, loop = true)
                            Spacer(Modifier.height(8.dp))
                            Text("For ${t.name}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GemGlyph(15.dp)
                                Spacer(Modifier.width(5.dp))
                                Text(nf.format(g.gems), fontSize = 14.sp, color = Ui.muted, fontWeight = FontWeight.Medium)
                                val left = g.remaining
                                if (g.limited && left != null) Text("  ·  $left left", fontSize = 14.sp, color = Ui.muted)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it.take(120) },
                            placeholder = { Text("Write a note (optional)") },
                            supportingText = { Text("${note.length}/120 · only ${t.name} can read it", color = Ui.muted) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Hide my name", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Switch(checked = hide, onCheckedChange = { hide = it })
                        }
                        Text(
                            "People visiting ${t.name}'s profile won't see who sent it. ${t.name} will still see your name.",
                            fontSize = 13.sp,
                            color = Ui.muted
                        )
                        val err = sendError
                        if (err != null) {
                            Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        val canSend = enough && !sending
                        GiftDarkButton(
                            when {
                                sending -> "Sending…"
                                !enough -> "Not enough Gems"
                                else -> "Send for ${nf.format(g.gems)} Gems"
                            },
                            onClick = { if (canSend) send() },
                            modifier = Modifier.fillMaxWidth().alpha(if (canSend) 1f else 0.45f)
                        )
                        if (bal != null) {
                            Text(
                                "Your balance is ${nf.format(bal)} Gems",
                                fontSize = 13.sp,
                                color = Ui.muted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                        }
                    }
                }

                // ---- 4. done ----
                ShopStep.Done -> {
                    val g = picked
                    val t = target
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (g != null) GiftArtView(g.artUrl, g.artKind, g.name, 150.dp, loop = true)
                        Spacer(Modifier.height(10.dp))
                        Text("Gift sent", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (t != null) "${t.name} will find it on their profile." else "",
                            fontSize = 14.sp,
                            color = Ui.muted,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        GiftDarkButton("Done", onDismiss, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
