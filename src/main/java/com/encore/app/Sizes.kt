package com.encore.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp

// ছবি/ভিডিওর বক্সের মাপ: ল্যান্ডস্কেপে স্ক্রিন-৬৪dp, নাহলে ৭০%,
// উচ্চতা অনুপাত ধরে, সর্বনিম্ন ১২০dp, সর্বোচ্চ প্রস্থ+১০০dp
@Composable
fun mediaBoxSize(m: Media?): Pair<Dp, Dp> {
    val screen = LocalConfiguration.current.screenWidthDp.dp
    val w0 = m?.w ?: 0
    val h0 = m?.h ?: 0
    if (w0 <= 0 || h0 <= 0) return 240.dp to 150.dp
    val width = if (w0 >= h0) screen - 64.dp else screen * 0.7f
    val height = (width * h0 / w0).coerceIn(120.dp, width + 100.dp)
    return width to height
}

object ListDims {
    val rowHeight = 70.dp
    val avatar = 52.dp
    val avatarLeft = 11.dp
    val avatarTop = 9.dp
    val textLeft = 76.dp
    val timeTop = 16.dp
    val badgeH = 20.666.dp
    val badgePadH = 6.333.dp
    val badgeMinText = 8.dp
    val badgeRight = 15.666.dp
    val badgeTop = 38.dp
}

object InputDims {
    val minHeight = 44.dp
    val button = 44.dp
    val padTop = 9.dp
    val padBottom = 10.dp
}
