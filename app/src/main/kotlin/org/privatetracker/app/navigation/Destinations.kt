package org.privatetracker.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import org.privatetracker.R
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.designsystem.R as DesignR

@Serializable data object MapKey : NavKey

@Serializable data object DevicesKey : NavKey

@Serializable data object ServerKey : NavKey

@Serializable data object TrackerKey : NavKey

@Serializable data object SettingsKey : NavKey

@Serializable data class DeviceDetailKey(val deviceId: String) : NavKey

@Serializable data object PermissionsKey : NavKey

/** The server's QR code for pairing another phone. */
@Serializable data object ServerPairingKey : NavKey

/** Scanning a server's QR code, or confirming the pairing [link] a camera app opened. */
@Serializable data class TrackerPairingKey(val link: String? = null) : NavKey

/** The tabs of the bottom bar. Each one starts its own back stack. */
enum class TopLevelDestination(val key: NavKey, @StringRes val label: Int) {
    MAP(MapKey, R.string.nav_map),
    DEVICES(DevicesKey, R.string.nav_devices),
    SERVER(ServerKey, R.string.nav_server),
    TRACKER(TrackerKey, R.string.nav_tracker),
    SETTINGS(SettingsKey, R.string.nav_settings),
    ;

    @Composable
    fun icon(): Painter = when (this) {
        MAP -> painterResource(DesignR.drawable.ic_map)
        DEVICES -> rememberVectorPainter(Icons.AutoMirrored.Filled.List)
        SERVER -> painterResource(DesignR.drawable.ic_server)
        TRACKER -> painterResource(DesignR.drawable.ic_location)
        SETTINGS -> rememberVectorPainter(Icons.Filled.Settings)
    }

    companion object {
        /** The tabs a mode shows, the first one being where the app opens. */
        fun forMode(mode: AppMode): List<TopLevelDestination> = when (mode) {
            AppMode.TRACKER -> listOf(TRACKER, SETTINGS)
            AppMode.SERVER -> listOf(MAP, DEVICES, SERVER, SETTINGS)
            AppMode.TRACKER_AND_SERVER -> listOf(MAP, DEVICES, SERVER, TRACKER, SETTINGS)
        }
    }
}
