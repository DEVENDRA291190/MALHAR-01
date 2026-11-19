package com.printbridge.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Image helpers: grey -> black/white, trimming. */
object Raster {

    private fun luminance(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }

    /** Returns row-major array, true = dot to print. */
    fun toBlack(bmp: Bitmap, threshold: Int, dither: Boolean): BooleanArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = BooleanArray(w * h)
        if (!dither) {
            for (i in px.indices) out[i] = luminance(px[i]) < threshold
            return out
        }
        // Floyd-Steinberg error diffusion
        val g = FloatArray(w * h) { luminance(px[it]).toFloat() }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = g[i]
                val nw = if (old < 128f) 0f else 255f
                out[i] = nw == 0f
                val err = old - nw
                if (x + 1 < w) g[i + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x > 0) g[i + w - 1] += err * 3f / 16f
                    g[i + w] += err * 5f / 16f
                    if (x + 1 < w) g[i + w + 1] += err / 16f
                }
            }
        }
        return out
    }

    private fun rowHasInk(black: BooleanArray, w: Int, y: Int): Boolean {
        val start = y * w
        for (x in 0 until w) if (black[start + x]) return true
        return false
    }

    /** Cuts blank rows above and below the content, keeping a small margin. */
    fun trimRows(black: BooleanArray, w: Int, h: Int, margin: Int = 8): Pair<BooleanArray, Int> {
        var top = 0
        while (top < h && !rowHasInk(black, w, top)) top++
        if (top == h) return Pair(BooleanArray(w * margin * 2), margin * 2) // blank page
        var bottom = h - 1
        while (bottom > top && !rowHasInk(black, w, bottom)) bottom--
        val from = max(0, top - margin)
        val to = min(h - 1, bottom + margin)
        return Pair(black.copyOfRange(from * w, (to + 1) * w), to - from + 1)
    }
}

object PrintEngine {

    private const val MAX_RECEIPT_DOTS = 8000

    fun openConnection(context: Context, s: PrintSettings): PrinterConnection {
        if (s.printerAddress.isBlank()) {
            throw PrintException(context.getString(R.string.select_printer_first))
        }
        return when (s.transport) {
            Transport.BLUETOOTH -> BluetoothPrinterConnection(context, s.printerAddress)
            Transport.USB -> {
                val usb = context.getSystemService(UsbManager::class.java)
                val device = usb?.deviceList?.get(s.printerAddress)
                    ?: throw PrintException(context.getString(R.string.printer_not_connected))
                UsbPrinterConnection(context, device)
            }
        }
    }

    /** Sends raw TSPL bytes (test print, calibrate). */
    suspend fun send(context: Context, s: PrintSettings, data: ByteArray) {
        withContext(Dispatchers.IO) {
            val conn = openConnection(context, s)
            try {
                conn.open()
                conn.write(data)
            } finally {
                conn.close()
            }
        }
    }

    /** Renders every page of [file] and prints it. */
    suspend fun printPdf(
        context: Context,
        file: File,
        s: PrintSettings,
        progress: (String) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            if (s.printerAddress.isBlank()) {
                throw PrintException(context.getString(R.string.select_printer_first))
            }
            progress(context.getString(R.string.reading_pdf))
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                pfd.close()
                throw PrintException(context.getString(R.string.pdf_password))
            } catch (e: IOException) {
                pfd.close()
                throw PrintException(context.getString(R.string.not_pdf))
            }
            try {
                val pages = renderer.pageCount
                if (pages == 0) throw PrintException(context.getString(R.string.pdf_no_pages))
                val conn = openConnection(context, s)
                try {
                    conn.open()
                    for (i in 0 until pages) {
                        progress(context.getString(R.string.printing_page_fmt, i + 1, pages))
                        val page = renderer.openPage(i)
                        val job = try {
                            buildJob(page, s)
                        } finally {
                            page.close()
                        }
                        conn.write(job)
                    }
                    progress(context.getString(R.string.sending))
                } finally {
                    conn.close()
                }
            } finally {
                renderer.close()
            }
        }
    }

    private fun shouldRotate(s: PrintSettings, pageW: Float, pageH: Float): Boolean =
        when (s.orientation) {
            Orientation.PORTRAIT -> false
            Orientation.LANDSCAPE -> true
            Orientation.AUTO -> {
                val labelLandscape = if (s.paper == PaperType.RECEIPT) {
                    s.pageMm < s.effectiveWidthMm
                } else {
                    s.heightMm < s.effectiveWidthMm
                }
                val pageLandscape = pageW > pageH
                pageLandscape != labelLandscape
            }
        }

    private fun buildJob(page: PdfRenderer.Page, s: PrintSettings): ByteArray {
        val widthDots = s.effectiveWidthMm * DOTS_PER_MM
        val pw = page.width.toFloat()
        val ph = page.height.toFloat()
        val rotate = shouldRotate(s, pw, ph)
        val rw = if (rotate) ph else pw
        val rh = if (rotate) pw else ph

        var scale = widthDots / rw
        val maxH = if (s.paper == PaperType.RECEIPT) {
            MAX_RECEIPT_DOTS
        } else {
            s.heightMm.coerceAtLeast(5) * DOTS_PER_MM
        }
        if (rh * scale > maxH) scale = maxH / rh

        val ow = max(1, (pw * scale).roundToInt())
        val oh = max(1, (ph * scale).roundToInt())
        val raw = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        raw.eraseColor(Color.WHITE)
        page.render(raw, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

        val oriented = if (rotate) {
            val m = Matrix()
            m.postRotate(90f)
            val r = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            raw.recycle()
            r
        } else {
            raw
        }

        // Centre the page on a canvas exactly as wide as the roll.
        val canvasBmp = Bitmap.createBitmap(widthDots, oriented.height, Bitmap.Config.ARGB_8888)
        canvasBmp.eraseColor(Color.WHITE)
        Canvas(canvasBmp).drawBitmap(oriented, (widthDots - oriented.width) / 2f, 0f, null)
        oriented.recycle()

        var h = canvasBmp.height
        var black = Raster.toBlack(canvasBmp, s.threshold, s.dither)
        canvasBmp.recycle()

        if (s.paper == PaperType.RECEIPT && s.trimBlank) {
            val trimmed = Raster.trimRows(black, widthDots, h)
            black = trimmed.first
            h = trimmed.second
        }
        if (s.rotate180) black.reverse()

        val bits = TsplBuilder.pack(black, widthDots, h, s.offsetXDots, s.invert)
        return TsplBuilder.bitmapJob(s, bits, widthDots / 8, h)
    }
}
