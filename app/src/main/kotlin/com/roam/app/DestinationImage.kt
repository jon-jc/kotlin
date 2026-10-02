package com.roam.app

import androidx.activity.compose.ReportDrawnWhen
import androidx.annotation.DrawableRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/** Constraint-sized decoding off the UI thread, backed by Coil's shared memory cache. */
@Composable
fun DestinationImage(
    @DrawableRes resource: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var settled by remember(resource) { mutableStateOf(false) }
    // Startup includes the visible photos, not just an empty image placeholder.
    ReportDrawnWhen { settled }
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    AsyncImage(
        model = resource,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = placeholder,
        error = placeholder,
        onSuccess = { settled = true },
        onError = { settled = true },
    )
}
