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

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.android.geto.domain.common.dispatcher.Dispatcher
import com.android.geto.domain.common.dispatcher.GetoDispatchers
import com.android.geto.domain.framework.UsageStatsForegroundAppDetector
import com.android.geto.domain.usecase.detection.ResumedActivityTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Polls `UsageStatsManager` for activity events, but only while the screen is on.
 *
 * The screen state comes from the `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` broadcasts, so while
 * the screen is off nothing runs at all: no timer, no system calls. When it comes back on, the
 * apps that are still in front report a fresh "resumed" event and are picked up again.
 */
@Singleton
internal class DefaultUsageStatsForegroundAppDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(GetoDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : UsageStatsForegroundAppDetector {

    @OptIn(ExperimentalCoroutinesApi::class)
    override val foregroundPackages: Flow<Set<String>> = flow {
        // The first time there is no history to go on, so look back far enough to learn what is
        // already open. After the screen has been off, only the last moments matter.
        var lookbackMillis = INITIAL_LOOKBACK_MILLIS

        emitAll(
            interactiveStates().flatMapLatest { interactive ->
                if (interactive) {
                    pollForegroundPackages(lookbackMillis)
                } else {
                    lookbackMillis = SCREEN_ON_LOOKBACK_MILLIS

                    flowOf(emptySet<String>())
                }
            },
        )
    }.distinctUntilChanged().flowOn(ioDispatcher)

    /** `true` while the screen is on. Starts with the current state. */
    private fun interactiveStates(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> trySend(true)
                    Intent.ACTION_SCREEN_OFF -> trySend(false)
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Read after registering, so a change in between cannot be missed.
        trySend(context.getSystemService(PowerManager::class.java)?.isInteractive != false)

        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    @Suppress("InlinedApi")
    private fun pollForegroundPackages(lookbackMillis: Long): Flow<Set<String>> = flow {
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)

        if (usageStatsManager == null) {
            emit(emptySet())

            return@flow
        }

        val tracker = ResumedActivityTracker()
        val event = UsageEvents.Event()

        var queryFrom = System.currentTimeMillis() - lookbackMillis
        var hasUsageAccess = false
        var accessCheckedAt = 0L

        while (true) {
            // The permission is cheap to check but still a call into the system, and it changes
            // rarely, so it is not worth doing on every tick.
            val elapsed = SystemClock.elapsedRealtime()

            if (!hasUsageAccess || elapsed - accessCheckedAt >= ACCESS_RECHECK_MILLIS) {
                hasUsageAccess = AppLaunchDetectionSetup.hasUsageAccess(context)
                accessCheckedAt = elapsed
            }

            val now = System.currentTimeMillis()

            val events = if (hasUsageAccess) {
                try {
                    usageStatsManager.queryEvents(queryFrom, now)
                } catch (_: SecurityException) {
                    // Access was revoked since the last check.
                    hasUsageAccess = false

                    null
                }
            } else {
                null
            }

            if (events == null) {
                tracker.clear()
                emit(emptySet())

                // When access is granted, learn the current state from scratch.
                queryFrom = now - lookbackMillis

                delay(PERMISSION_RECHECK_MILLIS)

                continue
            }

            while (events.hasNextEvent()) {
                events.getNextEvent(event)

                val packageName = event.packageName ?: continue

                when (event.eventType) {
                    // Same value as MOVE_TO_FOREGROUND, which is what Android 9 and below send.
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        tracker.onActivityResumed(packageName, event.className)
                    }

                    // 2 is MOVE_TO_BACKGROUND before Android 10. ACTIVITY_DESTROYED is a hidden
                    // constant; it is simply never matched if this device doesn't report it.
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    ACTIVITY_DESTROYED,
                    -> {
                        tracker.onActivityInactive(packageName, event.className)
                    }

                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> tracker.clear()
                }
            }

            queryFrom = now - OVERLAP_MILLIS

            emit(tracker.foregroundPackages)

            delay(POLL_MILLIS)
        }
    }

    private companion object {
        const val POLL_MILLIS = 1_000L
        const val PERMISSION_RECHECK_MILLIS = 5_000L
        const val ACCESS_RECHECK_MILLIS = 10_000L
        const val OVERLAP_MILLIS = 2_000L
        const val INITIAL_LOOKBACK_MILLIS = 60 * 60 * 1_000L
        const val SCREEN_ON_LOOKBACK_MILLIS = 10_000L

        /** `UsageEvents.Event.ACTIVITY_DESTROYED`, which is not in the public SDK. */
        const val ACTIVITY_DESTROYED = 24
    }
}
