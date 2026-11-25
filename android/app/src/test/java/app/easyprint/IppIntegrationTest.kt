package app.easyprint

import app.easyprint.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs only when IPP_TEST_PORT is set (a local test IPP server on 127.0.0.1). */
class IppIntegrationTest {
    private val port = System.getenv("IPP_TEST_PORT")?.toIntOrNull()

    @Test fun queryAndPrint() {
        assumeTrue(port != null)
        val client = IppClient("127.0.0.1", port!!, "ipp/print", chunked = System.getenv("IPP_TEST_CHUNKED") != null)
        val info = client.getPrinterAttributes()
        println("make=${info.makeAndModel} state=${info.state} formats=${info.formats} sides=${info.sides}")
        assertTrue(info.state in 3..5)

        val file = File.createTempFile("job", ".pwg")
        file.outputStream().buffered().use { os ->
            val w = PwgRasterWriter(os)
            repeat(2) { page ->
                w.beginPage(PwgRasterWriter.PageHeader(200, 100, 75, page == 1, 192 to 96, "iso_a4_210x297mm", "stationery", 4, 2))
                w.writeRows(IntArray(200 * 100) { if (it % 200 < 100) 0xff000000.toInt() else -1 }, 200, 100)
                w.endPage()
            }
        }
        val jobId = client.printJob(file, IppClient.JobOptions("Test job", "iso_a4_210x297mm", "stationery", "monochrome", 4))
        println("job-id=$jobId")
        assertTrue(jobId > 0)
        System.getenv("IPP_TEST_EXPECT_FILE_SIZE_OUT")?.let { File(it).writeText(file.length().toString()) }
    }
}
