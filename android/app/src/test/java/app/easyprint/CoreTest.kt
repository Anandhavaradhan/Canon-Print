package app.easyprint

import app.easyprint.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class LayoutTest {
    private val a4 = PageSize(595f, 842f)
    private fun pages(n: Int, landscape: Set<Int> = emptySet()) =
        (1..n).map { if (it in landscape) PageSize(842f, 595f) else a4 }

    @Test fun pageRange() {
        assertEquals(listOf(0, 1, 2, 3), parsePageRange("", 4))
        assertEquals(listOf(0, 1, 3), parsePageRange("1-2, 4", 5))
        assertEquals(listOf(2, 3, 4), parsePageRange("3-", 5))
        assertThrows(LayoutException::class.java) { parsePageRange("9", 5) }
        assertThrows(LayoutException::class.java) { parsePageRange("abc", 5) }
    }

    @Test fun twoPerSide() {
        val p = makePlan(pages(5), PrintSettings(pagesPerSide = 2))
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), p.sheets.map { it.front.numbers })
        assertTrue(p.sheets.all { it.front.landscape })
    }

    @Test fun manualDuplexOddPages() {
        val p = makePlan(pages(5), PrintSettings(sides = Sides.LONG))
        assertEquals(listOf(listOf(2), listOf(4), null), p.sheets.map { it.back?.numbers })
        val j = buildJobs(p)
        assertTrue(j.manualDuplex && j.removeTopSheet)
        assertEquals(listOf(3, 2), j.passes.map { it.pages.size })
        assertFalse(j.passes[1].pages[0].rotate180)
    }

    @Test fun shortEdgeRotatesBacks() {
        val j = buildJobs(makePlan(pages(4), PrintSettings(sides = Sides.SHORT)))
        assertTrue(j.passes[1].pages.all { it.rotate180 })
        val j2 = buildJobs(makePlan(pages(4), PrintSettings(sides = Sides.SHORT, rotateBacks = true)))
        assertTrue(j2.passes[1].pages.none { it.rotate180 })
    }

    @Test fun booklet() {
        val p = makePlan(pages(8), PrintSettings(booklet = true))
        assertEquals(listOf(listOf(8, 1) to listOf(2, 7), listOf(6, 3) to listOf(4, 5)),
            p.sheets.map { it.front.numbers to it.back!!.numbers })
        assertEquals(Sides.SHORT, p.settings.sides)
    }

    @Test fun filesStartOnNewSheet() {
        val p = makePlan(pages(6), PrintSettings(sides = Sides.LONG), fileStarts = listOf(3))
        assertEquals(listOf(listOf(1) to listOf(2), listOf(3) to null, listOf(4) to listOf(5), listOf(6) to null),
            p.sheets.map { it.front.numbers to it.back?.numbers })
    }

    @Test fun copiesAndLastPageFirst() {
        val p = makePlan(pages(2), PrintSettings(copies = 2, collate = true, lastPageFirst = true))
        val order = buildJobs(p).passes[0].pages.map { it.side!!.numbers[0] }
        assertEquals(listOf(2, 1, 2, 1), order)
        val p2 = makePlan(pages(2), PrintSettings(copies = 2, collate = false, lastPageFirst = false))
        assertEquals(listOf(1, 1, 2, 2), buildJobs(p2).passes[0].pages.map { it.side!!.numbers[0] })
    }

    @Test fun oddEven() {
        val p = makePlan(pages(6), PrintSettings(pageSubset = PageSubset.EVEN))
        assertEquals(listOf(2, 4, 6), p.sheets.map { it.front.numbers[0] })
    }

    @Test fun landscapeGoesAnticlockwiseOntoPortrait() {
        val p = makePlan(pages(1, setOf(1)), PrintSettings())
        val side = p.sheets[0].front
        assertTrue(side.landscape)
        val t = p.physicalTransform(side, false)
        // top-left of the landscape side -> bottom-left of the portrait page
        val (x, y) = t.map(0f, 0f)
        assertEquals(0f, x, 0.01f); assertEquals(p.paperH, y, 0.01f)
        // top-right -> top-left
        val (x2, y2) = t.map(p.paperH, 0f)
        assertEquals(0f, x2, 0.01f); assertEquals(0f, y2, 0.01f)
    }

    @Test fun sentence() {
        val p = makePlan(pages(4), PrintSettings(pagesPerSide = 2, sides = Sides.LONG, color = ColorMode.BW))
        assertEquals("1 sheet of A4, 2 pages per side, both sides, flip on long edge, black and white, standard quality.", describe(p))
    }
}

class PwgTest {
    /** Independent decoder used to verify the encoder. */
    private fun decode(bytes: ByteArray): List<Triple<Int, Int, ByteArray>> {
        val din = DataInputStream(ByteArrayInputStream(bytes))
        val sync = ByteArray(4); din.readFully(sync)
        assertEquals("RaS2", String(sync))
        val pages = mutableListOf<Triple<Int, Int, ByteArray>>()
        while (din.available() > 0) {
            val h = ByteArray(1796); din.readFully(h)
            fun u(o: Int) = java.nio.ByteBuffer.wrap(h, o, 4).int
            val w = u(372); val ht = u(376); val bpl = u(392); val bpp = u(388) / 8
            val out = ByteArray(bpl * ht)
            var row = 0
            while (row < ht) {
                val rep = din.readUnsignedByte() + 1
                val line = ByteArray(bpl); var x = 0
                while (x < w) {
                    val c = din.readUnsignedByte()
                    if (c <= 127) {
                        val px = ByteArray(bpp); din.readFully(px)
                        repeat(c + 1) { System.arraycopy(px, 0, line, x * bpp, bpp); x++ }
                    } else {
                        val n = 257 - c
                        din.readFully(line, x * bpp, n * bpp); x += n
                    }
                }
                repeat(rep) { System.arraycopy(line, 0, out, row * bpl, bpl); row++ }
            }
            pages += Triple(w, ht, out)
        }
        return pages
    }

    @Test fun roundTrip() {
        val w = 300; val h = 260
        val rnd = java.util.Random(4)
        val px = IntArray(w * h) { i ->
            val y = i / w; val x = i % w
            when {
                y < 40 -> 0xffffffff.toInt()               // repeated white lines
                y < 80 -> if (x % 7 == 0) 0xff102030.toInt() else 0xffffffff.toInt()
                else -> 0xff000000.toInt() or rnd.nextInt(0xffffff) // noise (literal runs)
            }
        }
        for (gray in listOf(false, true)) {
            val bos = ByteArrayOutputStream()
            val wr = PwgRasterWriter(bos)
            val header = PwgRasterWriter.PageHeader(w, h, 300, gray, 72 to 62, "iso_a4_210x297mm", "stationery", 4, 1)
            wr.beginPage(header)
            wr.writeRows(px.copyOfRange(0, w * 100), w, 100)
            wr.writeRows(px.copyOfRange(w * 100, w * h), w, h - 100)
            wr.endPage()
            val (dw, dh, data) = decode(bos.toByteArray()).single()
            assertEquals(w, dw); assertEquals(h, dh)
            for (i in 0 until w * h step 997) {
                val p = px[i]
                if (gray) {
                    val g = (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000
                    assertEquals(g, data[i].toInt() and 0xff)
                } else {
                    assertEquals((p shr 16) and 0xff, data[i * 3].toInt() and 0xff)
                    assertEquals(p and 0xff, data[i * 3 + 2].toInt() and 0xff)
                }
            }
        }
    }
}

class IppTest {
    @Test fun requestEncodesAndParses() {
        // Build a "response" with the request builder (same wire format) and parse it.
        val bytes = IppRequest(0x0000, 7)
            .group(Ipp.OPERATION)
            .string(Ipp.CHARSET, "attributes-charset", "utf-8")
            .group(Ipp.PRINTER)
            .string(Ipp.KEYWORD, "document-format-supported", "application/octet-stream", "image/pwg-raster")
            .integer("printer-state", 3, Ipp.ENUM)
            .integer("marker-levels", 80).integer("", 55)
            .collection("media-col-default", mapOf("media-type" to "stationery", "media-size" to mapOf("x-dimension" to 21000)))
            .string(Ipp.KEYWORD, "sides-supported", "one-sided")
            .bytes()
        val r = IppParser.parse(ByteArrayInputStream(bytes))
        assertTrue(r.ok)
        assertEquals(7, r.requestId)
        assertEquals(listOf("application/octet-stream", "image/pwg-raster"), r.strings(Ipp.PRINTER, "document-format-supported"))
        assertEquals(listOf(80, 55), r.ints(Ipp.PRINTER, "marker-levels"))
        assertEquals(3, r.int(Ipp.PRINTER, "printer-state"))
        assertEquals(listOf("one-sided"), r.strings(Ipp.PRINTER, "sides-supported"))
        val col = r.group(Ipp.PRINTER)["media-col-default"]!!.single() as IppValue.Collection
        assertEquals("stationery", col.members["media-type"]!!.single().text())
    }
}
