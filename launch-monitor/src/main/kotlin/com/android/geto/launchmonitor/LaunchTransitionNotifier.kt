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

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import com.android.geto.broadcastreceiver.RevertSettingsBroadcastReceiver
import com.android.geto.domain.framework.PackageManagerWrapper
import com.android.geto.domain.model.AppSettingsResult
import com.android.geto.domain.usecase.detection.AppLaunchMonitor
import com.android.geto.framework.notificationmanager.AndroidNotificationManagerWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.android.geto.framework.notificationmanager.R as notificationManagerR

/**
 * Same notification the shortcut flow shows (same id, so the two never stack), now also posted
 * for launches the monitor noticed, and removed again when the monitor reverts.
 */
@Singleton
class LaunchTransitionNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationManagerWrapper: AndroidNotificationManagerWrapper,
    private val packageManagerWrapper: PackageManagerWrapper,
) {
    suspend fun onTransition(transition: AppLaunchMonitor.Transition) {
        val notificationId = transition.componentName.hashCode()

        when (transition.kind) {
            AppLaunchMonitor.Transition.Kind.Reverted -> notificationManagerWrapper.cancel(notificationId)

            AppLaunchMonitor.Transition.Kind.Applied -> {
                val text = when (transition.result) {
                    AppSettingsResult.Success -> R.string.transition_applied
                    AppSettingsResult.Failure -> R.string.transition_failure
                    AppSettingsResult.NoPermission -> R.string.transition_no_permission
                    AppSettingsResult.InvalidValues -> R.string.transition_invalid_values
                    // Not reachable: the monitor only targets enabled settings.
                    AppSettingsResult.EmptyAppSettings,
                    AppSettingsResult.DisabledAppSettings,
                    -> return
                }

                notificationManagerWrapper.notify(
                    id = notificationId,
                    notification = getNotification(
                        notificationId = notificationId,
                        componentName = transition.componentName,
                        icon = packageManagerWrapper.getActivityIcon(transition.componentName),
                        text = context.getString(text),
                        canRevert = transition.result != AppSettingsResult.NoPermission,
                    ),
                )
            }
        }
    }

    private fun getNotification(
        notificationId: Int,
        componentName: String,
        icon: ByteArray?,
        text: String,
        canRevert: Boolean,
    ): Notification {
        val revertIntent = Intent(context, RevertSettingsBroadcastReceiver::class.java).apply {
            action = AndroidNotificationManagerWrapper.ACTION_REVERT_SETTINGS
            putExtra(AndroidNotificationManagerWrapper.NOTIFICATION_EXTRA_COMPONENT_NAME, componentName)
            putExtra(AndroidNotificationManagerWrapper.NOTIFICATION_EXTRA_NOTIFICATION_ID, notificationId)
        }

        // The request code matters: PendingIntents are matched without their extras, so sharing
        // one would make every notification's Revert button revert the most recent app.
        val revertPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            revertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, AndroidNotificationManagerWrapper.NOTIFICATION_CHANNEL_ID).apply {
            setSmallIcon(notificationManagerR.drawable.baseline_settings_24)

            icon?.let { setLargeIcon(Icon.createWithData(it, 0, it.size)) }

            setContentTitle(context.getString(R.string.transition_title))
            setContentText(text)
            setSilent(true)

            if (canRevert) {
                addAction(
                    notificationManagerR.drawable.baseline_settings_24,
                    context.getString(notificationManagerR.string.revert),
                    revertPendingIntent,
                )
            }
        }.build()
    }
}
