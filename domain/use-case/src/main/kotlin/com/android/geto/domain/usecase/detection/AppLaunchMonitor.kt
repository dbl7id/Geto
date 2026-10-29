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
package com.android.geto.domain.usecase.detection

import com.android.geto.domain.framework.AppliedSettingsStore
import com.android.geto.domain.framework.ForegroundAppDetector
import com.android.geto.domain.model.AppSettingsResult
import com.android.geto.domain.repository.AppSettingsRepository
import com.android.geto.domain.usecase.ApplyAppSettingsUseCase
import com.android.geto.domain.usecase.RevertAppSettingsUseCase
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

/**
 * Applies an app's settings when it comes to the foreground and reverts them once it has been
 * gone for a while. Where the foreground information comes from is up to the [ForegroundAppDetector].
 *
 * Guarantees worth knowing about:
 *  - Applying is immediate. Reverting is delayed by `revertDelayMillis` and cancelled if the app
 *    comes back, so hopping to an SMS app for an OTP code does not flip settings back and forth.
 *  - Only settings this monitor applied are ever reverted. Starting the monitor does not write
 *    anything for apps that were never opened.
 *  - What was applied is persisted, so a process death while an app was open does not leave
 *    settings stuck: the next [run] picks the revert up.
 *  - Cancelling [run] reverts whatever is still applied.
 */
class AppLaunchMonitor @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val applyAppSettingsUseCase: ApplyAppSettingsUseCase,
    private val revertAppSettingsUseCase: RevertAppSettingsUseCase,
    private val appliedSettingsStore: AppliedSettingsStore,
) {
    data class Transition(
        val componentName: String,
        val kind: Kind,
        val result: AppSettingsResult,
    ) {
        enum class Kind { Applied, Reverted }
    }

    private sealed interface Event {
        data class Targets(val componentNamesByPackage: Map<String, Set<String>>) : Event

        data class Active(val packageName: String, val active: Boolean) : Event
    }

    /** Suspends until cancelled. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(
        detector: ForegroundAppDetector,
        revertDelayMillis: Long = DEFAULT_REVERT_DELAY_MILLIS,
        onTransition: suspend (Transition) -> Unit = {},
    ): Unit = coroutineScope {
        // One upstream collection no matter how many packages we watch.
        val foreground = detector.foregroundPackages.stateIn(
            scope = this,
            started = SharingStarted.Eagerly,
            initialValue = emptySet(),
        )

        // Package -> its components that have at least one enabled setting.
        val targets: Flow<Map<String, Set<String>>> = appSettingsRepository.appSettingsFlow
            .map { appSettings ->
                appSettings
                    .filter { it.enabled }
                    .groupBy(keySelector = {
                        it.componentName.packageName()
                    }, valueTransform = { it.componentName })
                    .mapValues { it.value.toSet() }
            }
            .distinctUntilChanged()

        val events: Flow<Event> = targets.flatMapLatest { componentNamesByPackage ->
            flow {
                emit(Event.Targets(componentNamesByPackage))

                emitAll(
                    componentNamesByPackage.keys.map { packageName ->
                        foreground
                            .map { packageName in it }
                            .distinctUntilChanged()
                            .holdFalseFor(revertDelayMillis)
                            .map { active -> Event.Active(packageName, active) }
                    }.merge(),
                )
            }
        }

        // Everything below runs in this one collector, so none of it needs locking.
        val applied = appliedSettingsStore.getAppliedComponentNames().toMutableSet()
        var currentTargets: Map<String, Set<String>> = emptyMap()

        suspend fun persist() = appliedSettingsStore.setAppliedComponentNames(applied.toSet())

        suspend fun notify(transition: Transition) {
            try {
                onTransition(transition)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A broken listener must not take the monitor down with it.
            }
        }

        suspend fun apply(componentName: String) {
            val result = applyAppSettingsUseCase(componentName = componentName)

            // These three mean nothing was written, so there is nothing to revert later.
            if (result != AppSettingsResult.NoPermission &&
                result != AppSettingsResult.EmptyAppSettings &&
                result != AppSettingsResult.DisabledAppSettings
            ) {
                applied += componentName
            }

            notify(Transition(componentName, Transition.Kind.Applied, result))
        }

        suspend fun revert(componentName: String) {
            val result = revertAppSettingsUseCase(componentName = componentName)

            applied -= componentName

            notify(Transition(componentName, Transition.Kind.Reverted, result))
        }

        try {
            events.collect { event ->
                when (event) {
                    is Event.Targets -> {
                        currentTargets = event.componentNamesByPackage

                        // Settings that were deleted or disabled while applied cannot be reverted
                        // (the use case refuses to touch them), so stop tracking them.
                        if (applied.retainAll { it.packageName() in currentTargets }) persist()
                    }

                    is Event.Active -> {
                        if (event.active) {
                            currentTargets[event.packageName].orEmpty().forEach { apply(it) }
                        } else {
                            val toRevert = applied.filter { it.packageName() == event.packageName }

                            toRevert.forEach { revert(it) }

                            // Two apps can touch the same key. Reverting one must not undo the
                            // other while it is still open, so put the survivors' values back.
                            if (toRevert.isNotEmpty()) applied.toList().forEach { apply(it) }
                        }

                        persist()
                    }
                }
            }
        } finally {
            // Stopping the monitor (feature switched off, accessibility service disabled) must not
            // leave settings applied with nobody left to revert them.
            withContext(NonCancellable) {
                applied.toList().forEach { revert(it) }

                persist()
            }
        }
    }

    /** `false` is held back for [delayMillis] and dropped if something newer arrives meanwhile. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Flow<Boolean>.holdFalseFor(delayMillis: Long): Flow<Boolean> =
        transformLatest { value ->
            if (!value) delay(delayMillis)

            emit(value)
        }.distinctUntilChanged()

    /** Component names are `ComponentName.flattenToString()`: `package/class`. */
    private fun String.packageName() = substringBefore('/')

    companion object {
        /**
         * Long enough to cover "go and read the OTP from the SMS app". Staying applied a little
         * long is harmless; flapping is not.
         */
        const val DEFAULT_REVERT_DELAY_MILLIS = 15_000L
    }
}
