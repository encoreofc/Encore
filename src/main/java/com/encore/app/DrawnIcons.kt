package com.encore.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

@Composable
internal fun MicIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.38f, h * 0.1f),
            size = Size(w * 0.24f, h * 0.5f),
            cornerRadius = CornerRadius(w * 0.12f)
        )
        drawArc(
            color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.25f, h * 0.3f),
            size = Size(w * 0.5f, h * 0.4f),
            style = Stroke(width = w * 0.07f)
        )
        drawLine(color, Offset(w * 0.5f, h * 0.7f), Offset(w * 0.5f, h * 0.88f), strokeWidth = w * 0.07f)
    }
}

@Composable
internal fun VideoIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.08f, h * 0.27f),
            size = Size(w * 0.6f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.1f),
            style = Stroke(width = w * 0.08f)
        )
        val tri = Path().apply {
            moveTo(w * 0.74f, h * 0.45f)
            lineTo(w * 0.94f, h * 0.32f)
            lineTo(w * 0.94f, h * 0.68f)
            lineTo(w * 0.74f, h * 0.55f)
            close()
        }
        drawPath(tri, color)
    }
}

@Composable
internal fun CopyIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.34f, w * 0.1f),
            size = Size(w * 0.54f, w * 0.54f),
            cornerRadius = CornerRadius(w * 0.1f),
            style = Stroke(width = w * 0.08f)
        )
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.12f, w * 0.34f),
            size = Size(w * 0.54f, w * 0.54f),
            cornerRadius = CornerRadius(w * 0.1f),
            style = Stroke(width = w * 0.08f)
        )
    }
}

@Composable
internal fun TurnIcon(color: Color, forward: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        scale(if (forward) -1f else 1f, 1f, pivot = center) {
            val head = Path().apply {
                moveTo(w * 0.08f, h * 0.45f)
                lineTo(w * 0.42f, h * 0.18f)
                lineTo(w * 0.42f, h * 0.72f)
                close()
            }
            drawPath(head, color)
            val tail = Path().apply {
                moveTo(w * 0.4f, h * 0.45f)
                lineTo(w * 0.6f, h * 0.45f)
                quadraticBezierTo(w * 0.92f, h * 0.45f, w * 0.92f, h * 0.8f)
            }
            drawPath(tail, color, style = Stroke(width = w * 0.09f))
        }
    }
}
