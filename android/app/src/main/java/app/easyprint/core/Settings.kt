package app.easyprint.core

/** Everything the user can choose about a print job. Pure Kotlin (unit-testable). */

enum class Sides(val label: String) { ONE("One side"), LONG("Flip on long edge"), SHORT("Flip on short edge") }
enum class ColorMode { COLOR, BW }
enum class Quality(val label: String, val ippValue: Int) { DRAFT("Draft", 3), STANDARD("Standard", 4), HIGH("High", 5) }
enum class Orientation(val label: String) { AUTO("Automatic"), PORTRAIT("Portrait (tall)"), LANDSCAPE("Landscape (wide)") }
enum class Scaling(val label: String) { FIT("Fit to page"), SHRINK("Shrink only if too big"), ACTUAL("Actual size (100%)"), CUSTOM("Custom size") }
enum class PageSubset(val label: String) { ALL("All pages in the range"), ODD("Odd pages (1, 3, 5…)"), EVEN("Even pages (2, 4, 6…)") }
enum class PageOrder(val label: String) { ACROSS("Across, then down"), DOWN("Down, then across") }
enum class DuplexMethod { MANUAL, AUTO }

/**
 * Paper types offered by canong3000.ppd. [canonCode] is the IVEC papertype Canon's
 * driver sends (tocanonij cncl_paramtbl.h + cnijfilter2-loong64 tables.h, all
 * measured against Canon's library except OTHER_PHOTO, which is inferred there).
 */
enum class PaperType(val label: String, val ippMediaType: String, val canonCode: String) {
    PLAIN("Plain paper", "stationery", "stationery"),
    PHOTO_GLOSSY("Glossy Photo Paper", "photographic-glossy", "custom-media-type-canon-14"),
    PHOTO_PLUS_GLOSSY_II("Photo Paper Plus Glossy II", "photographic-glossy", "custom-media-type-canon-3"),
    PHOTO_SEMIGLOSS("Photo Paper Plus Semi-gloss", "photographic-semi-gloss", "custom-media-type-canon-6"),
    PHOTO_LUSTER("Photo Paper Pro Luster", "photographic-high-gloss", "custom-media-type-canon-17"),
    PHOTO_MATTE("Matte Photo Paper", "photographic-matte", "custom-media-type-canon-15"),
    PHOTO_PAPER("Photo Paper", "photographic", "photographic"),
    OTHER_PHOTO("Other Photo Paper", "photographic", "custom-media-type-canon-16"),
    HIGH_RES("High Resolution Paper", "stationery-coated", "custom-media-type-canon-9"),
    ENVELOPE("Envelope", "envelope", "custom-media-type-canon-18"),
}

/** Paper sizes in points (1/72 inch), always portrait. */
enum class Paper(val label: String, val shortLabel: String, val widthPt: Float, val heightPt: Float, val pwgName: String) {
    A4("A4 (210 × 297 mm)", "A4", 595.28f, 841.89f, "iso_a4_210x297mm"),
    LETTER("Letter (8.5 × 11 in)", "Letter", 612f, 792f, "na_letter_8.5x11in"),
    LEGAL("Legal (8.5 × 14 in)", "Legal", 612f, 1008f, "na_legal_8.5x14in"),
    A5("A5 (148 × 210 mm)", "A5", 419.53f, 595.28f, "iso_a5_148x210mm"),
    B5("B5 (182 × 257 mm)", "B5", 515.91f, 728.50f, "jis_b5_182x257mm"),
    PHOTO_4X6("Photo 4 × 6 in (10 × 15 cm)", "4×6 photo", 288f, 432f, "na_index-4x6_4x6in"),
    PHOTO_5X7("Photo 5 × 7 in (13 × 18 cm)", "5×7 photo", 360f, 504f, "na_5x7_5x7in"),
    PHOTO_8X10("Photo 8 × 10 in (20 × 25 cm)", "8×10 photo", 576f, 720f, "na_govt-letter_8x10in"),
    ENV_DL("Envelope DL (110 × 220 mm)", "DL envelope", 311.81f, 623.62f, "iso_dl_110x220mm"),
    ENV_10("Envelope #10 (4.1 × 9.5 in)", "#10 envelope", 296.98f, 684f, "na_number-10_4.125x9.5in"),
}

val PAGES_PER_SIDE_CHOICES = listOf(1, 2, 4, 6, 9, 16)

data class PrintSettings(
    val paper: Paper = Paper.A4,
    val paperType: PaperType = PaperType.PLAIN,
    val orientation: Orientation = Orientation.AUTO,
    val pagesPerSide: Int = 1,
    val pageOrder: PageOrder = PageOrder.ACROSS,
    val borders: Boolean = false,
    val scaling: Scaling = Scaling.FIT,
    val scalePercent: Int = 100,
    val marginMm: Float = 5f,
    val sides: Sides = Sides.ONE,
    val booklet: Boolean = false,
    val pageRange: String = "",
    val pageSubset: PageSubset = PageSubset.ALL,
    val copies: Int = 1,
    val collate: Boolean = true,
    /** G3000 output is face up, so printing the last page first leaves the pile in order. */
    val lastPageFirst: Boolean = true,
    val color: ColorMode = ColorMode.COLOR,
    val quality: Quality = Quality.STANDARD,
    val duplexMethod: DuplexMethod = DuplexMethod.MANUAL,
    /** Calibration: back sides come out upside down. */
    val rotateBacks: Boolean = false,
    /** Calibration: the flipped pile feeds in reverse. */
    val reverseBacks: Boolean = false,
    val filesOnNewSheet: Boolean = true,
) {
    /** Applies the rules that tie settings together (booklets are always 2-up, both sides). */
    fun normalized(): PrintSettings {
        var s = this
        if (s.pagesPerSide !in PAGES_PER_SIDE_CHOICES) s = s.copy(pagesPerSide = 1)
        s = s.copy(
            copies = s.copies.coerceIn(1, 999),
            scalePercent = s.scalePercent.coerceIn(10, 400),
            marginMm = s.marginMm.coerceIn(0f, 40f),
            pageRange = s.pageRange.trim(),
        )
        if (s.booklet) s = s.copy(pagesPerSide = 2, sides = Sides.SHORT, pageSubset = PageSubset.ALL)
        return s
    }

    val twoSided get() = sides != Sides.ONE
}

data class Preset(val id: String, val name: String, val apply: (PrintSettings) -> PrintSettings, val custom: Boolean = false)

object BuiltInPresets {
    val all = listOf(
        Preset("everyday", "Everyday B&W", { it.copy(sides = Sides.ONE, pagesPerSide = 1, booklet = false, color = ColorMode.BW, quality = Quality.STANDARD, paperType = PaperType.PLAIN) }),
        Preset("book", "Both sides, like a book", { it.copy(sides = Sides.LONG, pagesPerSide = 1, booklet = false, paperType = PaperType.PLAIN) }),
        Preset("handout", "2 per side, both sides", { it.copy(sides = Sides.SHORT, pagesPerSide = 2, booklet = false, borders = true, paperType = PaperType.PLAIN) }),
        Preset("ink", "Save ink", { it.copy(color = ColorMode.BW, quality = Quality.DRAFT, paperType = PaperType.PLAIN) }),
        Preset("photo", "Photo", { it.copy(sides = Sides.ONE, pagesPerSide = 1, booklet = false, color = ColorMode.COLOR, quality = Quality.HIGH, paperType = PaperType.PHOTO_GLOSSY, scaling = Scaling.FIT) }),
    )
}
