package com.example.epsonprintapp

/**
 * AppConstants — Todas las constantes globales de la aplicación.
 *
 * Centralizar aquí evita números mágicos dispersos por el código.
 */
object AppConstants {

    // ── Red ───────────────────────────────────────────────────────────────────
    const val SOCKET_TIMEOUT_MS       = 400      // timeout por host en escaneo TCP paralelo
    const val HTTP_CONNECT_TIMEOUT_S  = 15L
    const val HTTP_READ_TIMEOUT_S     = 120L
    const val HTTP_WRITE_TIMEOUT_S    = 120L
    const val DISCOVERY_TIMEOUT_MS    = 30_000L
    const val SCAN_POLL_INTERVAL_MS   = 2_000L
    const val MAX_SCAN_POLL_ATTEMPTS  = 30

    // ── Impresión ─────────────────────────────────────────────────────────────
    const val PDF_RASTER_DPI          = 200      // DPI para rasterizar PDF a JPEG
    const val JPEG_QUALITY            = 90       // calidad JPEG al rasterizar páginas
    const val MAX_FILE_SIZE_MB        = 50L      // límite de archivo a imprimir
    const val MAX_FILE_SIZE_BYTES     = MAX_FILE_SIZE_MB * 1024 * 1024

    // ── Escaneo ───────────────────────────────────────────────────────────────
    const val SCAN_THUMBNAIL_WIDTH_PX = 300      // ancho de miniaturas en RAM
    const val SCAN_JPEG_QUALITY       = 92       // calidad JPEG temporal en disco
    const val SCAN_PDF_DPI            = 150      // DPI para páginas del PDF final
    const val SCAN_TEMP_DIR           = "scans_temp"
    const val SCAN_TEMP_MAX_AGE_H     = 24L     // horas antes de limpiar temporales

    // ── Tinta ─────────────────────────────────────────────────────────────────
    const val INK_LOW_THRESHOLD       = 20       // % para alerta de tinta baja
    const val INK_WARNING_THRESHOLD   = 50       // % para aviso

    // ── Base de datos ─────────────────────────────────────────────────────────
    const val DB_OLD_JOBS_DAYS        = 30L      // días para limpiar historial
    const val DB_NAME                 = "epsonprint.db"

    // ── UI ────────────────────────────────────────────────────────────────────
    const val PRINTER_REFRESH_INTERVAL_MS = 30_000L
    const val HAPTIC_DURATION_MS          = 200L
}