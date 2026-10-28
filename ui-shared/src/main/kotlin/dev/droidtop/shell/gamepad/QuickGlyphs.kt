package dev.droidtop.shell.gamepad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke

enum class QuickGlyph {
    NETWORK, VOLUME, BRIGHTNESS, MOON, ROTATE, TIMER, BLUETOOTH, VPN,
    DISPLAY, GAMEPAD, SWAP, UPDATE, ANDROID, EXIT, SETTINGS, BATTERY, GENERIC,
    // The rail's own marks and the rows added with the branching panel.
    APPS, BELL, GAUGE, DOWNLOAD, POWER, AIRPLANE,
    // The companion's tabs (docs/SPEC.md "The companion's tabs"), drawn by the same rail.
    HOME, KEYBOARD, PEOPLE, MORE,
    // Tiles that drew GENERIC, the placeholder ring, until each had its own (Droidtop/tracker#534).
    CAMERA, STOP, SECOND_SCREEN, LOCATION, FLASHLIGHT, PLUGIN,
    PROFILE,
}


/**
 * The tile glyphs, drawn as plain strokes on a 24-unit square: small enough
 * to read at tile size, and one hand-drawn style across every tile and the
 * rail. A quick-settings grid without icons is a wall of text, so a tile
 * this file has no glyph for draws [QuickGlyph.GENERIC], a placeholder that
 * a known tile should never show (Droidtop/tracker#534).
 */
@Composable
fun QuickGlyphIcon(glyph: QuickGlyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = 2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun ring(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * u, p(cx, cy), style = stroke)
        fun dot(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * u, p(cx, cy))
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) = drawArc(
            color = tint,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = p(cx - r, cy - r),
            size = Size(2 * r * u, 2 * r * u),
            style = stroke,
        )
        when (glyph) {
            QuickGlyph.NETWORK -> {
                arc(12f, 17f, 10f, 200f, 140f)
                arc(12f, 17f, 6.5f, 200f, 140f)
                dot(12f, 17f, 1.7f)
            }
            QuickGlyph.VOLUME -> {
                drawPath(
                    Path().apply {
                        moveTo(4f * u, 9f * u); lineTo(8f * u, 9f * u); lineTo(12f * u, 5f * u)
                        lineTo(12f * u, 19f * u); lineTo(8f * u, 15f * u); lineTo(4f * u, 15f * u); close()
                    },
                    tint,
                )
                arc(12f, 12f, 4.5f, -60f, 120f)
                arc(12f, 12f, 7.5f, -60f, 120f)
            }
            QuickGlyph.BRIGHTNESS -> {
                ring(12f, 12f, 4.5f)
                val rays = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
                rays.forEach { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    val dx = Math.cos(rad).toFloat()
                    val dy = Math.sin(rad).toFloat()
                    line(12f + dx * 7.5f, 12f + dy * 7.5f, 12f + dx * 10f, 12f + dy * 10f)
                }
            }
            QuickGlyph.MOON -> {
                // A crescent: the disc minus a disc offset out of it.
                val full = Path().apply { addOval(Rect(p(3f, 3f), Size(18f * u, 18f * u))) }
                val bite = Path().apply { addOval(Rect(p(9f, 0f), Size(18f * u, 18f * u))) }
                drawPath(Path().apply { op(full, bite, PathOperation.Difference) }, tint)
            }
            QuickGlyph.ROTATE -> {
                arc(12f, 12f, 8f, 30f, 260f)
                drawPath(
                    Path().apply {
                        moveTo(18f * u, 4f * u); lineTo(21f * u, 9f * u); lineTo(15f * u, 9f * u); close()
                    },
                    tint,
                )
            }
            QuickGlyph.TIMER -> {
                ring(12f, 13f, 8f)
                line(12f, 13f, 12f, 8.5f)
                line(12f, 13f, 15.5f, 14.5f)
                line(9f, 2.5f, 15f, 2.5f)
            }
            QuickGlyph.BLUETOOTH -> {
                line(12f, 3f, 12f, 21f)
                line(12f, 3f, 18f, 8.5f); line(18f, 8.5f, 6f, 15.5f)
                line(12f, 21f, 18f, 15.5f); line(18f, 15.5f, 6f, 8.5f)
            }
            QuickGlyph.VPN -> {
                drawRoundRect(
                    tint,
                    topLeft = p(5f, 11f),
                    size = Size(14f * u, 9f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u, 2f * u),
                    style = stroke,
                )
                arc(12f, 11f, 4f, 180f, 180f)
                dot(12f, 15.5f, 1.4f)
            }
            QuickGlyph.DISPLAY -> {
                drawRoundRect(
                    tint,
                    topLeft = p(3f, 5f),
                    size = Size(18f * u, 11f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u, 2f * u),
                    style = stroke,
                )
                line(9f, 20f, 15f, 20f)
                line(12f, 16f, 12f, 20f)
            }
            QuickGlyph.GAMEPAD -> {
                drawRoundRect(
                    tint,
                    topLeft = p(2.5f, 7f),
                    size = Size(19f * u, 10f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * u, 5f * u),
                    style = stroke,
                )
                line(6f, 12f, 10f, 12f); line(8f, 10f, 8f, 14f)
                dot(16f, 11f, 1.3f); dot(18.5f, 13.5f, 1.3f)
            }
            QuickGlyph.SWAP -> {
                line(4f, 9f, 18f, 9f); line(14f, 5f, 18f, 9f); line(14f, 13f, 18f, 9f)
                line(20f, 16f, 6f, 16f); line(10f, 12f, 6f, 16f); line(10f, 20f, 6f, 16f)
            }
            QuickGlyph.UPDATE -> {
                arc(12f, 12f, 8f, 120f, 260f)
                line(12f, 6f, 12f, 14f); line(9f, 11f, 12f, 14f); line(15f, 11f, 12f, 14f)
            }
            QuickGlyph.ANDROID -> {
                ring(12f, 12f, 3.2f)
                listOf(0f, 60f, 120f, 180f, 240f, 300f).forEach { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    val dx = Math.cos(rad).toFloat()
                    val dy = Math.sin(rad).toFloat()
                    line(12f + dx * 5.5f, 12f + dy * 5.5f, 12f + dx * 9.5f, 12f + dy * 9.5f)
                }
            }
            QuickGlyph.EXIT -> {
                drawRoundRect(
                    tint,
                    topLeft = p(3f, 4f),
                    size = Size(10f * u, 16f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u, 2f * u),
                    style = stroke,
                )
                line(10f, 12f, 21f, 12f); line(17f, 8f, 21f, 12f); line(17f, 16f, 21f, 12f)
            }
            QuickGlyph.SETTINGS -> {
                // A gear: a ring, eight spokes, and a filled hub -- drawn
                // apart from QuickGlyph.ANDROID's six-spoke, hub-less ring
                // (the "Android settings" tile's own glyph) so the two
                // settings-shaped tiles read as different destinations at
                // a glance, not the same icon twice.
                ring(12f, 12f, 5.5f)
                listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f).forEach { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    val dx = Math.cos(rad).toFloat()
                    val dy = Math.sin(rad).toFloat()
                    line(12f + dx * 7.5f, 12f + dy * 7.5f, 12f + dx * 10f, 12f + dy * 10f)
                }
                dot(12f, 12f, 1.8f)
            }
            QuickGlyph.BATTERY -> {
                drawRoundRect(tint, p(3f, 7f), Size(16f * u, 10f * u), androidx.compose.ui.geometry.CornerRadius(2f * u), style = stroke)
                drawRect(tint, p(19.5f, 10f), Size(2f * u, 4f * u))
                drawRect(tint, p(5.5f, 9.5f), Size(8f * u, 5f * u))
            }
            QuickGlyph.GENERIC -> {
                ring(12f, 12f, 8f)
                dot(12f, 12f, 2.2f)
            }
            QuickGlyph.CAMERA -> {
                drawRoundRect(tint, p(3f, 7f), Size(18f * u, 13f * u), androidx.compose.ui.geometry.CornerRadius(2.5f * u), style = stroke)
                line(8.5f, 7f, 10f, 4.5f); line(10f, 4.5f, 14f, 4.5f); line(14f, 4.5f, 15.5f, 7f)
                ring(12f, 13.5f, 3.8f)
            }
            QuickGlyph.STOP -> {
                ring(12f, 12f, 9f)
                drawRect(tint, p(8.5f, 8.5f), Size(7f * u, 7f * u))
            }
            QuickGlyph.SECOND_SCREEN -> {
                // Two screens, the second drawn in front of the first: the device and its companion.
                drawRoundRect(tint, p(2.5f, 4f), Size(13f * u, 9f * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u), style = stroke)
                drawRoundRect(tint, p(8.5f, 11f), Size(13f * u, 9f * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u), style = stroke)
            }
            QuickGlyph.LOCATION -> {
                // A map pin: a round head narrowing to its point, and the hole through it.
                arc(12f, 9.5f, 6.5f, 150f, 240f)
                line(6.37f, 12.75f, 12f, 21f); line(17.63f, 12.75f, 12f, 21f)
                ring(12f, 9.5f, 2.3f)
            }
            QuickGlyph.FLASHLIGHT -> {
                // A torch, lit end up: the wide head, the handle and its switch, a ray each side.
                line(7f, 7f, 17f, 7f); line(7f, 7f, 9.5f, 11f); line(17f, 7f, 14.5f, 11f)
                drawRoundRect(tint, p(9.5f, 11f), Size(5f * u, 10f * u), androidx.compose.ui.geometry.CornerRadius(1f * u), style = stroke)
                dot(12f, 14.5f, 1.1f)
                line(12f, 2f, 12f, 4.5f); line(5.5f, 3.5f, 7f, 5f); line(18.5f, 3.5f, 17f, 5f)
            }
            QuickGlyph.PROFILE -> {
                // A person in a circle: the head and the shoulders' curve.
                ring(12f, 12f, 9f)
                ring(12f, 9.5f, 3f)
                arc(12f, 20f, 6f, 200f, 140f)
            }
            QuickGlyph.PLUGIN -> {
                // A puzzle piece, as the left menu's Plugins row: a square with a knob on its top and one on its right.
                line(4f, 8f, 9.5f, 8f); line(14.5f, 8f, 18f, 8f)
                arc(12f, 7f, 2.6f, 160f, 220f)
                line(18f, 8f, 18f, 11.5f); line(18f, 16.5f, 18f, 20f)
                arc(19f, 14f, 2.6f, 250f, 220f)
                line(4f, 20f, 18f, 20f); line(4f, 8f, 4f, 20f)
            }
            QuickGlyph.APPS -> {
                listOf(4f to 4f, 13f to 4f, 4f to 13f, 13f to 13f).forEach { (x, y) ->
                    drawRoundRect(
                        tint,
                        topLeft = p(x, y),
                        size = Size(7f * u, 7f * u),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * u, 1.5f * u),
                        style = stroke,
                    )
                }
            }
            QuickGlyph.BELL -> {
                arc(12f, 10f, 6f, 180f, 180f)
                line(6f, 10f, 6f, 16f); line(18f, 10f, 18f, 16f)
                line(4f, 17f, 20f, 17f)
                dot(12f, 20.5f, 1.5f)
            }
            QuickGlyph.GAUGE -> {
                arc(12f, 15f, 9f, 180f, 180f)
                line(12f, 15f, 17f, 9f)
                dot(12f, 15f, 1.8f)
            }
            QuickGlyph.DOWNLOAD -> {
                line(12f, 4f, 12f, 15f)
                line(8f, 11f, 12f, 15f); line(16f, 11f, 12f, 15f)
                line(5f, 19f, 19f, 19f)
            }
            QuickGlyph.POWER -> {
                arc(12f, 13f, 7.5f, -60f, 300f)
                line(12f, 3f, 12f, 11f)
            }
            QuickGlyph.AIRPLANE -> {
                line(12f, 3f, 12f, 20f)
                line(4f, 13f, 12f, 9f); line(20f, 13f, 12f, 9f)
                line(8f, 21f, 12f, 18f); line(16f, 21f, 12f, 18f)
            }
            QuickGlyph.HOME -> {
                line(3f, 11f, 12f, 3.5f); line(12f, 3.5f, 21f, 11f)
                line(5.5f, 9.5f, 5.5f, 20f); line(18.5f, 9.5f, 18.5f, 20f)
                line(5.5f, 20f, 18.5f, 20f)
                line(10f, 20f, 10f, 14f); line(14f, 20f, 14f, 14f); line(10f, 14f, 14f, 14f)
            }
            QuickGlyph.KEYBOARD -> {
                drawRoundRect(
                    tint,
                    topLeft = p(2.5f, 6f),
                    size = Size(19f * u, 12f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u, 2f * u),
                    style = stroke,
                )
                listOf(6f, 10f, 14f, 18f).forEach { x -> dot(x, 10f, 1.1f) }
                line(8f, 14.5f, 16f, 14.5f)
            }
            QuickGlyph.PEOPLE -> {
                ring(9f, 8f, 3.5f)
                arc(9f, 20f, 6.5f, 180f, 180f)
                ring(17f, 9f, 2.5f)
                arc(17f, 19.5f, 4.5f, 210f, 120f)
            }
            QuickGlyph.MORE -> {
                dot(5f, 12f, 2f); dot(12f, 12f, 2f); dot(19f, 12f, 2f)
            }
        }
    }
}
