package com.roam.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun RoamApp(viewModel: RoamViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accept = viewModel::accept
    val snackbar = remember { SnackbarHostState() }
    val screen = state.screen
    val detail = screen.selectedStay != null || screen.receipt != null
    BackHandler(detail) { accept(Intent.Back) }
    LaunchedEffect(screen.notice) {
        screen.notice?.let { snackbar.showSnackbar(it); accept(Intent.DismissNotice) }
    }
    val navigation = listOf(Destination.Explore to Icons.Outlined.Explore, Destination.Trips to Icons.Outlined.Luggage, Destination.Wallet to Icons.Outlined.AccountBalanceWallet, Destination.Passport to Icons.Outlined.Badge)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!detail && !wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    navigation.forEach { (destination, icon) -> NavigationBarItem(selected = screen.destination == destination, onClick = { accept(Intent.Navigate(destination)) }, icon = { Icon(icon, null) }, label = { Text(destination.name) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer)) }
                }
            },
        ) { insets ->
            Row(Modifier.fillMaxSize().padding(insets)) {
                if (wide && !detail) NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(24.dp)); Icon(Icons.Outlined.Explore, "Roam", tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(32.dp))
                    navigation.forEach { (destination, icon) -> NavigationRailItem(selected = screen.destination == destination, onClick = { accept(Intent.Navigate(destination)) }, icon = { Icon(icon, null) }, label = { Text(destination.name) }) }
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = if (screen.destination == Destination.Explore && !detail) 1080.dp else 720.dp).fillMaxSize()) {
                        when {
                            state.loadError != null -> EmptyState(Icons.Outlined.CloudOff, "A small detour", state.loadError!!, "Try again") { accept(Intent.RetryLoad) }
                            !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription = "Opening your passport" }) }
                            screen.receipt != null -> ReceiptScreen(state.snapshot.bookings.firstOrNull { it.request.key == screen.receipt }, viewModel.today(), screen.busy, screen.error, accept)
                            screen.selectedStay != null && screen.checkout -> CheckoutScreen(state, viewModel.quote(), accept)
                            screen.selectedStay != null -> StayDetail(state, viewModel.today(), accept)
                            screen.destination == Destination.Explore -> ExploreScreen(state, accept)
                            screen.destination == Destination.Trips -> TripsScreen(state, accept)
                            screen.destination == Destination.Wallet -> WalletScreen(state, accept)
                            else -> PassportScreen(state, accept)
                        }
                    }
                }
            }
        }
    }
    if (screen.profileEditor) ProfileDialog(state, accept)
    if (screen.privacyEditor) PrivacyDialog(state, accept)
    if (screen.demoEditor) DemoDialog(state, accept)
}

@Composable private fun ProfileDialog(state: RoamState, accept: (Intent) -> Unit) {
    val profile = state.snapshot.account.profile
    var name by rememberSaveable { mutableStateOf(profile.name) }
    var hometown by rememberSaveable { mutableStateOf(profile.hometown) }
    var bio by rememberSaveable { mutableStateOf(profile.bio) }
    AlertDialog(onDismissRequest = { if (!state.screen.busy) accept(Intent.EditProfile(false)) }, title = { Text("A little more you", style = MaterialTheme.typography.headlineMedium) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("The details that make your passport yours.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { name = it.take(51) }, label = { Text("Name") }, singleLine = true, enabled = !state.screen.busy)
            OutlinedTextField(hometown, { hometown = it.take(81) }, label = { Text("Hometown") }, singleLine = true, enabled = !state.screen.busy)
            OutlinedTextField(bio, { bio = it.take(161) }, label = { Text("A little about you") }, minLines = 2, supportingText = { Text("${bio.length}/160") }, enabled = !state.screen.busy)
            ErrorMessage(state.screen.error)
        }
    }, confirmButton = { TextButton({ accept(Intent.SaveProfile(name, hometown, bio)) }, enabled = !state.screen.busy) { Text("Save profile") } }, dismissButton = { TextButton({ accept(Intent.EditProfile(false)) }, enabled = !state.screen.busy) { Text("Cancel") } })
}
@Composable private fun PrivacyDialog(state: RoamState, accept: (Intent) -> Unit) {
    var hometown by rememberSaveable { mutableStateOf(state.snapshot.account.profile.shareHometown) }
    var activity by rememberSaveable { mutableStateOf(state.snapshot.account.profile.shareActivity) }
    AlertDialog(onDismissRequest = { accept(Intent.Privacy(false)) }, icon = { Icon(Icons.Outlined.Shield, null) }, title = { Text("Belong on your terms") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Choose what appears in your community preview. Your wallet and receipts always stay private.")
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Show hometown", Modifier.weight(1f)); Switch(hometown, { hometown = it }, Modifier.semantics { contentDescription = "Show hometown" }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Show travel activity", Modifier.weight(1f)); Switch(activity, { activity = it }, Modifier.semantics { contentDescription = "Show travel activity" }) }
            ErrorMessage(state.screen.error)
        }
    }, confirmButton = { TextButton({ accept(Intent.SavePrivacy(hometown, activity)) }, enabled = !state.screen.busy) { Text("Save privacy") } }, dismissButton = { TextButton({ accept(Intent.Privacy(false)) }) { Text("Cancel") } })
}
@Composable private fun DemoDialog(state: RoamState, accept: (Intent) -> Unit) {
    AlertDialog(onDismissRequest = { accept(Intent.Demo(false)) }, title = { Text("Try the unexpected") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Explore how checkout handles things that don't go to plan. All payments are simulated.")
            listOf(Triple(DemoMode.Normal, "Normal", "Confirm a reservation and use travel credit."), Triple(DemoMode.Decline, "Card declined", "Fail before saving; the balance stays intact."), Triple(DemoMode.LostResponse, "Response interrupted", "Save the booking, then safely recover its receipt on retry.")).forEach { (mode, title, description) ->
                Surface(onClick = { accept(Intent.Mode(mode)) }, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(state.screen.demoMode == mode, onClick = null)
                        Column(Modifier.padding(start = 10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }, confirmButton = { TextButton({ accept(Intent.Demo(false)) }) { Text("Done") } })
}
