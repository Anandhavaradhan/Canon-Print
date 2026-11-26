package app.easyprint.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.easyprint.core.ColorMode
import app.easyprint.core.Plan
import app.easyprint.core.Sides
import app.easyprint.core.pagesText

typealias PreviewLoader = suspend (Plan, Int, Boolean, Int) -> Bitmap?

private val grayFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun rememberSideBitmap(plan: Plan, sheet: Int, back: Boolean, widthPx: Int, load: PreviewLoader): Bitmap? {
    val state by produceState<Bitmap?>(null, plan, sheet, back, widthPx) { value = load(plan, sheet, back, widthPx) }
    return state
}

@Composable
fun PreviewCard(plan: Plan, load: PreviewLoader, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val reduce = LocalReduceMotion.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    var showBack by rememberSaveable { mutableStateOf(false) }
    val count = plan.sheets.size
    if (index >= count) index = (count - 1).coerceAtLeast(0)
    if (!plan.twoSided) showBack = false
    val sheet = plan.sheets.getOrNull(index) ?: return
    val bw = plan.settings.color == ColorMode.BW

    val landscape = sheet.front.landscape
    val ratio = if (landscape) plan.paperW / plan.paperH else plan.paperH / plan.paperW // height / width
    // Long-edge flips turn around the paper's long side.
    val aroundY = (plan.settings.sides == Sides.LONG) != landscape
    val angle by animateFloatAsState(if (showBack) 180f else 0f, if (reduce) tween(0) else tween(550), label = "flip")

    SectionCard("Preview", modifier, action = {
        if (plan.twoSided) {
            Row(Modifier.clip(RoundedCornerShape(50)).background(p.surface3).padding(3.dp).selectableGroupCompat()) {
                listOf(false to "Front", true to "Back").forEach { (b, label) ->
                    val sel = showBack == b
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (sel) p.surface else p.surface3)
                            .clickable(role = Role.Tab) { showBack = b }
                            .semantics { selected = sel }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (sel) p.accent else p.text) }
                }
            }
        }
    }) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(p.surface2)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxW = maxWidth - 96.dp
            val maxH = 340.dp
            val w = if (maxW * ratio > maxH) maxH / ratio else maxW
            val h = w * ratio
            val widthPx = with(LocalDensity.current) { w.roundToPx() }
            val front = rememberSideBitmap(plan, index, false, widthPx, load)
            val back = if (plan.twoSided && sheet.back != null) rememberSideBitmap(plan, index, true, widthPx, load) else null
            val frontDesc = "Sheet ${index + 1} front: ${pagesText(sheet.front.numbers)}"
            val backDesc = "Sheet ${index + 1} back: ${if (sheet.back != null) pagesText(sheet.back!!.numbers) else "blank"}"

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { index-- }, enabled = index > 0, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous sheet")
                }
                Box(
                    Modifier
                        .size(w, h)
                        .graphicsLayer {
                            if (aroundY) rotationY = angle else rotationX = angle
                            cameraDistance = 14f * density
                        }
                        .clickable(enabled = plan.twoSided, onClickLabel = "Turn the sheet over") { showBack = !showBack }
                        .semantics { contentDescription = if (showBack) backDesc else frontDesc },
                ) {
                    val showingBack = angle > 90f
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { if (showingBack) { if (aroundY) rotationY = 180f else rotationX = 180f } }
                            .shadow(8.dp, RoundedCornerShape(3.dp))
                            .background(p.paper, RoundedCornerShape(3.dp))
                            .clip(RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = if (showingBack) back else front
                        when {
                            showingBack && sheet.back == null -> Text("Blank back", color = androidx.compose.ui.graphics.Color(0xFF8A857C), fontWeight = FontWeight.SemiBold)
                            bmp != null -> Image(
                                bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                                colorFilter = if (bw) grayFilter else null, modifier = Modifier.fillMaxSize(),
                            )
                            else -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                    }
                }
                IconButton(onClick = { index++ }, enabled = index < count - 1, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next sheet")
                }
            }
        }
        val which = if (showBack) sheet.back else sheet.front
        Text(
            buildString {
                append("Sheet ${index + 1} of $count")
                if (plan.twoSided) append(if (showBack) " · back" else " · front")
                append(" · ${pagesText(which?.numbers)}")
            },
            style = MaterialTheme.typography.bodyMedium, color = p.muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (plan.twoSided) Text(
            "Tap the sheet to turn it over",
            style = MaterialTheme.typography.bodySmall, color = p.muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        if (count > 1) {
            val listState = rememberLazyListState()
            LaunchedEffect(index) { listState.animateScrollToItem((index - 1).coerceAtLeast(0)) }
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                itemsIndexed(plan.sheets) { i, s ->
                    val sel = i == index
                    val tw = with(LocalDensity.current) { 64.dp.roundToPx() }
                    val thumb = rememberSideBitmap(plan, i, false, tw, load)
                    val label = "Sheet ${i + 1}: ${pagesText(s.front.numbers)}" +
                        if (plan.twoSided) ", back ${pagesText(s.back?.numbers)}" else ""
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) p.accentSoft else p.surface)
                            .border(2.dp, if (sel) p.accent else p.surface, RoundedCornerShape(10.dp))
                            .clickable(role = Role.Tab, onClickLabel = "Show") { index = i; showBack = false }
                            .semantics(mergeDescendants = true) { contentDescription = label; selected = sel }
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val tr = if (s.front.landscape) plan.paperW / plan.paperH else plan.paperH / plan.paperW
                        Box(Modifier.size(64.dp, 64.dp * tr).background(p.paper).border(1.dp, p.border)) {
                            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), colorFilter = if (bw) grayFilter else null) }
                        }
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = if (sel) p.accent else p.muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

private fun Modifier.selectableGroupCompat() = this.semantics { }
