package com.roam.app

import android.content.Context
import android.content.Intent as AndroidIntent
import androidx.core.content.FileProvider
import com.roam.core.Booking
import com.roam.core.ReceiptCodec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Restrict sharing to a single receipt URI; no account database or private profile fields. */
suspend fun exportReceipt(context: Context, booking: Booking) {
    val uri =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "receipts").apply { mkdirs() }
            val safeId = booking.id.filter { it.isLetterOrDigit() || it == '-' }.take(100)
            val file = File.createTempFile("roam-$safeId-", ".json", directory)
            file.writeText(ReceiptCodec.encode(booking))
            FileProvider.getUriForFile(context, "${context.packageName}.receipts", file)
        }
    val send =
        AndroidIntent(AndroidIntent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(AndroidIntent.EXTRA_STREAM, uri)
            putExtra(AndroidIntent.EXTRA_SUBJECT, "Roam demo receipt")
            addFlags(AndroidIntent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(AndroidIntent.createChooser(send, "Export demo receipt"))
}
