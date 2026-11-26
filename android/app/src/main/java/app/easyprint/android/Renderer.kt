package app.easyprint.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.easyprint.core.Affine
import app.easyprint.core.PageSize
import app.easyprint.core.Plan
import app.easyprint.core.Side
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws sides of a [Plan] into bitmaps. Every source page of every opened
 * file has one global index, in the order the files are listed.
 * PdfRenderer is not thread-safe, so all drawing is serialised.
 */
class DocRenderer(val files: List<OpenedFile>) : Closeable {
    private data class Src(val file: OpenedFile, val page: Int)

    private val sources: List<Src> = files.flatMap { f -> f.pages.indices.map { Src(f, it) } }
    val pageSizes: List<PageSize> = files.flatMap { it.pages }
    /** Global index of the first page of each file after the first. */
    val fileStarts: List<Int> = files.runningFold(0) { acc, f -> acc + f.pages.size }.drop(1).dropLast(1)

    private val renderers = HashMap<String, Pair<ParcelFileDescriptor, PdfRenderer>>()
    private var imageCache: Triple<String, Int, Bitmap>? = null
    private val lock = Any()
    private var closed = false

    private fun pdf(f: OpenedFile): PdfRenderer = renderers.getOrPut(f.id) {
        val pfd = ParcelFileDescriptor.open(f.file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd to PdfRenderer(pfd)
    }.second

    /**
     * Draw [side] into [bitmap]. [toPx] maps logical points of the side to
     * bitmap pixels (it may rotate, and may shift the side to render a band).
     */
    fun drawSide(bitmap: Bitmap, plan: Plan, side: Side?, toPx: Affine, forPrint: Boolean) {
        if (side == null) return
        synchronized(lock) {
            if (closed) return
            val bounds = Rect(0, 0, bitmap.width, bitmap.height)
            for (p in side.placements) {
                val src = sources.getOrNull(p.src) ?: continue
                val m = p.pageToLogical.then(toPx)
                val size = pageSizes[p.src]
                val clip = mappedBounds(m, size.width, size.height)
                if (!clip.intersect(bounds)) continue
                when (src.file.kind) {
                    DocKind.PDF -> {
                        val renderer = pdf(src.file)
                        renderer.openPage(src.page).use { page ->
                            val mode = if (forPrint) PdfRenderer.Page.RENDER_MODE_FOR_PRINT else PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            page.render(bitmap, clip, m.toAndroid(), mode)
                        }
                    }
                    DocKind.IMAGE -> drawImage(bitmap, src.file, m, clip)
                }
            }
            if (plan.borders) {
                val c = Canvas(bitmap)
                c.concat(toPx.toAndroid())
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 0.6f; color = Color.rgb(115, 115, 115)
                }
                side.placements.forEach { c.drawRect(it.cell.x, it.cell.y, it.cell.x + it.cell.w, it.cell.y + it.cell.h, paint) }
            }
        }
    }

    private fun drawImage(bitmap: Bitmap, f: OpenedFile, pageToPx: Affine, clip: Rect) {
        // pixels per point needed on the output
        val needed = sqrt(pageToPx.a * pageToPx.a + pageToPx.b * pageToPx.b)
        val native = 150f / 72f // stored images are 150 px per point-inch
        var sample = 1
        while (native / (sample * 2) >= needed * 1.1f) sample *= 2
        val bmp = decode(f, sample) ?: return
        val w0 = bmp.width.toFloat(); val h0 = bmp.height.toFloat()
        val upright = when (f.imageRotation) {
            90 -> Affine(a = 0f, b = 1f, c = -1f, d = 0f, e = h0, f = 0f)
            180 -> Affine(a = -1f, b = 0f, c = 0f, d = -1f, e = w0, f = h0)
            270 -> Affine(a = 0f, b = -1f, c = 1f, d = 0f, e = 0f, f = w0)
            else -> Affine()
        }
        val pxToPt = sample * 72f / 150f
        val m = upright.then(Affine.scale(pxToPt)).then(pageToPx)
        val c = Canvas(bitmap)
        c.clipRect(clip)
        c.drawBitmap(bmp, m.toAndroid(), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun decode(f: OpenedFile, sample: Int): Bitmap? {
        imageCache?.let { (id, s, b) -> if (id == f.id && s == sample && !b.isRecycled) return b }
        val bmp = BitmapFactory.decodeFile(f.file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        imageCache = Triple(f.id, sample, bmp)
        return bmp
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            renderers.values.forEach { (pfd, r) -> runCatching { r.close() }; runCatching { pfd.close() } }
            renderers.clear()
            imageCache = null
        }
    }

    companion object {
        fun mappedBounds(m: Affine, w: Float, h: Float): Rect {
            val pts = listOf(m.map(0f, 0f), m.map(w, 0f), m.map(0f, h), m.map(w, h))
            val xs = pts.map { it.first }; val ys = pts.map { it.second }
            return Rect(floor(xs.min()).toInt(), floor(ys.min()).toInt(), ceil(xs.max()).toInt(), ceil(ys.max()).toInt())
        }
    }
}

fun Affine.toAndroid() = Matrix().apply { setValues(toMatrixValues()) }

/** Render a whole logical side (for the on-screen preview). */
fun renderPreview(renderer: DocRenderer, plan: Plan, side: Side?, landscape: Boolean, widthPx: Int): Bitmap {
    val size = if (landscape) app.easyprint.core.PageSize(plan.paperH, plan.paperW) else app.easyprint.core.PageSize(plan.paperW, plan.paperH)
    val scale = widthPx / size.width
    val h = max(1, (size.height * scale).roundToInt())
    val bmp = Bitmap.createBitmap(max(1, widthPx), h, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(Color.WHITE)
    renderer.drawSide(bmp, plan, side, Affine.scale(scale), forPrint = false)
    return bmp
}

/**
 * Render one physical (portrait) page in horizontal bands, so even 600 dpi
 * pages never need a huge bitmap. [onBand] receives the band bitmap and the
 * number of valid rows.
 */
fun renderPhysicalBands(
    renderer: DocRenderer,
    plan: Plan,
    side: Side?,
    rotate180: Boolean,
    dpi: Int,
    bandRows: Int = 256,
    /** Region of the physical page to render (points, top-left origin). Defaults to the whole sheet. */
    region: RectF? = null,
    onBand: (Bitmap, Int, Int) -> Unit,
) {
    val k = dpi / 72f
    val ox = region?.left ?: 0f
    val oy = region?.top ?: 0f
    val widthPx = ((region?.width() ?: plan.paperW) * k).roundToInt()
    val heightPx = ((region?.height() ?: plan.paperH) * k).roundToInt()
    val band = Bitmap.createBitmap(widthPx, min(bandRows, heightPx), Bitmap.Config.ARGB_8888)
    try {
        val physical = plan.physicalTransform(side, rotate180).then(Affine.translate(-ox, -oy))
        var y0 = 0
        while (y0 < heightPx) {
            val rows = min(band.height, heightPx - y0)
            band.eraseColor(Color.WHITE)
            val toPx = physical.then(Affine.scale(k)).then(Affine.translate(0f, -y0.toFloat()))
            renderer.drawSide(band, plan, side, toPx, forPrint = true)
            onBand(band, y0, rows)
            y0 += rows
        }
    } finally {
        band.recycle()
    }
}

fun physicalSizePx(plan: Plan, dpi: Int) = Pair((plan.paperW * dpi / 72f).roundToInt(), (plan.paperH * dpi / 72f).roundToInt())
