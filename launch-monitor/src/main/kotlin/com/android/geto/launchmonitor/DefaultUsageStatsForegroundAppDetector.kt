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
import android.content.Context
import android.os.PowerManager
import com.android.geto.domain.common.dispatcher.Dispatcher
import com.android.geto.domain.common.dispatcher.GetoDispatchers
import com.android.geto.domain.framework.UsageStatsForegroundAppDetector
import com.android.geto.domain.usecase.detection.ResumedActivityTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Polls `UsageStatsManager.queryEvents` and feeds the events through a [ResumedActivityTracker].
 *
 * The things that make naive usage stats monitoring flaky, and what is done about each:
 *  - Activity hops inside one app look like the app closing: tracked per activity, see the tracker.
 *  - Events can show up in the query a moment after they happened: every query re-reads the last
 *    [OVERLAP_MILLIS]. Replaying events in order is idempotent, and the result is only published
 *    once per poll, so nothing flickers.
 *  - Without usage access the query silently returns nothing, which looks exactly like "no app is
 *    open": the permission is checked on every poll and an empty set is published until granted.
 *  - The state when monitoring starts is unknown: the first query looks back [INITIAL_LOOKBACK_MILLIS].
 *  - A missed "paused" event pins an app to the foreground: screen off wipes the state.
 *  - Polling with the screen off is pointless: it only checks `isInteractive`, much less often.
 */
@Singleton
internal class DefaultUsageStatsForegroundAppDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(GetoDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : UsageStatsForegroundAppDetector {

    @Suppress("InlinedApi")
    override val foregroundPackages: Flow<Set<String>> = flow {
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val tracker = ResumedActivityTracker()
        val event = UsageEvents.Event()

        var queryFrom = System.currentTimeMillis() - INITIAL_LOOKBACK_MILLIS

        while (true) {
            val now = System.currentTimeMillis()

            if (usageStatsManager == null || !AppLaunchDetectionSetup.hasUsageAccess(context)) {
                tracker.clear()
                emit(emptySet())

                // When access is granted, learn the current state from scratch.
                queryFrom = now - INITIAL_LOOKBACK_MILLIS

                delay(PERMISSION_RECHECK_MILLIS)
            } else if (powerManager?.isInteractive == false) {
                tracker.clear()
                emit(emptySet())

                // Anything that happened while off is irrelevant: the apps that are still there
                // report a fresh "resumed" when the screen comes back.
                queryFrom = now

                delay(SCREEN_OFF_POLL_MILLIS)
            } else {
                val events = usageStatsManager.queryEvents(queryFrom, now)

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
    }.distinctUntilChanged().flowOn(ioDispatcher)

    private companion object {
        const val POLL_MILLIS = 1_000L
        const val SCREEN_OFF_POLL_MILLIS = 2_000L
        const val PERMISSION_RECHECK_MILLIS = 5_000L
        const val OVERLAP_MILLIS = 2_000L
        const val INITIAL_LOOKBACK_MILLIS = 60 * 60 * 1_000L

        /** `UsageEvents.Event.ACTIVITY_DESTROYED`, which is not in the public SDK. */
        const val ACTIVITY_DESTROYED = 24
    }
}
