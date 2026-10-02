@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roam.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.roam.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun StayDetail(state: RoamState, today: LocalDate, accept: (Intent) -> Unit) {
    val stay = Catalog.find(state.screen.selectedStay!!)
    var datePicker by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column {
                        Text(stay.nightly.formatted(), style = MaterialTheme.typography.titleLarge)
                        Text(
                            "per night · USD",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PrimaryButton(
                        "Make it your next",
                        { accept(Intent.Checkout) },
                        Modifier.weight(1f),
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    DestinationImage(
                        imageResource(stay.image),
                        "${stay.country} destination inspiration",
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    RoundButton(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        "Back to explore",
                        { accept(Intent.Back) },
                        Modifier.align(Alignment.TopStart).padding(16.dp),
                    )
                    val saved = stay.id in state.snapshot.account.saved
                    RoundButton(
                        if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        if (saved) "Unsave stay" else "Save stay",
                        { accept(Intent.SaveStay(stay.id)) },
                        Modifier.align(Alignment.TopEnd).padding(16.dp),
                        saved,
                    )
                    Surface(
                        Modifier.align(Alignment.BottomStart).padding(24.dp),
                        shape = RoundedCornerShape(50),
                        color = Cream,
                    ) {
                        Text(
                            "A PLACE TO BELONG",
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Forest,
                        )
                    }
                }
            }
            item {
                Column(
                    Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Eyebrow("${stay.category.uppercase()} / ${stay.country.uppercase()}")
                    Text(
                        stay.name,
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Star,
                            null,
                            Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "${stay.rating} · ${stay.reviews} sample reviews",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        stay.location,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                stay.host.take(1),
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Column {
                            Text(
                                "A warm welcome from ${stay.host}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Your local host · Up to ${stay.maxGuests} guests",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(stay.description, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                SurfaceCard(Modifier.padding(horizontal = 24.dp)) {
                    SectionHeading("Make room for a getaway")
                    SettingsRow(
                        Icons.Outlined.CalendarMonth,
                        "${state.screen.checkIn.pretty()} →",
                        "Check-out ${state.screen.checkIn.plusDays(state.screen.nights.toLong()).pretty()}",
                    ) {
                        datePicker = true
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Counter("Nights", state.screen.nights, 1, 28) { accept(Intent.Nights(it)) }
                    Counter("Guests", state.screen.guests, 1, stay.maxGuests) {
                        accept(Intent.Guests(it))
                    }
                }
            }
            item {
                Column(
                    Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SectionHeading("The little things, considered")
                    stay.amenities.forEach {
                        Text("✓  $it", style = MaterialTheme.typography.bodyLarge)
                    }
                    SurfaceCard {
                        Icon(
                            Icons.Outlined.VerifiedUser,
                            null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            "Plans change. That's okay.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Cancel before check-in for a full return of travel credits. This demo simulates the remaining card payment; no money moves.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Destination imagery is illustrative. Stays and reviews are fictional.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (datePicker) {
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    state.screen.checkIn.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                selectableDates =
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                            val day =
                                Instant.ofEpochMilli(utcTimeMillis)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                            return !day.isBefore(today) && !day.isAfter(today.plusYears(1))
                        }
                    },
            )
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton(
                    {
                        picker.selectedDateMillis?.let {
                            accept(
                                Intent.Dates(
                                    Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                                )
                            )
                        }
                        datePicker = false
                    },
                    enabled = picker.selectedDateMillis != null,
                ) {
                    Text("Set check-in")
                }
            },
            dismissButton = { TextButton({ datePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(picker)
        }
    }
}

@Composable
fun CheckoutScreen(state: RoamState, quote: Quote?, accept: (Intent) -> Unit) {
    val stay = Catalog.find(state.screen.selectedStay!!)
    LazyColumn(
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            RoundButton(
                Icons.AutoMirrored.Outlined.ArrowBack,
                "Back to stay",
                { accept(Intent.Back) },
            )
        }
        item {
            PageHeading(
                "ONE MORE GOOD DECISION",
                "Your next chapter\nstarts here.",
                "A clear price. A little peace of mind.",
            )
        }
        item {
            SurfaceCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DestinationImage(
                        imageResource(stay.image),
                        null,
                        Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Eyebrow(stay.country.uppercase())
                        Text(stay.name, style = MaterialTheme.typography.titleMedium)
                        Text(stay.location, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "${state.screen.checkIn.pretty()} – ${state.screen.checkIn.plusDays(state.screen.nights.toLong()).pretty()}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${state.screen.nights} nights · ${state.screen.guests} guests",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SurfaceCard {
                SectionHeading("Good things travel with you")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        Icons.Outlined.AccountBalanceWallet,
                        null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Use travel credit", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.snapshot.account.balance.formatted()} available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        state.screen.useCredit,
                        { accept(Intent.Credit(it)) },
                        enabled = !state.screen.busy,
                        modifier = Modifier.semantics { contentDescription = "Use travel credit" },
                    )
                }
                Text(
                    "Credits are applied before the sample card. Your final allocation is checked when you confirm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SurfaceCard {
                SectionHeading("Every detail, up front")
                if (quote != null) {
                    PriceRow("${stay.nightly.formatted()} × ${quote.nights} nights", quote.subtotal)
                    PriceRow("Service fee (8%)", quote.serviceFee)
                    PriceRow("Travel credit", quote.credit, credit = true)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PriceRow("Sample card total", quote.due, emphasized = true)
                } else
                    Text(
                        "These dates are no longer available. Go back to choose a new check-in date.",
                        color = MaterialTheme.colorScheme.error,
                    )
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.CreditCard, null, tint = MaterialTheme.colorScheme.secondary)
                Column {
                    Text("Roam test card · 4242", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Simulated payment. No real charge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { ErrorMessage(state.screen.error) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PrimaryButton(
                    if (state.screen.error?.contains("response was interrupted") == true)
                        "Recover my reservation"
                    else "Confirm demo reservation",
                    { accept(Intent.Reserve) },
                    enabled = quote != null,
                    busy = state.screen.busy,
                )
                Text(
                    "Free cancellation before check-in. This is a fictional stay using sample inventory and a simulated card payment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton({ accept(Intent.Demo(true)) }, Modifier.heightIn(min = 48.dp)) {
                    Text("Demo controls · ${state.screen.demoMode.name}")
                }
            }
        }
    }
}

@Composable
fun ReceiptScreen(
    booking: Booking?,
    today: LocalDate,
    busy: Boolean,
    error: String?,
    accept: (Intent) -> Unit,
) {
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (booking == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val stay = Catalog.find(booking.request.stayId)
    LazyColumn(
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { RoundButton(Icons.Outlined.Close, "Close receipt", { accept(Intent.Back) }) }
        item {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    if (booking.cancelled) Icons.AutoMirrored.Outlined.Undo
                    else Icons.Outlined.Check,
                    null,
                    Modifier.padding(18.dp).size(32.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        item {
            PageHeading(
                if (booking.cancelled) "PLANS CHANGE" else "IT'S IN YOUR PASSPORT",
                if (booking.cancelled) "Until the next\nadventure."
                else "You're going\nsomewhere good.",
                if (booking.cancelled) "Travel credits returned. Your original receipt is below."
                else "Your demo reservation is confirmed.",
            )
        }
        item {
            DestinationImage(
                imageResource(stay.image),
                null,
                Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        item {
            SurfaceCard {
                Text(stay.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${booking.request.checkIn.pretty()} – ${booking.request.checkOut.pretty()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${booking.request.guests} guests · ${booking.quote.nights} nights",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PriceRow("Stay + service fee", booking.quote.total)
                PriceRow(
                    "Credits ${if (booking.cancelled) "returned" else "used"}",
                    booking.quote.credit,
                )
                PriceRow(
                    "${if (booking.cancelled) "Voided" else "Simulated"} card payment",
                    booking.quote.due,
                )
                Eyebrow("CONFIRMATION · ${booking.id.take(8).uppercase()}")
                Text(
                    "Sample card · No money was charged",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { ErrorMessage(error) }
        item { ErrorMessage(exportError) }
        item {
            PrimaryButton("Back to my trips", { accept(Intent.Navigate(Destination.Trips)) })
            OutlinedButton(
                onClick = {
                    exporting = true
                    scope.launch {
                        try {
                            exportError = null
                            exportReceipt(context, booking)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            exportError = "The receipt couldn't be exported. Please try again."
                        } finally {
                            exporting = false
                        }
                    }
                },
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Outlined.IosShare, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Export receipt")
            }
            Text(
                "A portable JSON copy, without profile details.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!booking.cancelled && today.isBefore(booking.request.checkIn))
                TextButton(
                    { confirmCancel = true },
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !busy,
                ) {
                    Text("Cancel reservation")
                }
        }
    }
    if (confirmCancel)
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            icon = { Icon(Icons.Outlined.EventBusy, null) },
            title = { Text("Cancel this chapter?") },
            text = {
                Text(
                    "${booking.quote.credit.formatted()} in travel credits will be returned to your wallet. The sample card payment will be marked void."
                )
            },
            confirmButton = {
                TextButton({
                    confirmCancel = false
                    accept(Intent.Cancel(booking.request.key))
                }) {
                    Text("Yes, cancel stay")
                }
            },
            dismissButton = { TextButton({ confirmCancel = false }) { Text("Keep my stay") } },
        )
}
