package app.easyprint.android

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.easyprint.core.PageSize
import java.io.File
import java.util.UUID

enum class DocKind { PDF, IMAGE }

/** A document the user opened, copied into the app's private storage. */
data class OpenedFile(
    val id: String,
    val name: String,
    val kind: DocKind,
    val file: File,
    val pages: List<PageSize>,
    /** Clockwise rotation needed to show an image upright (EXIF). */
    val imageRotation: Int = 0,
)

class ImportException(message: String) : Exception(message)

object Documents {
    private const val IMAGE_DPI = 150f

    fun docsDir(context: Context) = File(context.filesDir, "docs").apply { mkdirs() }

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
    }

    fun import(context: Context, uri: Uri): OpenedFile {
        val name = displayName(context, uri)
        val mime = context.contentResolver.getType(uri) ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        val kind = when {
            mime == "application/pdf" || ext == "pdf" -> "pdf"
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif") -> "image"
            mime.startsWith("text/") || ext in setOf("txt", "md", "csv", "log") -> "text"
            ext in setOf("doc", "docx", "odt", "xls", "xlsx", "ppt", "pptx") ->
                throw ImportException("“$name” is an Office file. Open it in Word, Docs or Sheets, choose Share → Print or Save as PDF, then open the PDF here.")
            else -> throw ImportException("“$name” can’t be printed. You can open PDFs, photos and text files.")
        }
        val id = UUID.randomUUID().toString().take(12)
        val dest = File(docsDir(context), "$id.${if (kind == "image") ext.ifBlank { "img" } else "pdf"}")
        try {
            if (kind == "text") {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw ImportException("“$name” couldn’t be read.")
                textToPdf(text, dest)
            } else {
                context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    ?: throw ImportException("“$name” couldn’t be read.")
            }
            return fromFile(id, name, dest, if (kind == "image") DocKind.IMAGE else DocKind.PDF)
        } catch (e: ImportException) {
            dest.delete(); throw e
        } catch (e: Exception) {
            dest.delete(); throw ImportException("“$name” couldn’t be opened (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    /** Re-open a stored copy (used by Recent prints). */
    fun fromFile(id: String, name: String, file: File, kind: DocKind): OpenedFile {
        if (kind == DocKind.PDF) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                pfd.close()
                throw ImportException("“$name” is protected with a password. Remove the password (or print it from the app that opened it), then try again.")
            } catch (e: Exception) {
                pfd.close()
                throw ImportException("“$name” isn’t a PDF this app can read.")
            }
            renderer.use { r ->
                if (r.pageCount == 0) throw ImportException("“$name” has no pages.")
                val sizes = (0 until r.pageCount).map { i -> r.openPage(i).use { PageSize(it.width.toFloat(), it.height.toFloat()) } }
                return OpenedFile(id, name, DocKind.PDF, file, sizes)
            }
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        if (opts.outWidth <= 0) throw ImportException("“$name” isn’t a picture this app can read.")
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        var w = opts.outWidth.toFloat(); var h = opts.outHeight.toFloat()
        if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
        return OpenedFile(id, name, DocKind.IMAGE, file, listOf(PageSize(w * 72f / IMAGE_DPI, h * 72f / IMAGE_DPI)), rotation)
    }

    /** Lay out plain text on A4 pages. */
    fun textToPdf(text: String, dest: File) {
        val pw = 595; val ph = 842; val margin = 56
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK; typeface = Typeface.SANS_SERIF }
        val layout = StaticLayout.Builder.obtain(text.ifEmpty { " " }, 0, text.ifEmpty { " " }.length, paint, pw - 2 * margin)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
        val doc = PdfDocument()
        val usable = ph - 2 * margin
        var line = 0
        var pageNo = 1
        while (line < layout.lineCount) {
            val top = layout.getLineTop(line)
            var last = line
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= usable) last++
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo++).create())
            val c = page.canvas
            c.save()
            c.translate(margin.toFloat(), margin.toFloat() - top)
            c.clipRect(0, top, pw - 2 * margin, layout.getLineBottom(last))
            layout.draw(c)
            c.restore()
            doc.finishPage(page)
            line = last + 1
        }
        dest.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    /** Four big numbered pages used by the two-sided test. */
    fun calibrationPdf(dest: File) {
        val pw = 595; val ph = 842
        val doc = PdfDocument()
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 300f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; textAlign = Paint.Align.CENTER }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
        for (n in 1..4) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, n).create())
            val c = page.canvas
            c.drawRect(20f, 20f, pw - 20f, ph - 20f, line)
            c.drawText("TOP OF PAGE", pw / 2f, 80f, small)
            val arrow = android.graphics.Path().apply { moveTo(pw / 2f, 100f); lineTo(pw / 2f - 40, 160f); lineTo(pw / 2f + 40, 160f); close() }
            c.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG))
            c.drawText("$n", pw / 2f, ph * 0.55f, big)
            val front = n % 2 == 1
            c.drawText("Test page $n · ${if (front) "FRONT" else "BACK"} side", pw / 2f, ph * 0.72f, body)
            c.drawText("Turn the sheet over like a book page:", pw / 2f, ph * 0.72f + 30, body)
            c.drawText("page ${if (front) n + 1 else n - 1} should be on the other side, the right way up.", pw / 2f, ph * 0.72f + 56, body)
            doc.finishPage(page)
        }
        dest.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
