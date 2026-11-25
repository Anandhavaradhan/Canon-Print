package app.easyprint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.easyprint.core.Sides

/** Draw in a fixed 64×48 "viewBox", scaled to the canvas. */
private fun DrawScope.viewBox(w: Float, h: Float, block: DrawScope.() -> Unit) {
    val s = minOf(size.width / w, size.height / h)
    val dx = (size.width - w * s) / 2
    val dy = (size.height - h * s) / 2
    withTransform({ translate(dx, dy); scale(s, s, Offset.Zero) }) { block() }
}

private class Ink(val paper: Color, val back: Color, val line: Color, val ink: Color, val accent: Color, val soft: Color)

@Composable
private fun ink(selected: Boolean): Ink {
    val p = LocalPalette.current
    return Ink(
        paper = p.paper, back = p.surface3, line = if (selected) p.accent else p.borderStrong,
        ink = p.muted.copy(alpha = 0.55f), accent = p.accent, soft = p.accentSoft,
    )
}

private fun DrawScope.sheet(x: Float, y: Float, w: Float, h: Float, fill: Color, line: Color) {
    drawRoundRect(fill, Offset(x, y), Size(w, h), CornerRadius(2f))
    drawRoundRect(line, Offset(x, y), Size(w, h), CornerRadius(2f), style = Stroke(1.4f))
}

private fun DrawScope.textLines(x: Float, y: Float, w: Float, n: Int, c: Color) {
    repeat(n) { i -> drawLine(c, Offset(x, y + i * 5), Offset(x + if (i == n - 1) w * 0.7f else w, y + i * 5), 1.8f, StrokeCap.Round) }
}

private fun DrawScope.arrow(path: Path, c: Color) = drawPath(path, c, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))

@Composable
fun SidesArt(kind: Sides, selected: Boolean, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val k = ink(selected)
    Canvas(modifier.size(size, size * 0.75f)) {
        viewBox(64f, 48f) {
            when (kind) {
                Sides.ONE -> {
                    sheet(20f, 6f, 26f, 36f, k.paper, k.line)
                    textLines(25f, 14f, 16f, 3, k.ink)
                }
                Sides.LONG -> {
                    sheet(10f, 6f, 22f, 34f, k.back, k.line)
                    sheet(32f, 6f, 22f, 34f, k.paper, k.line)
                    drawLine(k.accent, Offset(32f, 6f), Offset(32f, 40f), 2.4f, StrokeCap.Round)
                    textLines(14f, 14f, 13f, 2, k.ink); textLines(36f, 14f, 13f, 2, k.ink)
                    arrow(Path().apply { moveTo(38f, 44f); cubicTo(34f, 47f, 30f, 47f, 26f, 44f) }, k.accent)
                    arrow(Path().apply { moveTo(26f, 41.5f); lineTo(26f, 44.5f); lineTo(29f, 44f) }, k.accent)
                }
                Sides.SHORT -> {
                    sheet(18f, 4f, 28f, 19f, k.back, k.line)
                    sheet(18f, 23f, 28f, 19f, k.paper, k.line)
                    drawLine(k.accent, Offset(18f, 23f), Offset(46f, 23f), 2.4f, StrokeCap.Round)
                    textLines(23f, 28f, 18f, 2, k.ink)
                    arrow(Path().apply { moveTo(50f, 30f); cubicTo(53f, 27f, 53f, 21f, 50f, 18f) }, k.accent)
                    arrow(Path().apply { moveTo(47.5f, 18f); lineTo(50f, 18f); lineTo(50f, 21f) }, k.accent)
                }
            }
        }
    }
}

/** kind: 1, 2, 4 or 0 for booklet */
@Composable
fun LayoutArt(kind: Int, selected: Boolean, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val k = ink(selected)
    Canvas(modifier.size(size, size * 0.75f)) {
        viewBox(64f, 48f) {
            fun pg(x: Float, y: Float, w: Float, h: Float) {
                drawRoundRect(k.soft, Offset(x, y), Size(w, h), CornerRadius(1f))
                drawRoundRect(k.accent.copy(alpha = 0.45f), Offset(x, y), Size(w, h), CornerRadius(1f), style = Stroke(1f))
            }
            when (kind) {
                1 -> { sheet(21f, 5f, 22f, 38f, k.paper, k.line); pg(25f, 10f, 14f, 28f) }
                2 -> { sheet(13f, 10f, 38f, 28f, k.paper, k.line); pg(17f, 13f, 14f, 22f); pg(33f, 13f, 14f, 22f) }
                4 -> {
                    sheet(21f, 5f, 22f, 38f, k.paper, k.line)
                    pg(24f, 8f, 7.5f, 15f); pg(32.5f, 8f, 7.5f, 15f); pg(24f, 25f, 7.5f, 15f); pg(32.5f, 25f, 7.5f, 15f)
                }
                else -> {
                    val book = Path().apply {
                        moveTo(32f, 12f); cubicTo(27f, 9f, 20f, 8f, 13f, 9f); lineTo(13f, 37f)
                        cubicTo(20f, 36f, 27f, 37f, 32f, 40f); cubicTo(37f, 37f, 44f, 36f, 51f, 37f); lineTo(51f, 9f)
                        cubicTo(44f, 8f, 37f, 9f, 32f, 12f); close()
                    }
                    drawPath(book, k.paper); drawPath(book, k.line, style = Stroke(1.4f, join = StrokeJoin.Round))
                    drawLine(k.accent, Offset(32f, 12f), Offset(32f, 40f), 2.4f, StrokeCap.Round)
                }
            }
        }
    }
}

/** "Turn the pile over like a book page, put it in the rear tray" illustration. */
@Composable
fun TurnPileArt(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        viewBox(320f, 140f) {
            // printed pile
            sheet(16f, 26f, 70f, 96f, p.paper, p.borderStrong)
            drawRoundRect(p.accentSoft, Offset(10f, 20f), Size(70f, 96f), CornerRadius(3f))
            drawRoundRect(p.accent, Offset(10f, 20f), Size(70f, 96f), CornerRadius(3f), style = Stroke(1.6f))
            repeat(5) { i -> drawLine(p.accent.copy(alpha = 0.6f), Offset(20f, 36f + i * 10), Offset(if (i % 2 == 1) 60f else 70f, 36f + i * 10), 2f, StrokeCap.Round) }
            // turn-over arrow
            arrow(Path().apply { moveTo(100f, 70f); cubicTo(118f, 25f, 160f, 25f, 178f, 70f) }, p.text)
            arrow(Path().apply { moveTo(170f, 63f); lineTo(178f, 70f); lineTo(182f, 59f) }, p.text)
            // blank side up
            sheet(196f, 20f, 70f, 96f, p.paper, p.borderStrong)
            arrow(Path().apply { moveTo(231f, 16f); lineTo(231f, 2f); moveTo(224f, 9f); lineTo(231f, 2f); lineTo(238f, 9f) }, p.text)
            // printer with rear tray
            val body = Path().apply {
                moveTo(280f, 120f); lineTo(280f, 64f); quadraticTo(280f, 56f, 288f, 56f); lineTo(312f, 56f)
                quadraticTo(320f, 56f, 320f, 64f); lineTo(320f, 120f); close()
            }
            drawPath(body, p.surface3); drawPath(body, p.borderStrong, style = Stroke(1.5f))
            val tray = Path().apply { moveTo(286f, 56f); lineTo(292f, 38f); lineTo(308f, 38f); lineTo(314f, 56f) }
            drawPath(tray, p.borderStrong, style = Stroke(1.5f, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun ColorSwatch(bw: Boolean, modifier: Modifier = Modifier) {
    val line = LocalPalette.current.borderStrong
    Canvas(modifier.size(36.dp)) {
        val r = size.minDimension / 2
        if (bw) {
            drawArc(Color(0xFF111111), 135f, 180f, true)
            drawArc(Color(0xFFBDBDBD), -45f, 180f, true)
        } else {
            val colors = listOf(Color(0xFF00A0E0), Color(0xFFE4007F), Color(0xFFFFD800))
            colors.forEachIndexed { i, c -> drawArc(c, -90f + i * 120f, 120f, true) }
        }
        drawCircle(line, r, style = Stroke(1.dp.toPx()))
    }
}
