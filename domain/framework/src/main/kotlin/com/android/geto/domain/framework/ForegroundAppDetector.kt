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
package com.android.geto.domain.framework

import kotlinx.coroutines.flow.Flow

/**
 * Source of truth for "which apps is the user looking at right now".
 *
 * Implementations are free to be cold (polling) or backed by a hot source (an accessibility
 * service); consumers must not assume anything about threading and should share the flow if
 * they need more than one collector.
 */
interface ForegroundAppDetector {
    /**
     * The package names that currently have a resumed, user-facing activity. Emits whenever the
     * set changes. An empty set means "nothing we know of is in the foreground", which includes
     * the detector not being able to see anything (for example a missing permission).
     */
    val foregroundPackages: Flow<Set<String>>
}

/** Event driven implementation backed by an accessibility service. */
interface AccessibilityForegroundAppDetector : ForegroundAppDetector

/** Polling implementation backed by `UsageStatsManager.queryEvents`. */
interface UsageStatsForegroundAppDetector : ForegroundAppDetector
