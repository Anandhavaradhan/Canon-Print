package app.easyprint

import app.easyprint.android.*
import app.easyprint.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Runs the app's real print pipeline (PDF -> PdfRenderer[PDFium] -> plan -> passes -> PWG raster)
 * for every scenario in $SCENARIOS and writes the spooled jobs to $SCENARIO_OUT/<name>/.
 * tools/printsim then feeds them through a simulated printer and a simulated person.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ShadowPdfiumRenderer::class, ShadowPdfiumPage::class])
class ScenarioExportTest {
    @Test fun export() {
        val scenarios = System.getenv("SCENARIOS")?.let { File(it) }
        val out = System.getenv("SCENARIO_OUT")?.let { File(it) }
        assumeTrue(scenarios != null && out != null && Pdfium.available)
        val list = JSONArray(scenarios!!.readText())
        // A printer that offers 100 dpi keeps the files small; everything else is like a G3000.
        val info = PrinterInfo("Simulated G3010", 3, emptyList(), "", listOf("image/pwg-raster"), listOf(100, 300, 600),
            listOf("srgb_8", "sgray_8"), "normal", emptyList(), emptyList(), emptyList(), listOf("one-sided"),
            listOf("color", "monochrome"), listOf(3, 4, 5), emptyList())
        for (i in 0 until list.length()) {
            val sc = list.getJSONObject(i)
            val name = sc.getString("name")
            val docs = sc.getJSONArray("files").let { a -> (0 until a.length()).map { a.getString(it) } }
            val files = docs.mapIndexed { k, path -> Documents.fromFile("f$k", File(path).name, File(path), DocKind.PDF) }
            val settings = Store.settingsFrom(sc.getJSONObject("settings")).copy(quality = Quality.DRAFT)
            val r = DocRenderer(files)
            val plan = makePlan(r.pageSizes, settings, r.fileStarts)
            val jobs = buildJobs(plan)
            val dir = File(out, name).apply { deleteRecursively(); mkdirs() }
            jobs.passes.forEachIndexed { p, pass ->
                DirectOutput.writeRaster(r, plan, pass, info, plan.settings, File(dir, "pass$p.pwg")) { _, _ -> }
            }
            File(dir, "meta.json").writeText(JSONObject().apply {
                put("passes", jobs.passes.size); put("manualDuplex", jobs.manualDuplex)
                put("removeTopSheet", jobs.removeTopSheet); put("sheets", jobs.sheets)
                put("reverseBacks", plan.settings.reverseBacks)
                put("sentence", describe(plan))
                put("ipp", JSONArray(jobs.passes.map { DirectOutput.jobOptions(info, plan.settings, it, name).toString() }))
            }.toString(2))
            r.close()
            println("exported $name: ${describe(plan)}")
        }
    }
}
