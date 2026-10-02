package com.roam.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roam.core.*

@Composable
fun ExploreScreen(state: RoamState, accept: (Intent) -> Unit, onCompare: (() -> Unit)? = null) {
    LazyVerticalGrid(
        modifier = Modifier.testTag("discovery_feed").semantics { testTagsAsResourceId = true },
        columns = GridCells.Adaptive(320.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Explore,
                    null,
                    Modifier.size(29.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "roam",
                    fontSize = 31.sp,
                    fontFamily = Serif,
                    modifier = Modifier.padding(start = 7.dp).weight(1f),
                )
                IconButton(
                    { accept(Intent.SavedOnly) },
                    Modifier.size(48.dp).semantics { selected = state.screen.savedOnly },
                ) {
                    Icon(
                        if (state.screen.savedOnly) Icons.Outlined.Favorite
                        else Icons.Outlined.FavoriteBorder,
                        if (state.screen.savedOnly) "Show all stays" else "Show saved stays",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Surface(
                    onClick = { accept(Intent.Navigate(Destination.Passport)) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier =
                        Modifier.size(48.dp).semantics { contentDescription = "Open your passport" },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            initials(state.snapshot.account.profile.name),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHeading(
                "YOUR NEXT CHAPTER",
                "Go somewhere\nthat feels like you.",
                "Thoughtful stays. A world of belonging.",
            )
        }
        if (onCompare != null)
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    onClick = onCompare,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(Icons.Outlined.TravelExplore, null, Modifier.size(30.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Compare stays", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Hotels, vacation rentals, and a clearer view of the price.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
                    }
                }
            }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                state.screen.query,
                { accept(Intent.Query(it)) },
                Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Where will curiosity take you?") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (state.screen.query.isNotEmpty())
                        IconButton({ accept(Intent.Query("")) }) {
                            Icon(Icons.Outlined.Close, "Clear search")
                        }
                },
                shape = RoundedCornerShape(18.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                label = { Text("Explore places") },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (listOf("All stays") +
                        state.catalog.map { it.category }.filter { it.isNotBlank() }.distinct())
                    .forEach { label ->
                        val icon =
                            when (label) {
                                "Nature" -> Icons.Outlined.Terrain
                                "Coast" -> Icons.Outlined.Waves
                                "City" -> Icons.Outlined.Apartment
                                else -> Icons.Outlined.AutoAwesome
                            }
                        FilterChip(
                            selected = state.screen.category == label,
                            onClick = { accept(Intent.Category(label)) },
                            label = { Text(label) },
                            leadingIcon = { Icon(icon, null, Modifier.size(17.dp)) },
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeading(
                if (state.screen.savedOnly) "Your little wish list"
                else "Places with a little soul",
                "${state.stays.size} stays",
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) { ErrorMessage(state.screen.error) }
        if (state.stays.isEmpty())
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    Icons.Outlined.TravelExplore,
                    "Room for a new discovery",
                    "Try another place or save a stay with the heart icon.",
                    "Reset discovery",
                ) {
                    accept(Intent.Query(""))
                    accept(Intent.Category("All stays"))
                    if (state.screen.savedOnly) accept(Intent.SavedOnly)
                }
            }
        items(state.stays, key = { it.id }) { stay ->
            StayCard(
                stay,
                stay.id in state.snapshot.account.saved,
                { accept(Intent.OpenStay(stay.id)) },
                { accept(Intent.SaveStay(stay.id)) },
                Modifier.animateItem(),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Public, null, tint = MaterialTheme.colorScheme.secondary)
                Text("Less ordinary. More you.", fontFamily = Serif, fontSize = 20.sp)
                Text(
                    if (state.isDemo) "An independent travel concept · Demo stays"
                    else "Find your next place to belong.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun StayCard(
    stay: Stay,
    saved: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.fillMaxWidth()
                .height(250.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable(onClickLabel = "Explore ${stay.name}", onClick = onOpen)
        ) {
            DestinationImage(
                imageResource(stay.image),
                contentDescription = "${stay.country} destination inspiration",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = .48f)),
                            startY = 220f,
                        )
                    )
            )
            if (stay.rating.isNotBlank() && stay.reviews > 0)
                Surface(
                    Modifier.align(Alignment.TopStart).padding(14.dp),
                    color = Cream,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        "GUEST FAVORITE",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink,
                    )
                }
            RoundButton(
                if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                if (saved) "Unsave ${stay.name}" else "Save ${stay.name}",
                onSave,
                Modifier.align(Alignment.TopEnd).padding(10.dp),
                saved,
            )
            Row(
                Modifier.align(Alignment.BottomStart).padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Place, null, Modifier.size(15.dp), tint = Color.White)
                Text(
                    stay.location,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stay.name, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (stay.rating.isNotBlank())
                    Icon(
                        Icons.Outlined.Star,
                        null,
                        Modifier.padding(start = 8.dp, end = 3.dp).size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                if (stay.rating.isNotBlank())
                    Text(stay.rating, style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stay.host.isNotBlank())
                    Text(
                        "Hosted by ${stay.host}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else Spacer(Modifier.weight(1f))
                Text(stay.nightly.formatted(), style = MaterialTheme.typography.titleMedium)
                Text(
                    " / night",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
