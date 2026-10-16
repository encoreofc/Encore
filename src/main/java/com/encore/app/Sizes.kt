package com.encore.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ছবি/ভিডিওর বক্সের মাপ
@Composable
fun mediaBoxSize(m: Media?): Pair<Dp, Dp> {
    val cfg = LocalConfiguration.current
    val base = minOf(cfg.screenWidthDp, cfg.screenHeightDp).dp
    val cap = (1280f / LocalDensity.current.density).dp
    val iw = m?.w ?: 0
    val ih = m?.h ?: 0
    if (iw <= 0 || ih <= 0) return 240.dp to 150.dp
    val landscape = iw >= ih
    val w0 = if (landscape) base - 64.dp else base * 0.7f
    val h0 = w0 + 100.dp
    val photoW = if (!landscape && w0 > cap) cap else w0
    val photoH = if (!landscape && h0 > cap) cap else h0
    val h = photoW * ih / iw
    return if (h > photoH) {
        (photoW * (photoH / h)) to photoH
    } else {
        photoW to maxOf(h, 120.dp)
    }
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
