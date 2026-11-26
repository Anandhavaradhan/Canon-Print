package app.easyprint.android

import android.content.Context
import app.easyprint.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ThemeMode(val label: String) { SYSTEM("Automatic"), LIGHT("Light"), DARK("Dark"), CONTRAST("High contrast") }

data class LookPrefs(
    val textScale: Float = 1f,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val reduceMotion: Boolean = false,
    val speak: Boolean = false,
)

data class SavedPreset(val id: String, val name: String, val settings: PrintSettings)

data class HistoryFile(val name: String, val path: String, val kind: DocKind)

data class HistoryEntry(
    val id: String,
    val time: Long,
    val title: String,
    val files: List<HistoryFile>,
    val settings: PrintSettings,
    val printer: String,
    val sheets: Int,
)

/** Small JSON-in-SharedPreferences store. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("easyprint", Context.MODE_PRIVATE)

    var settings: PrintSettings
        get() = prefs.getString("settings", null)?.let { runCatching { settingsFrom(JSONObject(it)) }.getOrNull() } ?: PrintSettings()
        set(v) = prefs.edit().putString("settings", settingsJson(v).toString()).apply()

    var look: LookPrefs
        get() = prefs.getString("look", null)?.let {
            runCatching {
                val o = JSONObject(it)
                LookPrefs(
                    textScale = o.optDouble("textScale", 1.0).toFloat(),
                    theme = enumOr(o.optString("theme"), ThemeMode.SYSTEM),
                    reduceMotion = o.optBoolean("reduceMotion"),
                    speak = o.optBoolean("speak"),
                )
            }.getOrNull()
        } ?: LookPrefs()
        set(v) = prefs.edit().putString("look", JSONObject().apply {
            put("textScale", v.textScale.toDouble()); put("theme", v.theme.name)
            put("reduceMotion", v.reduceMotion); put("speak", v.speak)
        }.toString()).apply()

    var lastPrinter: String?
        get() = prefs.getString("printer", null)
        set(v) = prefs.edit().putString("printer", v).apply()

    var manualPrinters: List<PrinterChoice>
        get() = array("manualPrinters").map { o ->
            if (o.optString("kind") == "canon")
                PrinterChoice.Canon(o.getString("name"), o.getString("host"), o.optString("mac"), manual = true, port = o.optInt("port", 9100))
            else
                PrinterChoice.Direct(o.getString("name"), o.getString("host"), o.optInt("port", 631), o.optString("path", "ipp/print"), manual = true)
        }
        set(v) = putArray("manualPrinters", v.map { p ->
            when (p) {
                is PrinterChoice.Canon -> JSONObject().put("kind", "canon").put("name", p.name).put("host", p.host).put("mac", p.mac).put("port", p.port)
                is PrinterChoice.Direct -> JSONObject().put("kind", "ipp").put("name", p.name).put("host", p.host).put("port", p.port).put("path", p.path)
            }
        })

    var presets: List<SavedPreset>
        get() = array("presets").mapNotNull { o ->
            runCatching { SavedPreset(o.getString("id"), o.getString("name"), settingsFrom(o.getJSONObject("settings"))) }.getOrNull()
        }
        set(v) = putArray("presets", v.map { JSONObject().put("id", it.id).put("name", it.name).put("settings", settingsJson(it.settings)) })

    var history: List<HistoryEntry>
        get() = array("history").mapNotNull { o ->
            runCatching {
                val files = (0 until o.getJSONArray("files").length()).map { i ->
                    val f = o.getJSONArray("files").getJSONObject(i)
                    HistoryFile(f.getString("name"), f.getString("path"), enumOr(f.optString("kind"), DocKind.PDF))
                }
                HistoryEntry(o.getString("id"), o.getLong("time"), o.getString("title"), files,
                    settingsFrom(o.getJSONObject("settings")), o.optString("printer"), o.optInt("sheets"))
            }.getOrNull()
        }.filter { e -> e.files.all { File(it.path).exists() } }
        set(v) = putArray("history", v.map { e ->
            JSONObject().put("id", e.id).put("time", e.time).put("title", e.title)
                .put("files", JSONArray(e.files.map { JSONObject().put("name", it.name).put("path", it.path).put("kind", it.kind.name) }))
                .put("settings", settingsJson(e.settings)).put("printer", e.printer).put("sheets", e.sheets)
        })

    private fun array(key: String): List<JSONObject> = prefs.getString(key, null)?.let { s ->
        runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.getOrNull()
    } ?: emptyList()

    private fun putArray(key: String, items: List<JSONObject>) = prefs.edit().putString(key, JSONArray(items).toString()).apply()

    companion object {
        inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
            runCatching { enumValueOf<T>(name ?: "") }.getOrDefault(default)

        fun settingsJson(s: PrintSettings) = JSONObject().apply {
            put("paper", s.paper.name); put("paperType", s.paperType.name); put("orientation", s.orientation.name)
            put("pagesPerSide", s.pagesPerSide); put("pageOrder", s.pageOrder.name); put("borders", s.borders)
            put("scaling", s.scaling.name); put("scalePercent", s.scalePercent); put("marginMm", s.marginMm.toDouble())
            put("sides", s.sides.name); put("booklet", s.booklet); put("pageRange", s.pageRange)
            put("pageSubset", s.pageSubset.name); put("copies", s.copies); put("collate", s.collate)
            put("lastPageFirst", s.lastPageFirst); put("color", s.color.name); put("quality", s.quality.name)
            put("duplexMethod", s.duplexMethod.name); put("rotateBacks", s.rotateBacks); put("reverseBacks", s.reverseBacks)
            put("filesOnNewSheet", s.filesOnNewSheet)
        }

        fun settingsFrom(o: JSONObject): PrintSettings {
            val d = PrintSettings()
            return PrintSettings(
                paper = enumOr(o.optString("paper"), d.paper),
                paperType = enumOr(o.optString("paperType"), d.paperType),
                orientation = enumOr(o.optString("orientation"), d.orientation),
                pagesPerSide = o.optInt("pagesPerSide", d.pagesPerSide),
                pageOrder = enumOr(o.optString("pageOrder"), d.pageOrder),
                borders = o.optBoolean("borders", d.borders),
                scaling = enumOr(o.optString("scaling"), d.scaling),
                scalePercent = o.optInt("scalePercent", d.scalePercent),
                marginMm = o.optDouble("marginMm", d.marginMm.toDouble()).toFloat(),
                sides = enumOr(o.optString("sides"), d.sides),
                booklet = o.optBoolean("booklet", d.booklet),
                pageRange = o.optString("pageRange", d.pageRange),
                pageSubset = enumOr(o.optString("pageSubset"), d.pageSubset),
                copies = o.optInt("copies", d.copies),
                collate = o.optBoolean("collate", d.collate),
                lastPageFirst = o.optBoolean("lastPageFirst", d.lastPageFirst),
                color = enumOr(o.optString("color"), d.color),
                quality = enumOr(o.optString("quality"), d.quality),
                duplexMethod = enumOr(o.optString("duplexMethod"), d.duplexMethod),
                rotateBacks = o.optBoolean("rotateBacks", d.rotateBacks),
                reverseBacks = o.optBoolean("reverseBacks", d.reverseBacks),
                filesOnNewSheet = o.optBoolean("filesOnNewSheet", d.filesOnNewSheet),
            ).normalized()
        }
    }
}
