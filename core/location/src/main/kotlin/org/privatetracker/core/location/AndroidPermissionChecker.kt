package org.privatetracker.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.port.PermissionChecker
import javax.inject.Inject

/** Reads permission state at call time, so a grant or revoke in system settings is seen at once. */
class AndroidPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : PermissionChecker {
    override fun isApplicable(permission: AppPermission): Boolean = when (permission) {
        AppPermission.PRECISE_LOCATION, AppPermission.BATTERY_OPTIMIZATION_EXEMPTION -> true
        AppPermission.BACKGROUND_LOCATION -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        AppPermission.NOTIFICATIONS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        AppPermission.LOCAL_NETWORK -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
    }

    override fun isGranted(permission: AppPermission): Boolean {
        if (!isApplicable(permission)) return true
        return when (permission) {
            AppPermission.BATTERY_OPTIMIZATION_EXEMPTION ->
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            else -> runtimePermissions(permission).all { granted(it) }
        }
    }

    private fun granted(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * The runtime permissions behind [permission], as the system dialog asks for them. Empty for
         * the battery exemption, which only system settings grant.
         */
        fun runtimePermissions(permission: AppPermission): List<String> = when (permission) {
            // Asking for fine location alone is refused on Android 12+; coarse must come with it.
            AppPermission.PRECISE_LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            AppPermission.BACKGROUND_LOCATION ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyList()
            AppPermission.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            AppPermission.LOCAL_NETWORK ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) listOf(Manifest.permission.ACCESS_LOCAL_NETWORK) else emptyList()
            AppPermission.BATTERY_OPTIMIZATION_EXEMPTION -> emptyList()
        }
    }
}
