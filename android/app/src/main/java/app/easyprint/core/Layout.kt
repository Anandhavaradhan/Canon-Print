package app.easyprint.core

import kotlin.math.abs
import kotlin.math.min

/**
 * Page layout ("imposition") engine. Pure Kotlin.
 *
 * Coordinates are in points with the origin at the TOP-LEFT (like a bitmap).
 * A *logical* side is what the reader sees (may be landscape). A *physical*
 * side is always portrait, which is how paper travels through the printer;
 * landscape sides are turned 90° anticlockwise. Controlling that rotation
 * ourselves is what makes guided two-sided printing reliable.
 */

const val PT_PER_MM = 72f / 25.4f

class LayoutException(message: String) : Exception(message)

data class PageSize(val width: Float, val height: Float)

/** Affine transform: x' = a·x + c·y + e,  y' = b·x + d·y + f */
data class Affine(val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f, val e: Float = 0f, val f: Float = 0f) {
    /** this, then [o] */
    fun then(o: Affine) = Affine(
        a = o.a * a + o.c * b,
        b = o.b * a + o.d * b,
        c = o.a * c + o.c * d,
        d = o.b * c + o.d * d,
        e = o.a * e + o.c * f + o.e,
        f = o.b * e + o.d * f + o.f,
    )

    fun map(x: Float, y: Float) = Pair(a * x + c * y + e, b * x + d * y + f)

    /** Values for android.graphics.Matrix.setValues */
    fun toMatrixValues() = floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f)

    companion object {
        fun scale(sx: Float, sy: Float = sx) = Affine(a = sx, d = sy)
        fun translate(tx: Float, ty: Float) = Affine(e = tx, f = ty)
    }
}

data class Rect(val x: Float, val y: Float, val w: Float, val h: Float)

/** One source page drawn on a side: page point (u, v) -> (x + u·scale, y + v·scale). */
data class Placement(val src: Int, val x: Float, val y: Float, val scale: Float, val cell: Rect) {
    val pageToLogical get() = Affine.scale(scale).then(Affine.translate(x, y))
}

data class Side(val landscape: Boolean, val placements: List<Placement>, val numbers: List<Int>)

data class Sheet(val front: Side, val back: Side? = null)

data class Plan(
    val paperW: Float,
    val paperH: Float,
    val sheets: List<Sheet>,
    val settings: PrintSettings,
    val sourcePages: Int,
    val selectedPages: Int,
    val borders: Boolean,
) {
    val twoSided get() = settings.twoSided
    val physicalSheets get() = sheets.size * settings.copies

    fun logicalSize(side: Side?) =
        if (side?.landscape == true) PageSize(paperH, paperW) else PageSize(paperW, paperH)

    /** Logical side coordinates -> physical portrait page coordinates. */
    fun physicalTransform(side: Side?, rotate180: Boolean): Affine {
        var t = Affine()
        if (side?.landscape == true) {
            // 90° anticlockwise: the top of the landscape side goes to the left edge.
            t = Affine(a = 0f, b = -1f, c = 1f, d = 0f, e = 0f, f = paperH)
        }
        if (rotate180) t = t.then(Affine(a = -1f, b = 0f, c = 0f, d = -1f, e = paperW, f = paperH))
        return t
    }
}

private val GRIDS = mapOf(1 to (1 to 1), 2 to (2 to 1), 4 to (2 to 2), 6 to (3 to 2), 9 to (3 to 3), 16 to (4 to 4))

fun parsePageRange(text: String, pageCount: Int): List<Int> {
    val t = text.trim()
    if (t.isEmpty()) return (0 until pageCount).toList()
    val out = mutableListOf<Int>()
    val range = Regex("""(\d*)\s*[-–]\s*(\d*)""")
    for (part in t.split(Regex("""[,;\s]+""")).filter { it.isNotEmpty() }) {
        val m = range.matchEntire(part)
        val (start, end) = when {
            m != null -> (m.groupValues[1].toIntOrNull() ?: 1) to (m.groupValues[2].toIntOrNull() ?: pageCount)
            part.all { it.isDigit() } -> part.toInt().let { it to it }
            else -> throw LayoutException("“$part” isn’t a page number. Use numbers and dashes, like 1-3, 5, 8-10.")
        }
        if (start < 1 || end < 1) throw LayoutException("Page numbers start at 1.")
        if (start > pageCount) throw LayoutException(
            "Page $start doesn’t exist. The document has $pageCount page${if (pageCount == 1) "" else "s"}."
        )
        val e = min(end, pageCount)
        if (e >= start) out += (start - 1..e - 1) else out += (start - 1 downTo e - 1)
    }
    if (out.isEmpty()) throw LayoutException("The page range doesn’t include any pages.")
    return out
}

private data class Grid(val landscape: Boolean, val cols: Int, val rows: Int)

private fun chooseGrid(n: Int, pw: Float, ph: Float, paperW: Float, paperH: Float, orientation: Orientation, margin: Float, gap: Float): Grid {
    val (cols, rows) = GRIDS.getValue(n)
    val arrangements = setOf(cols to rows, rows to cols).sortedWith(compareBy({ it.first }, { it.second }))
    val orients = when (orientation) {
        Orientation.PORTRAIT -> listOf(false)
        Orientation.LANDSCAPE -> listOf(true)
        Orientation.AUTO -> listOf(false, true)
    }
    var best: Grid? = null
    var bestKey = Pair(-1f, false)
    for (land in orients) {
        val sw = if (land) paperH else paperW
        val sh = if (land) paperW else paperH
        for ((c, r) in arrangements) {
            val cw = (sw - 2 * margin - gap * (c - 1)) / c
            val ch = (sh - 2 * margin - gap * (r - 1)) / r
            if (cw <= 0 || ch <= 0) continue
            val scale = (min(cw / pw, ch / ph) * 10000).toInt() / 10000f
            val key = Pair(scale, land == (n == 2 || n == 6))
            if (best == null || key.first > bestKey.first || (key.first == bestKey.first && key.second && !bestKey.second)) {
                best = Grid(land, c, r); bestKey = key
            }
        }
    }
    return best ?: throw LayoutException("The margins are too big for this paper size.")
}

/**
 * @param pageSizes size of every source page, in points
 * @param fileStarts index of the first page of each file after the first
 */
fun makePlan(pageSizes: List<PageSize>, settingsIn: PrintSettings, fileStarts: List<Int> = emptyList()): Plan {
    val s = settingsIn.normalized()
    val total = pageSizes.size
    if (total == 0) throw LayoutException("The document has no pages.")
    var indices = parsePageRange(s.pageRange, total)
    when (s.pageSubset) {
        PageSubset.ODD -> indices = indices.filter { it % 2 == 0 }
        PageSubset.EVEN -> indices = indices.filter { it % 2 == 1 }
        PageSubset.ALL -> {}
    }
    if (indices.isEmpty()) throw LayoutException("There are no ${s.pageSubset.name.lowercase()} pages in that range.")

    val paperW = s.paper.widthPt
    val paperH = s.paper.heightPt
    val n = s.pagesPerSide
    val margin = s.marginMm * PT_PER_MM
    val gap = if (n > 1) 4 * PT_PER_MM else 0f

    var slots: List<Int?> = indices
    if (s.booklet) {
        val padded = slots.toMutableList()
        while (padded.size % 4 != 0) padded += null
        val m = padded.size
        val ordered = mutableListOf<Int?>()
        for (i in 0 until m / 4) {
            ordered += listOf(padded[m - 1 - 2 * i], padded[2 * i], padded[2 * i + 1], padded[m - 2 - 2 * i])
        }
        slots = ordered
    } else if (s.filesOnNewSheet && fileStarts.isNotEmpty()) {
        val unit = n * (if (s.twoSided) 2 else 1)
        val starts = fileStarts.toSet()
        val padded = mutableListOf<Int?>()
        for (src in slots) {
            if (src in starts && padded.isNotEmpty()) while (padded.size % unit != 0) padded += null
            padded += src
        }
        slots = padded
    }

    val groups = slots.chunked(n)
    val first = pageSizes[indices.first()]
    val fixed = chooseGrid(n, first.width, first.height, paperW, paperH, s.orientation, margin, gap)

    fun buildSide(group: List<Int?>): Side {
        val grid = if (n == 1 && s.orientation == Orientation.AUTO && group[0] != null) {
            val p = pageSizes[group[0]!!]
            Grid(p.width > p.height, 1, 1)
        } else fixed
        val sw = if (grid.landscape) paperH else paperW
        val sh = if (grid.landscape) paperW else paperH
        val cw = (sw - 2 * margin - gap * (grid.cols - 1)) / grid.cols
        val ch = (sh - 2 * margin - gap * (grid.rows - 1)) / grid.rows
        val placements = mutableListOf<Placement>()
        val numbers = mutableListOf<Int>()
        group.forEachIndexed { pos, src ->
            if (src == null) return@forEachIndexed
            val (col, row) = if (s.pageOrder == PageOrder.DOWN) (pos / grid.rows) to (pos % grid.rows)
            else (pos % grid.cols) to (pos / grid.cols)
            var cell = Rect(margin + col * (cw + gap), margin + row * (ch + gap), cw, ch)
            val p = pageSizes[src]
            val fit = min(cw / p.width, ch / p.height)
            val scale = when (s.scaling) {
                Scaling.FIT -> fit
                Scaling.SHRINK -> min(1f, fit)
                Scaling.ACTUAL -> 1f
                Scaling.CUSTOM -> s.scalePercent / 100f
            }
            if (n == 1 && (s.scaling == Scaling.ACTUAL || s.scaling == Scaling.CUSTOM)) cell = Rect(0f, 0f, sw, sh)
            val x = cell.x + (cell.w - p.width * scale) / 2
            val y = cell.y + (cell.h - p.height * scale) / 2
            placements += Placement(src, x, y, scale, cell)
            numbers += src + 1
        }
        return Side(grid.landscape, placements, numbers)
    }

    val sheets = if (!s.twoSided) groups.map { Sheet(buildSide(it)) }
    else groups.chunked(2).map { pair ->
        val back = pair.getOrNull(1)?.let { buildSide(it) }?.takeIf { it.placements.isNotEmpty() }
        Sheet(buildSide(pair[0]), back)
    }
    return Plan(paperW, paperH, sheets, s, total, indices.size, s.borders && n > 1)
}

// ---------------------------------------------------------------------------
// Turning a plan into print passes
// ---------------------------------------------------------------------------

data class PhysicalPage(val side: Side?, val rotate180: Boolean = false)

data class Pass(val label: String, val pages: List<PhysicalPage>, val sides: Sides = Sides.ONE)

data class JobPlan(val passes: List<Pass>, val manualDuplex: Boolean, val removeTopSheet: Boolean, val sheets: Int)

fun buildJobs(plan: Plan): JobPlan {
    val s = plan.settings
    val seq: List<Sheet> = if (s.collate) List(s.copies) { plan.sheets }.flatten()
    else plan.sheets.flatMap { sh -> List(s.copies) { sh } }

    if (!plan.twoSided) {
        val order = if (s.lastPageFirst) seq.reversed() else seq
        return JobPlan(listOf(Pass("All pages", order.map { PhysicalPage(it.front) })), false, false, seq.size)
    }
    if (s.duplexMethod == DuplexMethod.AUTO) {
        val pages = seq.flatMap { listOf(PhysicalPage(it.front), PhysicalPage(it.back)) }
        return JobPlan(listOf(Pass("Both sides", pages, s.sides)), false, false, seq.size)
    }
    // Guided two-sided printing: the user always turns the pile over like a
    // book page, so short-edge binding needs the backs turned 180°.
    val rotate = (s.sides == Sides.SHORT) != s.rotateBacks
    val fronts = seq.map { PhysicalPage(it.front) }
    val backs = seq.map { it.back }.toMutableList()
    var removeTop = false
    if (!s.reverseBacks && backs.isNotEmpty() && backs.last() == null) {
        backs.removeAt(backs.lastIndex)
        removeTop = true
    }
    if (s.reverseBacks) backs.reverse()
    val passes = mutableListOf(Pass("Front sides", fronts))
    if (backs.isNotEmpty()) passes += Pass("Back sides", backs.map { PhysicalPage(it, rotate) })
    return JobPlan(passes, true, removeTop, seq.size)
}

fun plural(n: Int, word: String, many: String = word + "s") = "$n ${if (n == 1) word else many}"

/** One readable sentence, e.g. "3 sheets of A4, 2 pages per side, both sides, flip on long edge, black and white, standard quality." */
fun describe(plan: Plan): String {
    val s = plan.settings
    val parts = mutableListOf("${plural(plan.physicalSheets, "sheet")} of ${s.paper.shortLabel}")
    if (s.booklet) parts += "booklet" else if (s.pagesPerSide > 1) parts += "${s.pagesPerSide} pages per side"
    parts += when (s.sides) {
        Sides.ONE -> "one side"
        Sides.LONG -> "both sides, flip on long edge"
        Sides.SHORT -> "both sides, flip on short edge"
    }
    parts += if (s.color == ColorMode.BW) "black and white" else "colour"
    parts += "${s.quality.label.lowercase()} quality"
    if (s.copies > 1) parts += "${s.copies} copies"
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
}

fun pagesText(numbers: List<Int>?): String {
    if (numbers.isNullOrEmpty()) return "blank"
    if (numbers.size == 1) return "page ${numbers[0]}"
    val consecutive = numbers.zipWithNext().all { (a, b) -> b == a + 1 }
    if (consecutive && numbers.size > 2) return "pages ${numbers.first()}–${numbers.last()}"
    return "pages ${numbers.dropLast(1).joinToString(", ")} and ${numbers.last()}"
}

/** Close enough? (used when matching printer media) */
fun near(a: Float, b: Float, tol: Float = 3f) = abs(a - b) <= tol
