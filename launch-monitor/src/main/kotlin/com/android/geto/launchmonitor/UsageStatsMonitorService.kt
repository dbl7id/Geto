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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.android.geto.domain.framework.UsageStatsForegroundAppDetector
import com.android.geto.domain.model.AppLaunchDetection
import com.android.geto.domain.repository.UserDataRepository
import com.android.geto.domain.usecase.detection.AppLaunchMonitor
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper
import com.android.geto.framework.notificationmanager.R as notificationManagerR
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
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

private const val NOTIFICATION_ID = 2
private const val ACTION_STOP = "com.android.geto.launchmonitor.action.STOP"

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
    lateinit var launchTransitionNotifier: LaunchTransitionNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
                            launchTransitionNotifier.onTransition(transition)

                            val text = when (transition.kind) {
                                AppLaunchMonitor.Transition.Kind.Applied -> getString(
                                    R.string.monitor_applied,
                                    transition.componentName.substringBefore('/'),
                                )

                                AppLaunchMonitor.Transition.Kind.Reverted -> idleText()
                            }

                            notificationManagerWrapper.notify(
                                NOTIFICATION_ID,
                                getNotification(text),
                            )
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

        return START_STICKY
    }

    override fun onDestroy() {
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

    private fun getNotification(text: String): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, UsageStatsMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(
            this,
            AndroidNotificationManagerWrapper.NOTIFICATION_CHANNEL_ID,
        )
            .setSmallIcon(notificationManagerR.drawable.baseline_settings_24)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                notificationManagerR.drawable.baseline_settings_24,
                getString(R.string.stop),
                stopPendingIntent,
            )
            .build()
    }

    companion object {
        private const val STOP_GRACE_MILLIS = 2_000L

        private val _isRunning = MutableStateFlow(false)

        val isRunning = _isRunning.asStateFlow()
    }
}
