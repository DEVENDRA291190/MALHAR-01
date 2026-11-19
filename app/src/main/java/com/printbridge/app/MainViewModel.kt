package com.printbridge.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val settings: PrintSettings = PrintSettings(),
    val printers: List<PrinterEntry> = emptyList(),
    val status: String? = null,
    val busy: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication<Application>()
    private val store = SettingsStore(app)

    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                showStatus(
                    ctx.getString(
                        if (granted) R.string.usb_permission_granted else R.string.usb_permission_denied
                    )
                )
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            ctx,
            usbReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCleared() {
        try {
            ctx.unregisterReceiver(usbReceiver)
        } catch (ignored: IllegalArgumentException) {
        }
        super.onCleared()
    }

    // ---- settings -------------------------------------------------------

    fun update(transform: PrintSettings.() -> PrintSettings) {
        val s = _state.value.settings.transform()
        store.save(s)
        _state.update { it.copy(settings = s) }
    }

    fun showStatus(message: String?) {
        _state.update { it.copy(status = message) }
    }

    // ---- printers -------------------------------------------------------

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun isPrinter(d: UsbDevice): Boolean {
        if (d.deviceClass == UsbConstants.USB_CLASS_PRINTER) return true
        for (i in 0 until d.interfaceCount) {
            if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun bondedPrinters(): List<PrinterEntry> {
        if (!hasBluetoothPermission()) return emptyList()
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.map {
                PrinterEntry(Transport.BLUETOOTH, it.name ?: it.address, it.address, it.address)
            }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private fun usbPrinters(): List<PrinterEntry> {
        val usb = ctx.getSystemService(UsbManager::class.java) ?: return emptyList()
        return usb.deviceList.values.filter { isPrinter(it) }.map {
            PrinterEntry(
                Transport.USB,
                it.productName ?: ctx.getString(R.string.usb_printer),
                it.deviceName,
                String.format("USB %04X:%04X", it.vendorId, it.productId),
            )
        }
    }

    fun refresh() {
        val list = (usbPrinters() + bondedPrinters()).toMutableList()
        val s = _state.value.settings
        // Keep the saved choice visible even if it is currently not reachable.
        if (s.printerAddress.isNotBlank() && list.none { it.address == s.printerAddress }) {
            list.add(0, PrinterEntry(s.transport, s.printerName.ifBlank { s.printerAddress }, s.printerAddress, s.printerAddress))
        }
        _state.update {
            it.copy(
                printers = list,
                status = if (list.isEmpty()) ctx.getString(R.string.no_printers) else it.status,
            )
        }
    }

    fun select(p: PrinterEntry) {
        update { copy(transport = p.transport, printerName = p.name, printerAddress = p.address) }
        if (p.transport == Transport.USB) requestUsbPermission(p.address)
    }

    private fun requestUsbPermission(deviceName: String) {
        val usb = ctx.getSystemService(UsbManager::class.java) ?: return
        val device = usb.deviceList[deviceName] ?: return
        if (usb.hasPermission(device)) return
        val pi = PendingIntent.getBroadcast(
            ctx,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        usb.requestPermission(device, pi)
    }

    // ---- actions --------------------------------------------------------

    fun testPrint() {
        val s = _state.value.settings
        runJob(s) { PrintEngine.send(ctx, s, TsplBuilder.testPrint(s)) }
    }

    fun calibrate() {
        val s = _state.value.settings
        if (s.paper == PaperType.RECEIPT) {
            showStatus(ctx.getString(R.string.no_calibration))
            return
        }
        runJob(s) { PrintEngine.send(ctx, s, TsplBuilder.calibrate(s)) }
    }

    private fun runJob(s: PrintSettings, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, status = ctx.getString(R.string.sending)) }
        viewModelScope.launch {
            val message = try {
                block()
                ctx.getString(R.string.sent_to, s.printerName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.getString(R.string.error_fmt, e.message ?: e.javaClass.simpleName)
            }
            _state.update { it.copy(busy = false, status = message) }
        }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.printbridge.app.USB_PERMISSION"
    }
}
