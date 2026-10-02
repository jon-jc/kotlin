package com.roam.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roam.core.*
import java.time.Instant
import java.time.ZoneId

@Composable
fun WalletScreen(state: RoamState, accept: (Intent) -> Unit) {
    val entries =
        state.snapshot.ledger.filter {
            state.screen.ledgerFilter == "All" ||
                (state.screen.ledgerFilter == "Earned" && it.amount.minor > 0) ||
                (state.screen.ledgerFilter == "Spent" && it.amount.minor < 0)
        }
    LazyColumn(
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            PageHeading(
                "VALUE THAT GOES WITH YOU",
                "A little more\npossibility.",
                "Your travel credit, all in one place.",
            )
        }
        item {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFE7D7BE), Color(0xFFF5EADA))))
            ) {
                PassportArtwork(Modifier.align(Alignment.TopEnd).size(200.dp))
                Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("ROAM / WALLET", Forest)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Outlined.AccountBalanceWallet, null, tint = Forest)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        state.snapshot.account.balance.formatted(),
                        fontFamily = Serif,
                        fontSize = 46.sp,
                        color = Forest,
                        modifier =
                            Modifier.semantics {
                                contentDescription =
                                    "Available travel credit ${state.snapshot.account.balance.formatted()}"
                            },
                    )
                    Text(
                        "AVAILABLE TRAVEL CREDIT · USD",
                        style = MaterialTheme.typography.labelSmall,
                        color = Forest,
                    )
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = Forest.copy(alpha = .18f))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.snapshot.account.profile.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = Forest,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "DEMO ACCOUNT",
                            style = MaterialTheme.typography.labelSmall,
                            color = Forest,
                        )
                    }
                }
            }
        }
        item {
            SurfaceCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Outlined.CardGiftcard,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "A welcome worth keeping",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Your community starts with $25 in travel credit.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val claimed = "welcome" in state.snapshot.account.redeemed
                PrimaryButton(
                    if (claimed) "Welcome credit claimed" else "Claim $25 welcome credit",
                    { accept(Intent.Redeem) },
                    enabled = !claimed,
                    busy = state.screen.busy,
                )
                Text(
                    "One welcome benefit per demo account. Credits have no cash value.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { ErrorMessage(state.screen.error) }
        item { SectionHeading("A trail of good things", "ACTIVITY") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Earned", "Spent").forEach { label ->
                    FilterChip(
                        state.screen.ledgerFilter == label,
                        { accept(Intent.LedgerFilter(label)) },
                        label = { Text(label) },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        items(entries, key = { it.id }) { entry ->
            LedgerRow(
                entry.title,
                Instant.ofEpochMilli(entry.createdAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .pretty(),
                entry.amount,
                entry.bookingId
                    ?.let { id ->
                        state.snapshot.bookings.firstOrNull { it.id == id }?.request?.key
                    }
                    ?.let { key -> { accept(Intent.Receipt(key)) } },
            )
        }
        if (state.screen.ledgerFilter != "Spent")
            item {
                LedgerRow(
                    "Your opening travel credit",
                    "A little head start · Demo balance",
                    Money(8500),
                    null,
                )
            }
        if (entries.isEmpty() && state.screen.ledgerFilter == "Spent")
            item {
                EmptyState(
                    Icons.Outlined.AccountBalanceWallet,
                    "The best is still ahead",
                    "Your credit spending will appear here after your first reservation.",
                    "Find a stay",
                ) {
                    accept(Intent.Navigate(Destination.Explore))
                }
            }
        item {
            SurfaceCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.secondary)
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Yours, wherever you go", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Credits apply automatically at checkout when enabled. Every change is saved to your device with a receipt.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerRow(title: String, date: String, amount: Money, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (onClick != null)
                    Modifier.clickable(onClickLabel = "View receipt", onClick = onClick)
                else Modifier
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(
                if (amount.minor >= 0) Icons.Outlined.SouthWest else Icons.Outlined.NorthEast,
                null,
                Modifier.padding(14.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            (if (amount.minor > 0) "+" else "") + amount.formatted(),
            style = MaterialTheme.typography.labelLarge,
            color =
                if (amount.minor >= 0) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun TripsScreen(state: RoamState, accept: (Intent) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            PageHeading(
                "MOMENTS IN THE MAKING",
                "Your next\nchapters.",
                "Every reservation, right where it belongs.",
            )
        }
        if (state.snapshot.bookings.isEmpty()) {
            item {
                DestinationImage(
                    R.drawable.alpine,
                    "Mountains waiting to be explored",
                    Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop,
                )
                EmptyState(
                    Icons.Outlined.Luggage,
                    "A blank page. A whole world.",
                    "Your first stay is the start of something. Find a place that feels like you.",
                    "Explore stays",
                ) {
                    accept(Intent.Navigate(Destination.Explore))
                }
            }
        }
        items(state.snapshot.bookings, key = { it.id }) { booking ->
            val stay = Catalog.find(booking.request.stayId)
            Surface(
                onClick = { accept(Intent.Receipt(booking.request.key)) },
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    Box {
                        DestinationImage(
                            imageResource(stay.image),
                            null,
                            Modifier.fillMaxWidth().height(190.dp),
                            contentScale = ContentScale.Crop,
                        )
                        Surface(
                            Modifier.padding(16.dp),
                            color = Cream,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                if (booking.cancelled) "CANCELLED" else "CONFIRMED · DEMO",
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Forest,
                            )
                        }
                    }
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stay.name, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${booking.request.checkIn.pretty()} – ${booking.request.checkOut.pretty()}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row {
                            Text(
                                "${booking.request.guests} guests · ${booking.quote.nights} nights",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowForward,
                                "View reservation",
                                Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PassportScreen(state: RoamState, accept: (Intent) -> Unit) {
    val profile = state.snapshot.account.profile
    val active = state.snapshot.bookings.filterNot { it.cancelled }
    val countries = active.map { Catalog.find(it.request.stayId).country }.distinct()
    LazyColumn(
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { PageHeading("MORE THAN A PROFILE", "Your world,\nconnected.") }
        item {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Forest)) {
                PassportArtwork(Modifier.align(Alignment.CenterEnd).size(230.dp))
                Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Explore, null, tint = Color(0xFFE9E3C9))
                        Text(
                            "ROAM PASSPORT",
                            Modifier.padding(start = 8.dp).weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE9E3C9),
                        )
                        Icon(Icons.Outlined.Public, null, tint = Color(0xFFE9E3C9))
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = CircleShape, color = Color(0xFFDFE4CA)) {
                        Text(
                            initials(profile.name),
                            Modifier.padding(17.dp),
                            style = MaterialTheme.typography.titleLarge,
                            color = Forest,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            profile.name,
                            fontFamily = Serif,
                            fontSize = 30.sp,
                            color = Color.White,
                        )
                        Text(
                            "Curious by nature. A member by choice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCCD6C8),
                        )
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = .2f))
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "EXPLORER",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE9E3C9),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "MEMBER / DEMO",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE9E3C9),
                        )
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(
                        active.size.toString() to "Reservations",
                        countries.size.toString() to "Destinations",
                        state.snapshot.account.saved.size.toString() to "Saved stays",
                    )
                    .forEach { (value, label) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value, style = MaterialTheme.typography.headlineMedium)
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
            }
        }
        item {
            SurfaceCard {
                SectionHeading("What the community sees")
                Text(
                    profile.bio.ifBlank { "A new chapter is waiting to be written." },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (profile.shareHometown && profile.hometown.isNotBlank())
                    Text("⌂  ${profile.hometown}", style = MaterialTheme.typography.bodyMedium)
                if (profile.shareActivity)
                    Text(
                        "${active.size} reservations · ${countries.size} destinations",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                Text(
                    "This preview follows your privacy preferences. No profile is published from this demo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Column {
                SettingsRow(
                    Icons.Outlined.PersonOutline,
                    "Make it yours",
                    "Name, hometown, and a little about you",
                ) {
                    accept(Intent.EditProfile(true))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    Icons.Outlined.Shield,
                    "Your privacy, your choice",
                    "Decide what your public preview shows",
                ) {
                    accept(Intent.Privacy(true))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    Icons.Outlined.CardGiftcard,
                    "Community benefits",
                    "Claim your welcome travel credit",
                ) {
                    accept(Intent.Navigate(Destination.Wallet))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    Icons.Outlined.Tune,
                    "Demo controls",
                    "Try a declined card or recover a lost response",
                ) {
                    accept(Intent.Demo(true))
                }
            }
        }
        item { ErrorMessage(state.screen.error) }
        item {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Explore, null, tint = MaterialTheme.colorScheme.primary)
                Text("Belong a little more.", fontFamily = Serif, fontSize = 22.sp)
                Text(
                    "Roam 1.0 · Independent portfolio concept",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Local demo · No real payments or identity checks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
