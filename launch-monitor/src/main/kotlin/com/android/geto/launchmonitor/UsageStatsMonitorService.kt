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

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.android.geto.domain.framework.PackageManagerWrapper
import com.android.geto.domain.framework.UsageStatsForegroundAppDetector
import com.android.geto.domain.model.AppLaunchDetection
import com.android.geto.domain.model.AppSettingsResult
import com.android.geto.domain.repository.UserDataRepository
import com.android.geto.domain.usecase.RevertAppSettingsUseCase
import com.android.geto.domain.usecase.detection.AppLaunchMonitor
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.android.geto.framework.notificationmanager.R as notificationManagerR

private const val NOTIFICATION_ID = 2
private const val ACTION_STOP = "com.android.geto.launchmonitor.action.STOP"
private const val ACTION_REVERT = "com.android.geto.launchmonitor.action.REVERT"
private const val EXTRA_PACKAGE_NAME = "package_name"

/**
 * Hosts the usage stats based monitor. Declared as a `specialUse` foreground service rather than
 * `dataSync`: from Android 15 `dataSync` services are cut off after six hours in any 24, which
 * would silently end monitoring.
 */
@AndroidEntryPoint
class UsageStatsMonitorService : Service() {
    @Inject
    lateinit var appLaunchMonitor: AppLaunchMonitor

    @Inject
    lateinit var detector: UsageStatsForegroundAppDetector

    @Inject
    lateinit var userDataRepository: UserDataRepository

    @Inject
    lateinit var notificationManagerWrapper: AndroidNotificationManagerWrapper

    @Inject
    lateinit var packageManagerWrapper: PackageManagerWrapper

    @Inject
    lateinit var revertAppSettingsUseCase: RevertAppSettingsUseCase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** What is applied right now (component name to result), oldest first. Use `synchronized`. */
    private val applied = LinkedHashMap<String, AppSettingsResult>()

    // The monitor reverts what is still applied while it shuts down, after onDestroy. Posting
    // then would leave a notification behind with nothing running behind it.
    @Volatile
    private var isDestroyed = false

    override fun onCreate() {
        super.onCreate()

        _isRunning.value = true

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                getNotification(idleText()),
                foregroundServiceType(),
            )
        } catch (_: Exception) {
            // Typically ForegroundServiceStartNotAllowedException after a restart from the
            // background. Nothing useful can be done without the notification.
            stopSelf()

            return
        }

        scope.launch {
            userDataRepository.userData
                .map { it.appLaunchDetection }
                .distinctUntilChanged()
                .collectLatest { detection ->
                    if (detection == AppLaunchDetection.UsageStats) {
                        appLaunchMonitor.run(detector = detector) { transition ->
                            onTransition(transition)
                        }
                    } else {
                        // The setting is written just before the service is started, so the first
                        // value read can still be the old one. Give the new one a moment to land;
                        // collectLatest cancels this wait if it does.
                        delay(STOP_GRACE_MILLIS)

                        stopSelf()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                try {
                    userDataRepository.updateAppLaunchDetection(AppLaunchDetection.Off)
                } finally {
                    stopSelf()
                }
            }

            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_REVERT) {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)

            if (packageName != null) scope.launch { revertApplied(packageName) }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true

        _isRunning.value = false

        // Cancelling the monitor reverts whatever it still has applied.
        scope.cancel()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun idleText(): String = getString(
        if (AppLaunchDetectionSetup.hasUsageAccess(
                this,
            )
        ) {
            R.string.monitor_waiting
        } else {
            R.string.monitor_needs_usage_access
        },
    )

    @SuppressLint("InlinedApi")
    private fun foregroundServiceType(): Int = if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    ) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
    }

    private suspend fun onTransition(transition: AppLaunchMonitor.Transition) {
        val componentName = transition.componentName

        if (isDestroyed) return

        when (transition.kind) {
            AppLaunchMonitor.Transition.Kind.Applied -> when (transition.result) {
                // Nothing was written, so there is nothing to revert later. Say so, once.
                AppSettingsResult.NoPermission -> {
                    notificationManagerWrapper.notify(
                        NOTIFICATION_ID,
                        getNotification(
                            text = getString(R.string.transition_no_permission),
                            icon = packageManagerWrapper.getActivityIcon(componentName),
                        ),
                    )

                    return
                }

                // Not reachable: the monitor only targets enabled settings.
                AppSettingsResult.EmptyAppSettings,
                AppSettingsResult.DisabledAppSettings,
                -> return

                else -> synchronized(applied) {
                    // Remove first so a repeated apply moves to the end, as the newest.
                    applied.remove(componentName)

                    applied[componentName] = transition.result
                }
            }

            AppLaunchMonitor.Transition.Kind.Reverted -> synchronized(applied) {
                applied.remove(componentName)
            }
        }

        refreshNotification()
    }

    /** What the Revert button does: revert the app now and go back to waiting. */
    private suspend fun revertApplied(packageName: String) {
        val componentNames = synchronized(applied) {
            applied.keys.filter { it.substringBefore('/') == packageName }
        }

        componentNames.forEach { revertAppSettingsUseCase(componentName = it) }

        synchronized(applied) { applied.keys.removeAll(componentNames.toSet()) }

        refreshNotification()
    }

    /** Shows the most recently applied app, or the idle text when nothing is applied. */
    private suspend fun refreshNotification() {
        if (isDestroyed) return

        val latest = synchronized(applied) { applied.entries.lastOrNull()?.toPair() }

        val notification = if (latest == null) {
            getNotification(text = idleText())
        } else {
            val (componentName, result) = latest

            val packageName = componentName.substringBefore('/')

            getNotification(
                text = when (result) {
                    AppSettingsResult.Failure -> getString(R.string.transition_failure)
                    AppSettingsResult.InvalidValues -> getString(R.string.transition_invalid_values)
                    else -> getString(R.string.monitor_applied, packageName)
                },
                packageName = packageName,
                icon = packageManagerWrapper.getActivityIcon(componentName),
            )
        }

        notificationManagerWrapper.notify(NOTIFICATION_ID, notification)
    }

    /**
     * @param packageName the app a Revert button should act on. No button when it is `null`.
     * @param icon the app's icon, shown beside the text.
     */
    private fun getNotification(
        text: String,
        packageName: String? = null,
        icon: ByteArray? = null,
    ): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, UsageStatsMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(
            this,
            AndroidNotificationManagerWrapper.NOTIFICATION_CHANNEL_ID,
        ).apply {
            setSmallIcon(notificationManagerR.drawable.baseline_settings_24)

            icon?.let { setLargeIcon(Icon.createWithData(it, 0, it.size)) }

            setContentTitle(getString(R.string.monitor_notification_title))
            setContentText(text)
            setOngoing(true)
            setSilent(true)

            addAction(
                notificationManagerR.drawable.baseline_settings_24,
                getString(R.string.stop),
                stopPendingIntent,
            )

            if (packageName != null) {
                val revertPendingIntent = PendingIntent.getService(
                    this@UsageStatsMonitorService,
                    1,
                    Intent(this@UsageStatsMonitorService, UsageStatsMonitorService::class.java)
                        .setAction(ACTION_REVERT)
                        .putExtra(EXTRA_PACKAGE_NAME, packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

                addAction(
                    notificationManagerR.drawable.baseline_settings_24,
                    getString(notificationManagerR.string.revert),
                    revertPendingIntent,
                )
            }
        }.build()
    }

    companion object {
        private const val STOP_GRACE_MILLIS = 2_000L

        private val _isRunning = MutableStateFlow(false)

        val isRunning = _isRunning.asStateFlow()
    }
}
