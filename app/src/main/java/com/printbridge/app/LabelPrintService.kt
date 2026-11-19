package com.printbridge.app

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** Makes the printer appear in Android's system print dialog (Chrome, Drive, ...). */
class LabelPrintService : PrintService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return object : PrinterDiscoverySession() {
            override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
                addPrinters(listOf(buildPrinter()))
            }

            override fun onStopPrinterDiscovery() {}

            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
                addPrinters(listOf(buildPrinter()))
            }

            override fun onStartPrinterStateTracking(printerId: PrinterId) {
                addPrinters(listOf(buildPrinter()))
            }

            override fun onStopPrinterStateTracking(printerId: PrinterId) {}

            override fun onDestroy() {}
        }
    }

    private fun buildPrinter(): PrinterInfo {
        val s = SettingsStore(this).load()
        val id = generatePrinterId("printbridge")

        val widthMm = s.effectiveWidthMm
        val heightMm = (if (s.paper == PaperType.RECEIPT) s.pageMm else s.heightMm).coerceAtLeast(10)
        val wMils = (widthMm / 25.4 * 1000).roundToInt()
        val hMils = (heightMm / 25.4 * 1000).roundToInt()

        val caps = PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(
                PrintAttributes.MediaSize("printbridge_roll", "$widthMm x $heightMm mm", wMils, hMils),
                true,
            )
            .addResolution(PrintAttributes.Resolution("r203", "203 dpi", 203, 203), true)
            .setColorModes(
                PrintAttributes.COLOR_MODE_MONOCHROME,
                PrintAttributes.COLOR_MODE_MONOCHROME,
            )
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        val name = if (s.printerName.isBlank()) {
            getString(R.string.app_name)
        } else {
            getString(R.string.app_name) + " - " + s.printerName
        }
        return PrinterInfo.Builder(id, name, PrinterInfo.STATUS_IDLE)
            .setCapabilities(caps)
            .build()
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        printJob.start()
        val pfd: ParcelFileDescriptor? = printJob.document.data
        if (pfd == null) {
            printJob.fail(getString(R.string.no_pdf))
            return
        }
        val settings = SettingsStore(this).load()
        scope.launch {
            val error: String? = try {
                val f = File(cacheDir, "svc-${System.nanoTime()}.pdf")
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                    PrintEngine.printPdf(this@LabelPrintService, f, settings) { }
                } finally {
                    f.delete()
                }
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            mainHandler.post {
                if (error == null) printJob.complete() else printJob.fail(error)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
