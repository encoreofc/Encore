// ===========================================================================
// USERNAME SHEET (glass look, the @ spins when it opens, no copy button)
//
// Paste everything below the "PASTE FROM HERE" line at the END of your profile
// file (it uses Pf and shareProfile, which are private to that file).
//
// Imports to add at the top of that file (skip the ones you already have):
//   import androidx.compose.animation.core.Animatable
//   import androidx.compose.animation.core.FastOutSlowInEasing
//   import androidx.compose.animation.core.Spring
//   import androidx.compose.animation.core.spring
//   import androidx.compose.animation.core.tween
//   import androidx.compose.foundation.border
//   import androidx.compose.ui.graphics.Shape
//   import androidx.compose.ui.graphics.graphicsLayer
//   import kotlinx.coroutines.launch
//
// 4 small edits inside UserPageScreen:
//
// 1) With the other states:
//      var usernameSheet by remember { mutableStateOf(false) }
//      var usernameSetAt by remember(conv.id) { mutableStateOf(0L) }
//
// 2) In the server refresh, inside runIo({ Api.call("GET", "/v1/users/${peer.id}")... }) r.onSuccess { u -> ... } add:
//      usernameSetAt = u.optLong("username_set_at", 0L)   // epoch millis, 0 = unknown
//
// 3) The @username text: tap opens the sheet (press and hold keeps the old copy).
//    Replace its .clickable { copyText(...) } with:
//      .combinedClickable(
//          onClick = { usernameSheet = true },
//          onLongClick = { copyText(appCtx, "@" + peer.username, "Username copied") }
//      )
//
// 4) With the other dialogs (near the bottom of UserPageScreen):
//      if (usernameSheet && peer != null) {
//          UsernameSheet(
//              peer.id, peer.username,
//              ContactsStore.nameFor(peer.id, peer.displayName),
//              usernameSetAt
//          ) { usernameSheet = false }
//      }
// ===========================================================================

// ===================== PASTE FROM HERE =====================

private fun usernameDate(ms: Long): String =
    java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ms))

/** Frosted look: a see-through tint with a thin light edge. */
private fun Modifier.frosted(shape: Shape, tint: Color, edge: Color): Modifier =
    this
        .clip(shape)
        .background(tint)
        .border(1.dp, edge, shape)

@Composable
private fun UsernameInfoRow(label: String, divider: Boolean, value: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Pf.muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
            value()
        }
        if (divider) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 18.dp)
                    .fillMaxWidth()
                    .height(Dp.Hairline)
                    .background(Pf.muted.copy(alpha = 0.25f))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsernameSheet(
    userId: String,
    username: String,
    displayName: String,
    setAt: Long,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // the @ turns once and settles; tapping it turns it again
    val spin = remember { Animatable(0f) }
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        launch {
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
        spin.animateTo(360f, tween(900, easing = FastOutSlowInEasing))
    }

    val tint = Pf.ink.copy(alpha = 0.06f)
    val edge = Pf.ink.copy(alpha = 0.10f)
    val glow = Pf.ink.copy(alpha = 0.16f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Pf.sheet.copy(alpha = 0.88f),
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // the @ with a soft glow behind it
            Box(
                Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .clickable {
                        scope.launch {
                            spin.snapTo(0f)
                            spin.animateTo(360f, tween(900, easing = FastOutSlowInEasing))
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        brush = Brush.radialGradient(listOf(glow, Color.Transparent)),
                        radius = size.minDimension / 2f
                    )
                }
                Box(
                    Modifier
                        .size(76.dp)
                        .graphicsLayer {
                            rotationZ = spin.value
                            scaleX = pop.value
                            scaleY = pop.value
                        }
                        .frosted(CircleShape, tint, edge),
                    contentAlignment = Alignment.Center
                ) {
                    Text("@", color = Pf.ink, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "@$username",
                color = Pf.ink,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(20.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .frosted(RoundedCornerShape(20.dp), tint, edge)
            ) {
                UsernameInfoRow("Owner", setAt > 0L) {
                    Avatar(userId, displayName, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.widthIn(max = 170.dp)) {
                        Text(
                            displayName,
                            color = Pf.ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Badge(userId, 16.sp, name = displayName)
                }
                // only shown when the server knows it; nothing is guessed
                if (setAt > 0L) {
                    UsernameInfoRow("Since", false) {
                        Text(
                            usernameDate(setAt),
                            color = Pf.ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .frosted(RoundedCornerShape(26.dp), Pf.ink.copy(alpha = 0.10f), edge)
                    .clickable { shareProfile(ctx, displayName, username) },
                contentAlignment = Alignment.Center
            ) {
                Text("Share link", color = Pf.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
