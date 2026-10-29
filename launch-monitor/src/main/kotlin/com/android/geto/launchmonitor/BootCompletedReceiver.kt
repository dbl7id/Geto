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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.android.geto.common.ApplicationScope
import com.android.geto.domain.model.AppLaunchDetection
import com.android.geto.domain.repository.UserDataRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Brings the foreground service back after a reboot or an app update. The accessibility service
 * needs nothing like this; the system restores it by itself.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {
    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject
    lateinit var userDataRepository: UserDataRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()

        appScope.launch {
            try {
                if (userDataRepository.userData.first().appLaunchDetection ==
                    AppLaunchDetection.UsageStats
                ) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, UsageStatsMonitorService::class.java),
                    )
                }
            } catch (_: Exception) {
                // Not allowed to start a foreground service from here on this device; the user
                // can start it again from Settings.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
