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

import android.content.Context
import com.android.geto.domain.common.dispatcher.Dispatcher
import com.android.geto.domain.common.dispatcher.GetoDispatchers
import com.android.geto.domain.framework.AppliedSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A plain set of strings, written with `commit()` so it is on disk before the next thing that
 * could kill the process. Excluded from backups: restoring it onto another device would claim
 * settings were applied there when they were not.
 */
@Singleton
internal class SharedPreferencesAppliedSettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(GetoDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : AppliedSettingsStore {
    private val preferences by lazy {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun getAppliedComponentNames(): Set<String> = withContext(ioDispatcher) {
        // The returned set belongs to SharedPreferences and must not be modified.
        preferences.getStringSet(KEY_APPLIED, emptySet()).orEmpty().toSet()
    }

    override suspend fun setAppliedComponentNames(componentNames: Set<String>) {
        withContext(ioDispatcher) {
            preferences.edit().putStringSet(KEY_APPLIED, componentNames.toSet()).commit()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "launch_monitor"
        const val KEY_APPLIED = "applied_component_names"
    }
}
