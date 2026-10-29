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

/**
 * Turns a stream of "activity resumed" / "activity no longer resumed" events into the set of
 * packages that are in the foreground.
 *
 * `UsageStatsManager` reports transitions per activity, not per app. Moving between two screens
 * of the same app produces `A paused, B resumed, A stopped` and the first event briefly leaves
 * the app with nothing resumed. Tracking resumed activities per package, rather than toggling a
 * single "current app", means that dip only happens when the *last* resumed activity of a
 * package goes away. It also handles split-screen, where two apps are resumed at once.
 *
 * Not thread safe; confine to one coroutine.
 */
class ResumedActivityTracker {
    private val resumedClassNamesByPackage = LinkedHashMap<String, MutableSet<String>>()

    val foregroundPackages: Set<String>
        get() = resumedClassNamesByPackage.keys.toSet()

    fun onActivityResumed(packageName: String, className: String?) {
        resumedClassNamesByPackage.getOrPut(packageName) {
            LinkedHashSet()
        }.add(className.orEmpty())
    }

    /** Covers paused, stopped and destroyed: all mean the activity is not the one in front. */
    fun onActivityInactive(packageName: String, className: String?) {
        val classNames = resumedClassNamesByPackage[packageName] ?: return

        classNames.remove(className.orEmpty())

        if (classNames.isEmpty()) resumedClassNamesByPackage.remove(packageName)
    }

    /**
     * Forget everything. Used when the screen turns off: nothing is in front of the user any more,
     * and whatever is still genuinely visible gets a fresh "resumed" event when the screen is back.
     * This also stops a missed "paused" event (a crash, for example) from pinning an app to the
     * foreground forever.
     */
    fun clear() {
        resumedClassNamesByPackage.clear()
    }
}
