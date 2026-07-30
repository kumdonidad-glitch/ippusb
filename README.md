# IPPUSB

This repository contains an initial Kotlin Android scaffold to build an AirPrint-style server on Android that can accept IPP print jobs and forward them to a USB-connected printer (Canon G2730 target for testing).

What is included
- Minimal Android Studio project (app module)
- Foreground Service (PrinterService) that starts a NanoHTTPD HTTP server to accept POST jobs and a JmDNS registrar to advertise an _ipp._tcp service
- USB backend stub that looks for USB Printer class devices and attempts to send raw bytes via bulkTransfer

Notes & next steps
- This is an MVP scaffold. The IPP handling is currently a very small stub that saves the POST body and forwards raw bytes to the USB printer. Proper IPP binary parsing (RFC 8010/8011) and IPP response generation should be implemented for compatibility.
- Many printers expect page description languages (PCL/PS) or raster formats. If your Canon G2730 accepts raw PDF over USB, this stub may work; otherwise you will need to implement PDF → printer-language conversion. Consider using Android's PDFRenderer to rasterize pages to bitmaps and then implement PWG raster encoding.
- USB permission: the app must request permission from the user to access USB devices. The current scaffold logs missing permission; update MainActivity to request permission via PendingIntent and handle the result.

How to open
1. Open this folder in Android Studio. Let it sync Gradle.
2. Build & run on a device supporting USB host (OTG). Minimum SDK is set to Android 12 (API 31).
3. Start the Printer Service from the app UI. The service will advertise an mDNS _ipp._tcp service and listen on port 6310.
4. From an AirPrint-capable client (iPhone on the same network), look for the advertised printer and send a simple PDF print job.

I can now:
- Improve IPP support (implement Get-Printer-Attributes, Print-Job parsing and proper IPP responses)
- Implement PDF→PWG Raster conversion and a more robust printing pipeline
- Add proper USB permission flow UI and persist paired devices
- Add tests and sample jobs

Tell me which of the above you'd like next and I will continue implementing it.
