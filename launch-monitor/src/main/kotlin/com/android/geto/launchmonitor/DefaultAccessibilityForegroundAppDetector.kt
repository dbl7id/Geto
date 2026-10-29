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

import com.android.geto.domain.framework.AccessibilityForegroundAppDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The accessibility service is created and destroyed by the system, so it cannot own the
 * detector. It pushes what it sees in here instead, and the monitor reads from this singleton.
 *
 * Unlike the usage stats detector this only ever reports one package: the owner of the most
 * recent user-facing window. That is enough to answer "is app X in front?".
 */
@Singleton
class DefaultAccessibilityForegroundAppDetector @Inject constructor() : AccessibilityForegroundAppDetector {
    private val _foregroundPackages = MutableStateFlow<Set<String>>(emptySet())

    override val foregroundPackages: Flow<Set<String>> = _foregroundPackages.asStateFlow()

    fun onForegroundPackage(packageName: String) {
        _foregroundPackages.value = setOf(packageName)
    }

    fun onServiceDisconnected() {
        _foregroundPackages.value = emptySet()
    }
}
