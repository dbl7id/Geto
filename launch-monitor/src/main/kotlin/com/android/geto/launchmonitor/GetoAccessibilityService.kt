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

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.android.geto.domain.model.AppLaunchDetection
import com.android.geto.domain.repository.UserDataRepository
import com.android.geto.domain.usecase.detection.AppLaunchMonitor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Event driven app launch detection. The system keeps this bound for as long as it is enabled,
 * restarts it after a crash and brings it back after a reboot, so unlike a foreground service it
 * does not get killed by aggressive battery managers.
 *
 * It does nothing unless [AppLaunchDetection.Accessibility] is the selected method, so it is safe
 * to leave enabled while the user switches methods.
 */
@AndroidEntryPoint
class GetoAccessibilityService : AccessibilityService() {
    @Inject
    lateinit var detector: DefaultAccessibilityForegroundAppDetector

    @Inject
    lateinit var appLaunchMonitor: AppLaunchMonitor

    @Inject
    lateinit var userDataRepository: UserDataRepository

    @Inject
    lateinit var launchTransitionNotifier: LaunchTransitionNotifier

    private var scope: CoroutineScope? = null

    private var classifier: ForegroundWindowClassifier? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        classifier = ForegroundWindowClassifier(this)

        scope?.cancel()

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { newScope ->
            newScope.launch {
                userDataRepository.userData
                    .map { it.appLaunchDetection == AppLaunchDetection.Accessibility }
                    .distinctUntilChanged()
                    .collectLatest { selected ->
                        // Cancelled (and therefore reverted) the moment another method is picked.
                        if (selected) {
                            appLaunchMonitor.run(
                                detector = detector,
                                onTransition = launchTransitionNotifier::onTransition,
                            )
                        }
                    }
            }
        }

        _isConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val windowEvent = event ?: return

        if (windowEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        classifier
            ?.foregroundPackageOrNull(windowEvent.packageName, windowEvent.className)
            ?.let(detector::onForegroundPackage)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        tearDown()

        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDown()

        super.onDestroy()
    }

    private fun tearDown() {
        scope?.cancel()
        scope = null
        classifier = null

        detector.onServiceDisconnected()

        _isConnected.value = false
    }

    companion object {
        private val _isConnected = MutableStateFlow(false)

        /** `true` while the system has this service bound and running. */
        val isConnected = _isConnected.asStateFlow()
    }
}
