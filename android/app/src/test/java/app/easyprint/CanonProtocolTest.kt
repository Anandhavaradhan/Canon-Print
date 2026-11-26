package app.easyprint

import app.easyprint.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CanonProtocolTest {
    private fun page(w: Int, h: Int, file: File) {
        file.outputStream().buffered().use { os ->
            val wr = PwgRasterWriter(os)
            wr.beginPage(PwgRasterWriter.PageHeader(w, h, 600, false, 595 to 842, "iso_a4_210x297mm", "stationery", 0, 0))
            val row = IntArray(w * 64)
            var y = 0
            while (y < h) {
                val n = minOf(64, h - y)
                for (i in 0 until w * n) row[i] = if ((i % w) < w / 3 && (y + i / w) < h / 4) 0xff2050c0.toInt() else -1
                wr.writeRows(row, w, n); y += n
            }
            wr.endPage()
        }
    }

    /** Split a Canon job stream into (xml block, payload-after-it) pairs using SendData's datasize. */
    private fun split(bytes: ByteArray): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        var p = 0
        val s = String(bytes, Charsets.ISO_8859_1)
        while (p < bytes.size) {
            assertTrue("block must start with <?xml at $p", s.startsWith("<?xml", p))
            val end = s.indexOf("</cmd>", p) + 6
            val xml = String(bytes, p, end - p, Charsets.UTF_8)
            p = end
            var payload = 0
            Regex("<ivec:datasize>(\\d+)</ivec:datasize>").find(xml)?.let {
                payload = it.groupValues[1].toInt()
                assertEquals("payload starts with RaS2", "RaS2", String(bytes, p, 4, Charsets.US_ASCII))
                p += payload
            }
            out += xml to payload
        }
        return out
    }

    @Test fun jobStreamIsWellFormedAndMatchesCanonStructure() {
        val m = CanonG3000.media(Paper.A4)
        assertEquals("A4 imageable width at 600 dpi (Canon: 4800)", 4800, m.widthPx)
        assertEquals("A4 imageable height at 600 dpi (Canon: 6826)", 6826, m.heightPx)

        val dir = File(System.getenv("CANON_OUT") ?: System.getProperty("java.io.tmpdir")).apply { mkdirs() }
        val pages = (0 until 3).map { File(dir, "page$it.pwg").also { f -> page(m.widthPx, m.heightPx, f) } }
        val job = CanonG3000.JobInfo(7, "P&L <draft>.pdf", "Easy Print", Paper.A4, PaperType.PLAIN, monochrome = true,
            uuid = "11111111-2222-3333-4444-555555555555")
        val bos = ByteArrayOutputStream()
        CanonG3000.writeJob(bos, job, pages)
        File(dir, "app-job.bin").writeBytes(bos.toByteArray())

        val blocks = split(bos.toByteArray())
        val ops = blocks.map { Regex("<ivec:operation>(\\w+)</ivec:operation>").find(it.first)!!.groupValues[1] }
        assertEquals(listOf("StartJob", "SetJobConfiguration", "SetConfiguration",
            "VendorCmd", "SendData", "VendorCmd", "SendData", "VendorCmd", "SendData", "EndJob"), ops)
        assertEquals(listOf("ON", "ON", "OFF"), blocks.mapNotNull { Regex("<vcn:nextpage>(\\w+)<").find(it.first)?.groupValues?.get(1) })
        pages.forEachIndexed { i, f -> assertEquals(f.length().toInt(), blocks[4 + 2 * i].second) }
        assertTrue(blocks.all { it.first.contains("<ivec:jobID>00000007</ivec:jobID>") })

        // Every block must be well-formed, namespaced XML (a firmware parser would reject anything else).
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for ((xml, _) in blocks) dbf.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        assertTrue("title is escaped", blocks[0].first.contains("<ivec:jobname>P&amp;L &lt;draft&gt;.pdf</ivec:jobname>"))
        val cfg = blocks[2].first
        assertTrue(cfg.contains("<ivec:papersize>iso_a4_210x297mm</ivec:papersize>"))
        assertTrue(cfg.contains("<ivec:papertype>stationery</ivec:papertype>"))
        assertTrue(cfg.contains("<ivec:printcolormode>monochrome</ivec:printcolormode>"))
        assertTrue(cfg.contains("<ivec:duplexprint>OFF</ivec:duplexprint>"))
    }

    @Test fun snmpRoundTrip() {
        val req = Snmp.request("canon_admin", 1, CanonG3000.DISCOVERY_OIDS)
        // independent check of the BER encoding of one OID
        assertArrayEquals(byteArrayOf(0x2b, 6, 1, 2, 1, 1, 1, 0), Snmp.encodeOid("1.3.6.1.2.1.1.1.0"))
        assertArrayEquals(byteArrayOf(0x2b, 6, 1, 4, 1, 0x8c.toByte(), 0x42, 1, 1, 1, 1, 0), Snmp.encodeOid(CanonG3000.OID_MODEL))
        assertTrue(req.size in 60..200)
    }

    @Test fun statusText() {
        assertEquals("error", CanonG3000.describeStatus(3, byteArrayOf(0x40, 0)).first) // noPaper
        assertEquals("Ready", CanonG3000.describeStatus(3, byteArrayOf(0, 0)).second)
        assertEquals("busy", CanonG3000.describeStatus(4, null).first)
    }
}
