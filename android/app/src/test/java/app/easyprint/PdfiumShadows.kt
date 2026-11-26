package app.easyprint

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.FileInputStream
import java.io.IOException

/**
 * Robolectric has no PdfRenderer. Android's PdfRenderer is a thin JNI layer over
 * PDFium, so these shadows reproduce that JNI layer (libs/hwui/jni/pdf/PdfRenderer.cpp,
 * android14-release) using the real PDFium library bundled with pypdfium2.
 * Set PDFIUM_LIB to the path of libpdfium.so.
 */
interface Pdfium : Library {
    fun FPDF_InitLibrary()
    fun FPDF_LoadMemDocument(data: Pointer, size: Int, password: String?): Pointer?
    fun FPDF_GetLastError(): NativeLong
    fun FPDF_GetPageCount(doc: Pointer): Int
    fun FPDF_LoadPage(doc: Pointer, index: Int): Pointer?
    fun FPDF_GetPageWidthF(page: Pointer): Float
    fun FPDF_GetPageHeightF(page: Pointer): Float
    fun FPDF_ClosePage(page: Pointer)
    fun FPDF_CloseDocument(doc: Pointer)
    fun FPDFBitmap_CreateEx(w: Int, h: Int, format: Int, buf: Pointer, stride: Int): Pointer?
    fun FPDFBitmap_Destroy(b: Pointer)
    fun FPDF_RenderPageBitmapWithMatrix(bitmap: Pointer, page: Pointer, matrix: Pointer, clip: Pointer, flags: Int)

    companion object {
        val lib: Pdfium by lazy {
            val path = System.getenv("PDFIUM_LIB") ?: error("PDFIUM_LIB not set")
            // RTLD_LOCAL: keep PDFium's bundled FreeType from replacing the one Android's font code uses
            Native.load(path, Pdfium::class.java, mapOf(Library.OPTION_OPEN_FLAGS to 1)).also { it.FPDF_InitLibrary() }
        }
        val available get() = System.getenv("PDFIUM_LIB")?.let { java.io.File(it).exists() } == true
        /** Keep document memory alive while the document is open. */
        val buffers = HashMap<Long, Memory>()
    }
}

@Implements(PdfRenderer::class)
class ShadowPdfiumRenderer {
    @RealObject lateinit var real: PdfRenderer

    private fun set(name: String, v: Any?) {
        PdfRenderer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(real, v)
    }

    @Implementation
    protected fun __constructor__(input: ParcelFileDescriptor) {
        val bytes = FileInputStream(input.fileDescriptor).readBytes()
        val mem = Memory(bytes.size.toLong().coerceAtLeast(1)).apply { write(0, bytes, 0, bytes.size) }
        val doc = Pdfium.lib.FPDF_LoadMemDocument(mem, bytes.size, null)
        if (doc == null) {
            // Same exceptions as Android's nativeCreate
            when (Pdfium.lib.FPDF_GetLastError().toInt()) {
                4 -> throw SecurityException("password required or incorrect password")
                else -> throw IOException("file not in PDF format or corrupted")
            }
        }
        val handle = Pointer.nativeValue(doc)
        Pdfium.buffers[handle] = mem
        set("mInput", input)
        set("mNativeDocument", handle)
        set("mPageCount", Pdfium.lib.FPDF_GetPageCount(doc))
        set("mTempPoint", Point())
        set("mCloseGuard", Class.forName("dalvik.system.CloseGuard").getMethod("get").invoke(null))
    }

    companion object {
        @JvmStatic @Implementation
        protected fun nativeOpenPageAndGetSize(doc: Long, index: Int, outSize: Point): Long {
            val page = Pdfium.lib.FPDF_LoadPage(Pointer(doc), index) ?: throw IllegalStateException("cannot load page")
            outSize.x = Pdfium.lib.FPDF_GetPageWidthF(page).toInt()
            outSize.y = Pdfium.lib.FPDF_GetPageHeightF(page).toInt()
            return Pointer.nativeValue(page)
        }

        @JvmStatic @Implementation
        protected fun nativeClosePage(page: Long) = Pdfium.lib.FPDF_ClosePage(Pointer(page))

        @JvmStatic @Implementation
        protected fun nativeClose(doc: Long) {
            Pdfium.lib.FPDF_CloseDocument(Pointer(doc))
            Pdfium.buffers.remove(doc)
        }
    }
}

@Implements(PdfRenderer.Page::class)
class ShadowPdfiumPage {
    @RealObject lateinit var real: PdfRenderer.Page

    /** Mirrors PdfRenderer.Page.render + nativeRenderPage (android14-release). */
    @Implementation
    fun render(destination: Bitmap, destClip: Rect?, transformIn: Matrix?, renderMode: Int) {
        require(destination.config == Bitmap.Config.ARGB_8888) { "Unsupported pixel format" }
        if (destClip != null) require(destClip.left >= 0 && destClip.top >= 0 && destClip.right <= destination.width && destClip.bottom <= destination.height) { "destBounds not in destination" }
        require(transformIn == null || transformIn.isAffine) { "transform not affine" }
        require(renderMode == PdfRenderer.Page.RENDER_MODE_FOR_PRINT || renderMode == PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) { "Unsupported render mode" }
        val l = destClip?.left ?: 0; val t = destClip?.top ?: 0
        val r = destClip?.right ?: destination.width; val b = destClip?.bottom ?: destination.height
        val transform = transformIn ?: Matrix().apply {
            postScale((r - l).toFloat() / real.width, (b - t).toFloat() / real.height); postTranslate(l.toFloat(), t.toFloat())
        }
        val v = FloatArray(9).also { transform.getValues(it) }
        val page = Pointer(PdfRenderer.Page::class.java.getDeclaredField("mNativePage").apply { isAccessible = true }.getLong(real))

        val w = destination.width; val h = destination.height
        val px = IntArray(w * h).also { destination.getPixels(it, 0, w, 0, 0, w, h) }
        val buf = Memory(w * h * 4L).apply { write(0, px, 0, px.size) } // little-endian ARGB ints == BGRA bytes
        val bmp = Pdfium.lib.FPDFBitmap_CreateEx(w, h, 4 /* FPDFBitmap_BGRA */, buf, w * 4)!!
        val fsMatrix = Memory(24).apply {
            // FS_MATRIX {a,b,c,d,e,f} = {scaleX, skewY, skewX, scaleY, transX, transY}
            setFloat(0, v[Matrix.MSCALE_X]); setFloat(4, v[Matrix.MSKEW_Y]); setFloat(8, v[Matrix.MSKEW_X])
            setFloat(12, v[Matrix.MSCALE_Y]); setFloat(16, v[Matrix.MTRANS_X]); setFloat(20, v[Matrix.MTRANS_Y])
        }
        val clip = Memory(16).apply { setFloat(0, l.toFloat()); setFloat(4, t.toFloat()); setFloat(8, r.toFloat()); setFloat(12, b.toFloat()) }
        val flags = if (renderMode == PdfRenderer.Page.RENDER_MODE_FOR_PRINT) 0x800 else 0x02
        Pdfium.lib.FPDF_RenderPageBitmapWithMatrix(bmp, page, fsMatrix, clip, flags)
        Pdfium.lib.FPDFBitmap_Destroy(bmp)
        buf.read(0, px, 0, px.size)
        destination.setPixels(px, 0, w, 0, 0, w, h)
    }
}
