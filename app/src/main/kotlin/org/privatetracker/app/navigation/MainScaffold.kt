package org.privatetracker.app.navigation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.privatetracker.R
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.feature.devices.detail.DeviceDetailRoute
import org.privatetracker.feature.devices.list.DeviceListRoute
import org.privatetracker.feature.devices.map.DevicesMapRoute
import org.privatetracker.feature.onboarding.PermissionsRoute
import org.privatetracker.feature.server.ui.PairingRoute
import org.privatetracker.feature.server.ui.ServerRoute
import org.privatetracker.feature.settings.SettingsRoute
import org.privatetracker.feature.tracker.pairing.TrackerPairingRoute
import org.privatetracker.feature.tracker.ui.TrackerRoute

/**
 * The main screens of [mode] with a bottom bar. The back stack starts at the selected tab; detail
 * screens go on top of it. Features never navigate themselves: they get callbacks from here.
 */
@Composable
fun MainScaffold(mode: AppMode, pairingLink: String?, onPairingLinkHandled: () -> Unit) {
    val destinations = TopLevelDestination.forMode(mode)
    val start = destinations.first()
    val backStack = rememberNavBackStack(start.key)

    // A mode change can remove the tab on screen.
    LaunchedEffect(mode) {
        if (destinations.none { it.key == backStack.first() }) backStack.reset(start.key)
    }
    // From another tab, back returns to the first one before it leaves the app.
    BackHandler(enabled = backStack.size == 1 && backStack.first() != start.key) { backStack.reset(start.key) }

    val pop: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    val openPermissions: () -> Unit = { backStack.add(PermissionsKey) }
    val openDevice: (DeviceId) -> Unit = { backStack.add(DeviceDetailKey(it.value)) }
    val openTrackerPairing: () -> Unit = { backStack.add(TrackerPairingKey()) }

    // A pairing link opened from a camera app. Only a phone that tracks can pair with a server.
    val context = LocalContext.current
    LaunchedEffect(pairingLink) {
        if (pairingLink != null) {
            if (mode.tracks) {
                backStack.add(TrackerPairingKey(pairingLink))
            } else {
                Toast.makeText(context, R.string.pairing_link_needs_tracker, Toast.LENGTH_LONG).show()
            }
            onPairingLinkHandled()
        }
    }

    Scaffold(
        // Each screen draws its own top bar under the status bar; this scaffold only adds the bottom bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = backStack.first() == destination.key,
                        onClick = { backStack.reset(destination.key) },
                        icon = { Icon(destination.icon(), contentDescription = null) },
                        label = { Text(stringResource(destination.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
            onBack = pop,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<MapKey> { DevicesMapRoute(onOpenDevice = openDevice) }
                entry<DevicesKey> { DeviceListRoute(onOpenDevice = openDevice) }
                entry<ServerKey> {
                    ServerRoute(
                        onOpenPermissions = openPermissions,
                        onOpenDevices = { backStack.reset(DevicesKey) },
                        onOpenPairing = { backStack.add(ServerPairingKey) },
                    )
                }
                entry<TrackerKey> {
                    TrackerRoute(
                        onOpenSettings = { backStack.reset(SettingsKey) },
                        onOpenPermissions = openPermissions,
                        onOpenPairing = openTrackerPairing,
                    )
                }
                entry<SettingsKey> { SettingsRoute(onOpenPermissions = openPermissions, onOpenPairing = openTrackerPairing) }
                entry<ServerPairingKey> { PairingRoute(onBack = pop) }
                entry<TrackerPairingKey> { key ->
                    TrackerPairingRoute(
                        link = key.link,
                        onDone = { backStack.reset(TrackerKey) },
                        onBack = pop,
                        onOpenPermissions = openPermissions,
                    )
                }
                entry<DeviceDetailKey> { key -> DeviceDetailRoute(deviceId = key.deviceId, onBack = pop) }
                entry<PermissionsKey> {
                    PermissionsRoute(mode = mode, doneLabel = stringResource(R.string.permissions_done), onDone = pop, onBack = pop)
                }
            },
        )
    }
}

/** Leaves only [key] on the stack. Never empties it, since NavDisplay needs an entry at all times. */
private fun NavBackStack<NavKey>.reset(key: NavKey) {
    while (size > 1) removeAt(lastIndex)
    if (isEmpty()) add(key) else if (first() != key) set(0, key)
}
