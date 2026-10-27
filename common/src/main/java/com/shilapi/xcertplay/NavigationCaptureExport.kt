package com.shilapi.xcertplay

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.navigation.NavigationCapture
import java.io.File
import java.io.IOException

/** Streaming ZIP export; no conversion to the size-limited general diagnostic report. */
internal object NavigationCaptureExport {
    fun save(context: Context): DiagnosticExportStore.SavedReport {
        val directory = File(context.filesDir, "diagnostic-reports/navigation").apply {
            check(isDirectory || mkdirs()) { "Cannot create export folder" }
        }
        val file = File.createTempFile("DiPlay-navigation-${System.currentTimeMillis()}-", ".zip", directory)
        try { file.outputStream().use { NavigationCapture.export(context, it) } }
        catch (error: Exception) { file.delete(); throw error }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            var uri: android.net.Uri? = null
            try {
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/DiPlay")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }) ?: throw IOException("Downloads unavailable")
                val destination = uri
                (resolver.openOutputStream(destination, "wt") ?: throw IOException("Cannot open Downloads")).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                check(resolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
                file.delete()
                return DiagnosticExportStore.SavedReport(destination, savedPath = "Download/DiPlay/${file.name}")
            } catch (_: Exception) { uri?.let { runCatching { resolver.delete(it, null, null) } } }
        }
        if (DiagnosticExportStore.canWritePublicDownloads(context)) {
            runCatching {
                val folder = DiagnosticExportStore.publicDownloadsDirectory().apply { check(isDirectory || mkdirs()) }
                val destination = File(folder, file.name)
                try { file.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } } }
                catch (error: Exception) { destination.delete(); throw error }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", destination)
                file.delete()
                return DiagnosticExportStore.SavedReport(uri, savedPath = destination.absolutePath)
            }
        }
        return DiagnosticExportStore.SavedReport(
            FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file), savedInApp = true)
    }
}
