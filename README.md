# PrintBridge (naya source)

Thermal / label printer (TSPL, 203 dpi) ke liye Android app. UI screenshot jaisa hai.

## Features
- Printer list: paired Bluetooth printers + USB (OTG) printers, Refresh, Bluetooth settings
- Paper type: Receipt roll / Labels (gap) / Labels (black mark)
- Roll size: Width mm, Page mm (labels me Height / Gap / Mark mm), Trim blank space
- Print quality: Darkness, Speed, Black threshold, Dithering
- Orientation: Portrait / Landscape / Auto, Rotate 180, Invert bits, Horizontal offset
- Actions: Test print, Calibrate, Print a PDF from phone, Android printing me enable karna
- Android print service (Chrome etc. ke print dialog me "PrintBridge" dikhta hai)
- PDF share / open-with se print

## Build
1. Android Studio (Koala ya naya) me `PrintBridge` folder open karo
2. Sync hone do (internet chahiye), phir Run ya Build > Build APK(s)
3. APK: `app/build/outputs/apk/debug/app-debug.apk`

Command line: Android Studio ke baad `gradle assembleDebug` (JDK 17 + Android SDK 34).
