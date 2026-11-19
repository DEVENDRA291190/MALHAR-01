package com.printbridge.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

interface PrinterConnection {
    suspend fun open()
    suspend fun write(data: ByteArray)
    fun close()
}

class BluetoothPrinterConnection(
    private val context: Context,
    private val address: String,
) : PrinterConnection {

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null

    @SuppressLint("MissingPermission")
    override suspend fun open() {
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                throw IOException(context.getString(R.string.bt_permission_denied))
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException(context.getString(R.string.printer_not_connected))
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                throw IOException(context.getString(R.string.printer_not_connected))
            }
            adapter.cancelDiscovery()

            var s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                s.connect()
            } catch (e: IOException) {
                try {
                    s.close()
                } catch (ignored: IOException) {
                }
                s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                try {
                    s.connect()
                } catch (e2: IOException) {
                    throw IOException(context.getString(R.string.printer_not_connected))
                }
            }
            socket = s
            out = s.outputStream
        }
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val o = out ?: throw IOException(context.getString(R.string.printer_not_connected))
            var off = 0
            while (off < data.size) {
                val n = minOf(CHUNK, data.size - off)
                o.write(data, off, n)
                o.flush()
                off += n
            }
        }
    }

    override fun close() {
        try {
            out?.flush()
            // Give the printer time to drain its buffer before the link drops.
            Thread.sleep(500)
        } catch (ignored: Exception) {
        }
        try {
            socket?.close()
        } catch (ignored: IOException) {
        }
        socket = null
        out = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CHUNK = 2048
    }
}

class UsbPrinterConnection(
    private val context: Context,
    private val device: UsbDevice,
) : PrinterConnection {

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpoint: UsbEndpoint? = null

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(UsbManager::class.java)
                ?: throw IOException(context.getString(R.string.printer_not_connected))
            if (!manager.hasPermission(device)) {
                throw IOException(context.getString(R.string.usb_not_granted))
            }

            var foundIf: UsbInterface? = null
            var foundEp: UsbEndpoint? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT
                    ) {
                        val isPrinter = intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER
                        if (foundEp == null || isPrinter) {
                            foundIf = intf
                            foundEp = ep
                        }
                    }
                }
            }
            if (foundIf == null || foundEp == null) {
                throw IOException(context.getString(R.string.printer_not_connected))
            }
            val conn = manager.openDevice(device)
                ?: throw IOException(context.getString(R.string.printer_not_connected))
            if (!conn.claimInterface(foundIf, true)) {
                conn.close()
                throw IOException(context.getString(R.string.usb_claim_failed))
            }
            connection = conn
            usbInterface = foundIf
            endpoint = foundEp
        }
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val conn = connection ?: throw IOException(context.getString(R.string.printer_not_connected))
            val ep = endpoint ?: throw IOException(context.getString(R.string.printer_not_connected))
            var off = 0
            while (off < data.size) {
                val n = minOf(CHUNK, data.size - off)
                val r = conn.bulkTransfer(ep, data, off, n, TIMEOUT_MS)
                if (r < 0) throw IOException(context.getString(R.string.usb_write_failed))
                off += r
            }
        }
    }

    override fun close() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
        } catch (ignored: Exception) {
        }
        connection?.close()
        connection = null
    }

    private companion object {
        const val CHUNK = 16384
        const val TIMEOUT_MS = 5000
    }
}
