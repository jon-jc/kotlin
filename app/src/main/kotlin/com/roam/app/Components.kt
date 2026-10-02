package com.roam.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.roam.core.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun imageResource(image: String): Any =
    when (image) {
        "alpine" -> R.drawable.alpine
        "coast" -> R.drawable.coast
        "kyoto" -> R.drawable.kyoto
        else ->
            if (
                runCatching {
                        val uri = java.net.URI(image)
                        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
                    }
                    .getOrDefault(false)
            )
                image
            else R.drawable.ic_roam
    }

fun LocalDate.pretty(): String = format(DateTimeFormatter.ofPattern("MMM d, yyyy"))

fun initials(name: String): String =
    name
        .trim()
        .split(Regex("\\s+"))
        .take(2)
        .mapNotNull { it.firstOrNull() }
        .joinToString("")
        .uppercase()

@Composable
fun Eyebrow(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
fun PageHeading(overline: String, title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(overline)
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SectionHeading(title: String, accessory: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        accessory?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Button(
        onClick,
        modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
            if (busy) {
                contentDescription = text
                stateDescription = "In progress"
            }
        },
        enabled = enabled && !busy,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        if (busy)
            CircularProgressIndicator(
                Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onSurface,
                strokeWidth = 2.dp,
            )
        else {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(19.dp))
        }
    }
}

@Composable
fun RoundButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    FilledIconButton(
        onClick,
        modifier.size(48.dp),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                contentColor =
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Icon(icon, description, Modifier.size(21.dp))
    }
}

@Composable
fun ErrorMessage(message: String?) {
    message?.let {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    action: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(
                icon,
                null,
                Modifier.padding(24.dp).size(34.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        TextButton(onClick, Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

@Composable
fun PriceRow(label: String, amount: Money, emphasized: Boolean = false, credit: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style =
                if (emphasized) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (credit && amount.minor > 0) "−${amount.formatted()}" else amount.formatted(),
            style =
                if (emphasized) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.bodyMedium,
            color =
                if (credit) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun Counter(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                if (label == "Guests") "Up to $max guests" else "$min–$max nights",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedIconButton(
            { onChange(value - 1) },
            enabled = value > min,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Outlined.Remove, "Decrease ${label.lowercase()}")
        }
        Text(
            "$value",
            Modifier.widthIn(min = 36.dp).semantics {
                contentDescription = "$value ${label.lowercase()}"
            },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedIconButton(
            { onChange(value + 1) },
            enabled = value < max,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Outlined.Add, "Increase ${label.lowercase()}")
        }
    }
}

@Composable
fun PassportArtwork(modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val color = Color(0xFFDBDABD).copy(alpha = .28f)
        val r = size.minDimension * .43f
        val center = Offset(size.width * .58f, size.height * .48f)
        drawCircle(color, r, center, style = Stroke(1.3.dp.toPx()))
        drawOval(
            color,
            topLeft = center - Offset(r * .45f, r),
            size = androidx.compose.ui.geometry.Size(r * .9f, r * 2),
            style = Stroke(1.dp.toPx()),
        )
        drawOval(
            color,
            topLeft = center - Offset(r, r * .35f),
            size = androidx.compose.ui.geometry.Size(r * 2, r * .7f),
            style = Stroke(1.dp.toPx()),
        )
        drawLine(color, center - Offset(r, 0f), center + Offset(r, 0f), 1.dp.toPx())
        drawLine(color, center - Offset(0f, r), center + Offset(0f, r), 1.dp.toPx())
        drawCircle(color, r * 1.2f, center, style = Stroke(.5.dp.toPx()))
    }
}

@Composable
fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
