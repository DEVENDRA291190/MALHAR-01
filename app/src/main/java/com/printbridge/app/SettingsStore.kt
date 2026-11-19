package com.printbridge.app

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("printbridge", Context.MODE_PRIVATE)

    fun load(): PrintSettings {
        val d = PrintSettings()
        return PrintSettings(
            transport = enumOr(prefs.getString("transport", null), d.transport),
            printerName = prefs.getString("printerName", d.printerName) ?: d.printerName,
            printerAddress = prefs.getString("printerAddress", d.printerAddress) ?: d.printerAddress,
            paper = enumOr(prefs.getString("paper", null), d.paper),
            widthMm = prefs.getInt("widthMm", d.widthMm),
            pageMm = prefs.getInt("pageMm", d.pageMm),
            heightMm = prefs.getInt("heightMm", d.heightMm),
            gapMm = prefs.getInt("gapMm", d.gapMm),
            markMm = prefs.getInt("markMm", d.markMm),
            trimBlank = prefs.getBoolean("trimBlank", d.trimBlank),
            darkness = prefs.getInt("darkness", d.darkness),
            speed = prefs.getInt("speed", d.speed),
            threshold = prefs.getInt("threshold", d.threshold),
            dither = prefs.getBoolean("dither", d.dither),
            orientation = enumOr(prefs.getString("orientation", null), d.orientation),
            rotate180 = prefs.getBoolean("rotate180", d.rotate180),
            invert = prefs.getBoolean("invert", d.invert),
            offsetXDots = prefs.getInt("offsetXDots", d.offsetXDots),
        )
    }

    fun save(s: PrintSettings) {
        prefs.edit()
            .putString("transport", s.transport.name)
            .putString("printerName", s.printerName)
            .putString("printerAddress", s.printerAddress)
            .putString("paper", s.paper.name)
            .putInt("widthMm", s.widthMm)
            .putInt("pageMm", s.pageMm)
            .putInt("heightMm", s.heightMm)
            .putInt("gapMm", s.gapMm)
            .putInt("markMm", s.markMm)
            .putBoolean("trimBlank", s.trimBlank)
            .putInt("darkness", s.darkness)
            .putInt("speed", s.speed)
            .putInt("threshold", s.threshold)
            .putBoolean("dither", s.dither)
            .putString("orientation", s.orientation.name)
            .putBoolean("rotate180", s.rotate180)
            .putBoolean("invert", s.invert)
            .putInt("offsetXDots", s.offsetXDots)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T {
        if (name == null) return default
        return try {
            enumValueOf<T>(name)
        } catch (e: IllegalArgumentException) {
            default
        }
    }
}
