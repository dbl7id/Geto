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
import com.android.geto.domain.framework.SecureSettingsWrapper
import com.android.geto.domain.model.AppSetting
import com.android.geto.domain.model.AppSettingsResult
import com.android.geto.domain.model.SecureSetting
import com.android.geto.domain.model.SettingType
import com.android.geto.domain.repository.AppSettingsRepository
import com.android.geto.domain.usecase.ApplyAppSettingsUseCase
import com.android.geto.domain.usecase.RevertAppSettingsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchMonitorTest {
    private val bank = "com.bank/.Main"
    private val maps = "com.maps/.Main"

    private val settings = MutableStateFlow(
        listOf(
            setting(componentName = bank, key = "dev", onLaunch = "0", onRevert = "1"),
        ),
    )
    private val detector = FakeDetector()
    private val secureSettings = FakeSecureSettings()
    private val store = FakeStore()
    private val transitions = mutableListOf<AppLaunchMonitor.Transition>()

    private val monitor = AppLaunchMonitor(
        appSettingsRepository = FakeRepository(settings),
        applyAppSettingsUseCase = ApplyAppSettingsUseCase(
            FakeRepository(settings),
            secureSettings,
            Dispatchers.Default,
        ),
        revertAppSettingsUseCase = RevertAppSettingsUseCase(
            FakeRepository(settings),
            secureSettings,
            Dispatchers.Default,
        ),
        appliedSettingsStore = store,
    )

    private fun runMonitor(scope: kotlinx.coroutines.CoroutineScope): Job = scope.launch {
        monitor.run(detector = detector, revertDelayMillis = GRACE) { transitions += it }
    }

    @Test
    fun appliesImmediatelyAndRevertsOnlyAfterTheGracePeriod() = runBlocking {
        val job = runMonitor(this)

        detector.foreground.value = setOf("com.bank")
        settle()

        assertEquals("0", secureSettings.values["dev"])

        detector.foreground.value = emptySet()
        delay(GRACE / 3)

        assertEquals("still applied during grace", "0", secureSettings.values["dev"])

        delay(GRACE + SETTLE)

        assertEquals("1", secureSettings.values["dev"])
        assertEquals(setOf<String>(), store.saved)

        job.cancelAndJoinQuietly()
    }

    @Test
    fun comingBackWithinTheGracePeriodCancelsTheRevertAndDoesNotReapply() = runBlocking {
        val job = runMonitor(this)

        detector.foreground.value = setOf("com.bank")
        settle()

        detector.foreground.value = setOf("com.sms")
        delay(GRACE / 3)
        detector.foreground.value = setOf("com.bank")
        delay(GRACE * 2)

        assertEquals("0", secureSettings.values["dev"])
        assertEquals(
            listOf(
                AppLaunchMonitor.Transition(
                    bank,
                    AppLaunchMonitor.Transition.Kind.Applied,
                    AppSettingsResult.Success,
                ),
            ),
            transitions,
        )

        job.cancelAndJoinQuietly()
    }

    @Test
    fun startingTheMonitorWritesNothingForAppsThatWereNeverOpened() = runBlocking {
        val job = runMonitor(this)

        delay(GRACE * 2)

        assertTrue(secureSettings.writes.isEmpty())
        assertTrue(transitions.isEmpty())

        job.cancelAndJoinQuietly()
    }

    @Test
    fun appliedStateLeftByADeadProcessIsRevertedOnNextStart() = runBlocking {
        store.saved = setOf(bank)
        secureSettings.values["dev"] = "0"

        val job = runMonitor(this)

        delay(GRACE * 2)

        assertEquals("1", secureSettings.values["dev"])
        assertEquals(setOf<String>(), store.saved)

        job.cancelAndJoinQuietly()
    }

    @Test
    fun appliedStateFromADeadProcessIsKeptIfTheAppIsStillOpen() = runBlocking {
        store.saved = setOf(bank)
        secureSettings.values["dev"] = "0"
        detector.foreground.value = setOf("com.bank")

        val job = runMonitor(this)

        delay(GRACE * 2)

        assertEquals("0", secureSettings.values["dev"])

        job.cancelAndJoinQuietly()
    }

    @Test
    fun stoppingTheMonitorRevertsWhatIsStillApplied() = runBlocking {
        val job = runMonitor(this)

        detector.foreground.value = setOf("com.bank")
        settle()

        assertEquals("0", secureSettings.values["dev"])

        job.cancelAndJoinQuietly()

        assertEquals("1", secureSettings.values["dev"])
        assertEquals(setOf<String>(), store.saved)
    }

    @Test
    fun closingOneAppDoesNotUndoTheOtherWhenTheyShareAKey() = runBlocking {
        settings.value = listOf(
            setting(componentName = bank, key = "dev", onLaunch = "0", onRevert = "1"),
            setting(componentName = maps, key = "dev", onLaunch = "2", onRevert = "1"),
        )

        val job = runMonitor(this)

        detector.foreground.value = setOf("com.bank", "com.maps")
        settle()

        detector.foreground.value = setOf("com.maps")
        delay(GRACE + SETTLE)

        assertEquals("maps is still open, its value must win", "2", secureSettings.values["dev"])

        job.cancelAndJoinQuietly()
    }

    @Test
    fun missingPermissionIsReportedAndNothingIsTrackedForRevert() = runBlocking {
        secureSettings.throwSecurityException = true

        val job = runMonitor(this)

        detector.foreground.value = setOf("com.bank")
        settle()

        assertEquals(AppSettingsResult.NoPermission, transitions.single().result)
        assertEquals(setOf<String>(), store.saved)

        job.cancelAndJoinQuietly()
    }

    @Test
    fun disabledSettingsAreNotTargets() = runBlocking {
        settings.value = listOf(
            setting(
                componentName = bank,
                key = "dev",
                onLaunch = "0",
                onRevert = "1",
                enabled = false,
            ),
        )

        val job = runMonitor(this)

        detector.foreground.value = setOf("com.bank")
        settle()

        assertTrue(secureSettings.writes.isEmpty())

        job.cancelAndJoinQuietly()
    }

    @Test
    fun settingsAddedWhileTheMonitorRunsAreCoveredStraightAway() = runBlocking {
        settings.value = emptyList()

        val job = runMonitor(this)

        detector.foreground.value = setOf("com.bank")
        settle()

        assertTrue(secureSettings.writes.isEmpty())

        settings.value =
            listOf(setting(componentName = bank, key = "dev", onLaunch = "0", onRevert = "1"))
        settle()

        assertEquals("0", secureSettings.values["dev"])

        job.cancelAndJoinQuietly()
    }

    // ---- helpers -------------------------------------------------------------------------

    private suspend fun settle() = delay(SETTLE)

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        join()
    }

    private fun setting(
        componentName: String,
        key: String,
        onLaunch: String,
        onRevert: String,
        enabled: Boolean = true,
    ) = AppSetting(
        id = (componentName + key).hashCode(),
        enabled = enabled,
        settingType = SettingType.GLOBAL,
        componentName = componentName,
        label = key,
        key = key,
        valueOnLaunch = onLaunch,
        valueOnRevert = onRevert,
    )

    private class FakeDetector : ForegroundAppDetector {
        val foreground = MutableStateFlow<Set<String>>(emptySet())
        override val foregroundPackages: Flow<Set<String>> = foreground
    }

    private class FakeStore : AppliedSettingsStore {
        var saved: Set<String> = emptySet()
        override suspend fun getAppliedComponentNames() = saved
        override suspend fun setAppliedComponentNames(componentNames: Set<String>) {
            saved = componentNames
        }
    }

    private class FakeSecureSettings : SecureSettingsWrapper {
        val values = mutableMapOf<String, String>()
        val writes = mutableListOf<Pair<String, String>>()
        var throwSecurityException = false

        override suspend fun canWriteSecureSettings(
            settingType: SettingType,
            key: String,
            value: String,
        ): Boolean {
            if (throwSecurityException) throw SecurityException()
            values[key] = value
            writes += key to value
            return true
        }

        override suspend fun getSecureSettings(settingType: SettingType): List<SecureSetting> = emptyList()
    }

    private class FakeRepository(private val settings: MutableStateFlow<List<AppSetting>>) : AppSettingsRepository {
        override val appSettingsFlow: Flow<List<AppSetting>> = settings
        override suspend fun upsertAppSetting(appSetting: AppSetting) = Unit
        override suspend fun deleteAppSetting(appSetting: AppSetting) = Unit
        override fun getAppSettingsFlowByComponentName(
            componentName: String,
        ): Flow<List<AppSetting>> = settings.map { list -> list.filter { it.componentName == componentName } }

        override suspend fun getAppSettingsByComponentName(
            componentName: String,
        ): List<AppSetting> = settings.first().filter { it.componentName == componentName }
    }

    private companion object {
        const val GRACE = 600L
        const val SETTLE = 250L
    }
}
