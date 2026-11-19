package com.printbridge.app

import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max

/** Builds TSPL2 command streams for 203 dpi label / receipt printers. */
object TsplBuilder {

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    /** SIZE + GAP/BLINE lines for the chosen paper type. */
    private fun header(s: PrintSettings, heightMm: Int): String = buildString {
        append("SIZE ${s.effectiveWidthMm} mm,$heightMm mm\r\n")
        when (s.paper) {
            PaperType.RECEIPT -> append("GAP 0 mm,0 mm\r\n")
            PaperType.LABEL_GAP -> append("GAP ${s.gapMm} mm,0 mm\r\n")
            PaperType.LABEL_MARK -> append("BLINE ${s.markMm} mm,0 mm\r\n")
        }
    }

    private fun quality(s: PrintSettings): String =
        "DENSITY ${s.darkness.coerceIn(0, 15)}\r\nSPEED ${s.speed.coerceIn(1, 6)}\r\nDIRECTION 1\r\n"

    private fun heightMmFor(s: PrintSettings, contentDots: Int): Int =
        if (s.paper == PaperType.RECEIPT) {
            max(1, ceil(contentDots / DOTS_PER_MM.toDouble()).toInt())
        } else {
            s.heightMm.coerceAtLeast(5)
        }

    /** Measures the gap / black mark and stores it in the printer. */
    fun calibrate(s: PrintSettings): ByteArray =
        ascii(header(s, s.heightMm.coerceAtLeast(5)) + "AUTODETECT\r\n")

    fun testPrint(s: PrintSettings): ByteArray {
        val h = if (s.paper == PaperType.RECEIPT) 25 else s.heightMm.coerceAtLeast(5)
        val text = header(s, h) + quality(s) +
            "CLS\r\n" +
            "TEXT 24,24,\"3\",0,1,1,\"PrintBridge\"\r\n" +
            "BARCODE 24,104,\"128\",80,1,0,2,2,\"PRINTBRIDGE-OK\"\r\n" +
            "PRINT 1,1\r\n"
        return ascii(text)
    }

    /**
     * Packs [black] (row-major, true = print a dot) into TSPL bitmap bytes.
     * In TSPL a cleared bit prints a dot, so "invert" simply flips that rule.
     * [offsetX] shifts the picture right (positive) or left (negative) by dots.
     */
    fun pack(black: BooleanArray, w: Int, h: Int, offsetX: Int, invert: Boolean): ByteArray {
        val wb = (w + 7) / 8
        val out = ByteArray(wb * h) { 0xFF.toByte() }
        for (y in 0 until h) {
            val row = y * w
            val outRow = y * wb
            for (x in 0 until w) {
                val dx = x + offsetX
                if (dx < 0 || dx >= wb * 8) continue
                val ink = black[row + x] != invert
                if (ink) {
                    val idx = outRow + (dx shr 3)
                    val mask = (0x80 shr (dx and 7)).inv()
                    out[idx] = (out[idx].toInt() and mask).toByte()
                }
            }
        }
        return out
    }

    fun bitmapJob(s: PrintSettings, bits: ByteArray, widthBytes: Int, heightDots: Int): ByteArray {
        val head = header(s, heightMmFor(s, heightDots)) + quality(s) +
            "CLS\r\n" +
            "BITMAP 0,0,$widthBytes,$heightDots,0,"
        val bos = ByteArrayOutputStream(bits.size + 256)
        bos.write(ascii(head))
        bos.write(bits)
        bos.write(ascii("\r\nPRINT 1,1\r\n"))
        return bos.toByteArray()
    }
}
