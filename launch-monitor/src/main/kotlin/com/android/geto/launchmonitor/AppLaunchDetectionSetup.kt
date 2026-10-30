/*
 *
 *   Copyright 2023 Einstein Blanco
 *
 *   Licensed under the GNU General Public License v3.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       https://www.gnu.org/licenses/gpl-3.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package com.android.geto.launchmonitor

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.android.geto.domain.model.AppLaunchDetection

/**
 * Everything the UI needs to get a detection method into a working state: permission checks,
 * enabling the accessibility service, and starting or stopping the foreground service.
 */
object AppLaunchDetectionSetup {

    /** Whether the method can work right now without the user doing anything more. */
    fun isReady(context: Context, detection: AppLaunchDetection): Boolean = when (detection) {
        AppLaunchDetection.Off -> true
        AppLaunchDetection.Accessibility -> isAccessibilityServiceEnabled(context)
        AppLaunchDetection.UsageStats -> hasUsageAccess(context)
    }

    /**
     * Puts the system into the state [detection] needs, as far as Geto is allowed to. Returns a
     * settings screen to open when the user has to finish the job, or `null` if nothing is left.
     */
    fun activate(context: Context, detection: AppLaunchDetection): Intent? {
        // Only one method is ever active. In particular the accessibility service is switched off
        // for the other methods: some banking apps refuse to run while any accessibility service
        // they don't recognise is enabled.
        if (detection != AppLaunchDetection.Accessibility) tryDisableAccessibilityService(context)

        if (detection != AppLaunchDetection.UsageStats) {
            context.stopService(Intent(context, UsageStatsMonitorService::class.java))
        }

        return when (detection) {
            AppLaunchDetection.Off -> null

            AppLaunchDetection.Accessibility -> {
                if (tryEnableAccessibilityService(context)) null else accessibilitySettingsIntent()
            }

            AppLaunchDetection.UsageStats -> {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, UsageStatsMonitorService::class.java),
                )

                if (hasUsageAccess(context)) null else usageAccessSettingsIntent(context)
            }
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOpsManager = context.getSystemService(AppOpsManager::class.java) ?: return false

        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }

        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val component = serviceComponent(context)

        return enabledAccessibilityServices(context).any { it == component }
    }

    /**
     * Geto already needs `WRITE_SECURE_SETTINGS`, which is also enough to enable an accessibility
     * service without sending the user through the system's accessibility screen (and without
     * Android 13+'s "restricted setting" detour for apps installed outside a store).
     */
    fun tryEnableAccessibilityService(context: Context): Boolean = try {
        val component = serviceComponent(context)
        val enabled = enabledAccessibilityServices(context)

        if (component !in enabled) {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (enabled + component).joinToString(":") { it.flattenToString() },
            )
        }

        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

        true
    } catch (_: SecurityException) {
        false
    }

    fun tryDisableAccessibilityService(context: Context) {
        try {
            val component = serviceComponent(context)
            val enabled = enabledAccessibilityServices(context)

            if (component !in enabled) return

            val remaining = enabled - component

            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                remaining.joinToString(":") { it.flattenToString() },
            )

            if (remaining.isEmpty()) {
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0,
                )
            }
        } catch (_: SecurityException) {
            // Without WRITE_SECURE_SETTINGS the user has to switch it off themselves.
        }
    }

    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun usageAccessSettingsIntent(context: Context): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        // Jumping straight to Geto's own entry is only supported from Android 10.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            data = Uri.parse("package:${context.packageName}")
        }

        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun serviceComponent(context: Context) = ComponentName(context, GetoAccessibilityService::class.java)

    private fun enabledAccessibilityServices(context: Context): List<ComponentName> = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it) }
}
