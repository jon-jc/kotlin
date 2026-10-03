@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roam.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roam.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun ComparisonScreen(
    model: ComparisonViewModel,
    onOpenUrl: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    onAirbnbSearch: ((ComparisonQuery) -> Unit)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) {
        while (isActive) {
            model.updateTime()
            delay(15_000)
        }
    }
    BackHandler(state.selected != null) { model.accept(ComparisonIntent.CloseDetails) }
    ComparisonContent(
        state,
        model.today(),
        model::accept,
        onOpenRate = { id -> model.bookingUrl(id)?.let(onOpenUrl) },
        onBack = onBack,
        backLabel = backLabel,
        onAirbnbSearch =
            onAirbnbSearch?.let { launch ->
                {
                    model.airbnbQuery()?.let(launch)
                    Unit
                }
            },
    )
}

@Composable
fun ComparisonContent(
    state: ComparisonUiState,
    today: LocalDate,
    accept: (ComparisonIntent) -> Unit,
    onOpenRate: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    onAirbnbSearch: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val formIndex = if (onBack == null) 1 else 2
    val coverageIndex = formIndex + 1 + if (onAirbnbSearch == null) 0 else 1
    var revealedResult by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.result, state.selected) {
        val result = state.result
        val marker = result?.let { "${it.checkedAt}:${it.query.hashCode()}" }
        if (state.selected == null && result != null && marker != revealedResult) {
            revealedResult = marker
            listState.animateScrollToItem(coverageIndex)
        }
    }
    val search = {
        focus.clearFocus()
        keyboard?.hide()
        revealedResult = null
        accept(ComparisonIntent.Search)
    }
    if (state.selected != null) {
        ComparisonDetailsContent(state, accept, onOpenRate)
        return
    }
    var showDates by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        state = listState,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        onBack?.let { back ->
            item {
                TextButton(back) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    Spacer(Modifier.width(8.dp))
                    Text(backLabel)
                }
            }
        }
        item(key = "comparison_heading") {
            PageHeading(
                "MORE POSSIBILITIES, ONE SEARCH",
                "Find your place.\nCompare your options.",
                "Hotels and vacation rentals, with your trip in mind.",
            )
        }
        item(key = "comparison_form") {
            SurfaceCard {
                OutlinedTextField(
                    state.query.destination,
                    { accept(ComparisonIntent.Destination(it)) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Destination") },
                    placeholder = { Text("City, region, or place") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                )
                OutlinedButton(
                    { showDates = true },
                    Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Outlined.DateRange, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Travel dates", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${dateLabel(state.query.checkIn)} – ${dateLabel(state.query.checkOut)}"
                        )
                    }
                }
                ComparisonCounter("Adults", state.query.adults, 1, 6) {
                    accept(ComparisonIntent.Adults(it))
                }
                state.query.childAges.forEachIndexed { index, age ->
                    Column {
                        ComparisonCounter("Child ${index + 1} age", age, 1, 17) {
                            accept(ComparisonIntent.ChildAge(index, it))
                        }
                        TextButton({ accept(ComparisonIntent.RemoveChild(index)) }) {
                            Text("Remove child ${index + 1}")
                        }
                    }
                }
                if (state.query.childAges.size < 4)
                    TextButton({ accept(ComparisonIntent.AddChild) }) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add child")
                    }
                Text(
                    "One room · Enter each child's age at check-in. Ages 1–17 supported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ComparisonChoice(
                        "Currency",
                        state.query.currency,
                        comparisonCurrencies.map { it to it },
                        Modifier.weight(1f),
                    ) {
                        accept(ComparisonIntent.Currency(it))
                    }
                    ComparisonChoice(
                        "Search market",
                        comparisonMarkets.first { it.first == state.query.market }.second,
                        comparisonMarkets,
                        Modifier.weight(1f),
                    ) {
                        accept(ComparisonIntent.Market(it))
                    }
                }
                Text(
                    "Market can affect the rates and booking sites shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ComparisonScope.entries.forEach { scope ->
                        FilterChip(
                            state.query.scope == scope,
                            { accept(ComparisonIntent.Scope(scope)) },
                            label = { Text(scopeLabel(scope)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                ErrorMessage(state.error)
                PrimaryButton("Compare prices", search, busy = state.loading)
            }
        }
        if (onAirbnbSearch != null)
            item {
                SurfaceCard {
                    Text("Also considering Airbnb?", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Airbnb prices are not included. Enter or confirm your dates and guests on Airbnb.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onAirbnbSearch, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Search Airbnb separately")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                    }
                }
            }
        if (state.loading)
            item {
                Row(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("Looking for stays that fit your trip…")
                }
            }
        state.result?.let { result ->
            item(key = "comparison_coverage") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton({ scope.launch { listState.animateScrollToItem(formIndex) } }) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Edit search")
                    }
                    SourceStatuses(result.sourceStatuses)
                }
            }
            if (result.properties.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeading("Places to consider", "${result.properties.size} stays")
                        Text(tripSummary(result.query), style = MaterialTheme.typography.bodyMedium)
                        Freshness(result.checkedAt, state.resultFresh)
                        if (!state.resultFresh) OutlinedButton(search) { Text("Refresh prices") }
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                state.sort == ComparisonSort.RELEVANCE,
                                { accept(ComparisonIntent.Sort(ComparisonSort.RELEVANCE)) },
                                label = { Text("Provider order") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                            FilterChip(
                                state.sort == ComparisonSort.TOTAL,
                                { accept(ComparisonIntent.Sort(ComparisonSort.TOTAL)) },
                                label = { Text("Total with reported fees") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                        Text(
                            "Total-price ordering uses full-stay prices in ${result.query.currency} with taxes and fees reported included. Incomplete prices appear last. Compare room types and terms before choosing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.properties, key = { it.id }) { property ->
                    ComparisonPropertyCard(property, result.query) {
                        accept(ComparisonIntent.OpenProperty(property.id))
                    }
                }
            } else
                item {
                    EmptyState(
                        Icons.Outlined.TravelExplore,
                        "No prices to compare yet",
                        "Check the source status above, or try another destination and travel dates.",
                        "Try search again",
                    ) {
                        search()
                    }
                }
        }
        item {
            Text(
                "Observed prices can change. Confirm the room, guest policy, taxes, fees, and cancellation terms with the booking site before paying.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDates)
        ComparisonDates(state.query, today, { showDates = false }) { start, end ->
            accept(ComparisonIntent.Dates(start, end))
            showDates = false
        }
}

@Composable
private fun ComparisonPropertyCard(
    property: ComparisonProperty,
    query: ComparisonQuery,
    onOpen: () -> Unit,
) {
    SurfaceCard {
        ComparisonPhoto(property)
        Eyebrow(if (property.kind == ComparisonKind.HOTEL) "HOTEL" else "VACATION RENTAL")
        Text(
            property.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            property.source,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ComparisonPriceContent(property.summaryPrice, query)
        PrimaryButton("Compare booking options", onOpen)
    }
}

@Composable
private fun ComparisonPhoto(property: ComparisonProperty) {
    val image = ComparisonLinks.safeImageUrl(property.imageUrl)
    if (image != null)
        DestinationImage(
            image,
            null,
            Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Crop,
        )
    else
        Surface(
            Modifier.fillMaxWidth().height(90.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(18.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (property.kind == ComparisonKind.HOTEL) Icons.Outlined.Hotel
                    else Icons.Outlined.Home,
                    "Property photo unavailable",
                    Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
}

@Composable
private fun ComparisonDetailsContent(
    state: ComparisonUiState,
    accept: (ComparisonIntent) -> Unit,
    onOpenRate: (String) -> Unit,
) {
    val property = state.selected ?: return
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            TextButton({ accept(ComparisonIntent.CloseDetails) }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                Spacer(Modifier.width(8.dp))
                Text("Back to comparison")
            }
        }
        item { PageHeading("YOUR STAY, YOUR CHOICE", property.title, tripSummary(state.query)) }
        item { ComparisonPhoto(property) }
        item {
            Text(
                "Compare room types and inclusions carefully. Booking options may have different cancellation policies and extra charges.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { ErrorMessage(state.detailsError) }
        if (state.detailsLoading)
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("Checking booking options…")
                }
            }
        state.details?.let { details ->
            item { SourceStatuses(details.sourceStatuses) }
            item { Freshness(details.checkedAt, state.detailsFresh) }
            items(state.rates, key = { it.id }) { rate ->
                SurfaceCard {
                    Text(
                        rate.provider,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    rate.title
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    ComparisonPriceContent(rate.price, details.query)
                    Text(
                        rate.cancellation?.takeIf { it.isNotBlank() }
                            ?: "Cancellation terms not provided. Check with the booking site.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val linkAvailable = ComparisonLinks.safeBookingUrl(rate.bookingUrl) != null
                    PrimaryButton(
                        "View on ${rate.provider}",
                        { onOpenRate(rate.id) },
                        enabled = state.detailsFresh && linkAvailable,
                    )
                    if (!linkAvailable)
                        Text(
                            "Provider link unavailable.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    else if (!state.detailsFresh)
                        Text(
                            "Refresh booking options to open this price.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
            }
            if (details.rates.isEmpty())
                item {
                    Text(
                        "No booking options were returned for these dates. Refresh or try another stay.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
        }
        item {
            OutlinedButton(
                { accept(ComparisonIntent.RefreshDetails) },
                Modifier.fillMaxWidth().heightIn(min = 52.dp),
                enabled = !state.detailsLoading,
            ) {
                Text("Refresh booking options")
            }
        }
        item {
            Text(
                "You'll continue on the booking site's website. A listed price is an observation, not a reservation or a guaranteed rate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ComparisonPriceContent(price: ComparisonPrice?, query: ComparisonQuery) {
    val total = price?.total
    if (total != null) {
        Text(
            "${total.formatted()} ${total.currency}",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            "Reported full-stay total · ${nights(query)} nights",
            style = MaterialTheme.typography.labelLarge,
        )
        if (total.currency != query.currency)
            Text(
                "Different currency from your search; excluded from total-price ordering.",
                style = MaterialTheme.typography.bodySmall,
            )
    } else {
        Text("Full-stay total unavailable", style = MaterialTheme.typography.titleMedium)
        price?.nightly?.let {
            Text(
                "Reported nightly rate ${it.formatted()} ${it.currency}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    Text(
        if (price?.taxCoverage == TaxCoverage.REPORTED_INCLUDED) "Taxes and fees reported included."
        else "Taxes and fees not confirmed. Extra charges may apply.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Freshness(checkedAt: String, fresh: Boolean) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            if (fresh) "Recently checked" else "Prices need a refresh",
            style = MaterialTheme.typography.titleSmall,
        )
        val observed =
            runCatching {
                    Instant.parse(checkedAt)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("MMM d, HH:mm z"))
                }
                .getOrDefault("Time unavailable")
        Text(
            "Observed $observed · valid for up to 5 minutes",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceStatuses(statuses: List<ComparisonSourceStatus>) {
    SurfaceCard {
        SectionHeading("Search coverage")
        if (statuses.isEmpty())
            Text(
                "Source status unavailable. Coverage has not been confirmed.",
                style = MaterialTheme.typography.bodyMedium,
            )
        statuses.forEach { source ->
            val type = if (source.kind == ComparisonKind.HOTEL) "Hotels" else "Vacation rentals"
            val status =
                when (source.status) {
                    ComparisonSourceState.LIVE -> "Results received"
                    ComparisonSourceState.EMPTY -> "No matching stays returned"
                    ComparisonSourceState.NOT_CONFIGURED -> "Live search not connected"
                    ComparisonSourceState.TIMEOUT -> "Source timed out"
                    ComparisonSourceState.QUOTA -> "Source temporarily at its limit"
                    ComparisonSourceState.ERROR -> "Source unavailable"
                    ComparisonSourceState.UNSUPPORTED -> "This search isn't supported"
                    ComparisonSourceState.BUSY -> "Source is busy; try again"
                }
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier =
                    Modifier.semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
            ) {
                Text("$type · $status", style = MaterialTheme.typography.titleSmall)
                Text(
                    source.source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                source.message
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun ComparisonCounter(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        OutlinedIconButton({ onChange(value - 1) }, Modifier.size(48.dp), enabled = value > min) {
            Icon(Icons.Outlined.Remove, "Decrease ${label.lowercase()}")
        }
        Text(
            "$value",
            Modifier.widthIn(min = 36.dp).semantics { contentDescription = "$label: $value" },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedIconButton({ onChange(value + 1) }, Modifier.size(48.dp), enabled = value < max) {
            Icon(Icons.Outlined.Add, "Increase ${label.lowercase()}")
        }
    }
}

@Composable
private fun ComparisonChoice(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    modifier: Modifier,
    onChoose: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            { expanded = true },
            Modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onChoose(code)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ComparisonDates(
    query: ComparisonDraft,
    today: LocalDate,
    onDismiss: () -> Unit,
    onDates: (LocalDate, LocalDate) -> Unit,
) {
    val picker =
        rememberDateRangePickerState(
            initialSelectedStartDateMillis =
                LocalDate.parse(query.checkIn)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
            initialSelectedEndDateMillis =
                LocalDate.parse(query.checkOut)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() in
                            today..today.plusDays(393)
                },
        )
    val start =
        picker.selectedStartDateMillis?.let {
            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
        }
    val end =
        picker.selectedEndDateMillis?.let {
            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
        }
    val valid =
        start != null &&
            end != null &&
            start <= today.plusDays(365) &&
            ChronoUnit.DAYS.between(start, end) in 1..28
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({ if (start != null && end != null) onDates(start, end) }, enabled = valid) {
                Text("Use these dates")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            picker,
            Modifier.weight(1f, fill = false).heightIn(max = 520.dp),
            title = { Text("Choose 1–28 nights", Modifier.padding(start = 24.dp, top = 16.dp)) },
        )
    }
}

private fun dateLabel(value: String): String =
    runCatching { LocalDate.parse(value).pretty() }.getOrDefault(value)

private fun nights(query: ComparisonQuery): Long =
    runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(query.checkIn), LocalDate.parse(query.checkOut))
        }
        .getOrDefault(0)

private fun tripSummary(query: ComparisonQuery): String =
    "${dateLabel(query.checkIn)} – ${dateLabel(query.checkOut)} · ${query.adults} adults${if (query.childAges.isEmpty()) "" else " · ${query.childAges.size} children"} · 1 room"

private fun tripSummary(query: ComparisonDraft): String =
    "${dateLabel(query.checkIn)} – ${dateLabel(query.checkOut)} · ${query.adults} adults${if (query.childAges.isEmpty()) "" else " · ${query.childAges.size} children"} · 1 room"

private fun scopeLabel(scope: ComparisonScope): String =
    when (scope) {
        ComparisonScope.ALL -> "All stays"
        ComparisonScope.HOTELS -> "Hotels"
        ComparisonScope.RENTALS -> "Vacation rentals"
    }
