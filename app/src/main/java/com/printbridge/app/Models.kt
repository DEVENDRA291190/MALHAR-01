package com.printbridge.app

const val MAX_WIDTH_MM = 104
const val DOTS_PER_MM = 8 // 203 dpi

enum class Transport { BLUETOOTH, USB }

enum class PaperType { RECEIPT, LABEL_GAP, LABEL_MARK }

enum class Orientation { PORTRAIT, LANDSCAPE, AUTO }

data class PrinterEntry(
    val transport: Transport,
    val name: String,
    val address: String,
    val detail: String,
)

data class PrintSettings(
    val transport: Transport = Transport.BLUETOOTH,
    val printerName: String = "",
    val printerAddress: String = "",
    val paper: PaperType = PaperType.RECEIPT,
    val widthMm: Int = 100,
    val pageMm: Int = 210,
    val heightMm: Int = 100,
    val gapMm: Int = 3,
    val markMm: Int = 3,
    val trimBlank: Boolean = true,
    val darkness: Int = 1,
    val speed: Int = 6,
    val threshold: Int = 160,
    val dither: Boolean = false,
    val orientation: Orientation = Orientation.LANDSCAPE,
    val rotate180: Boolean = false,
    val invert: Boolean = false,
    val offsetXDots: Int = 0,
) {
    /** Width actually sent to the printer (the head is 104 mm wide). */
    val effectiveWidthMm: Int get() = widthMm.coerceIn(8, MAX_WIDTH_MM)
}

class PrintException(message: String) : Exception(message)
