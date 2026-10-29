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

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumedActivityTrackerTest {
    private val tracker = ResumedActivityTracker()

    @Test
    fun resumedActivityMakesItsPackageForeground() {
        tracker.onActivityResumed("com.bank", "com.bank.Main")

        assertEquals(setOf("com.bank"), tracker.foregroundPackages)
    }

    @Test
    fun pausedActivityRemovesItsPackage() {
        tracker.onActivityResumed("com.bank", "com.bank.Main")
        tracker.onActivityInactive("com.bank", "com.bank.Main")

        assertEquals(emptySet<String>(), tracker.foregroundPackages)
    }

    @Test
    fun movingBetweenScreensOfOneAppNeverDropsIt() {
        // The system reports "B resumed" before "A stopped", so the app is never empty.
        tracker.onActivityResumed("com.bank", "com.bank.Login")
        tracker.onActivityInactive("com.bank", "com.bank.Login")
        tracker.onActivityResumed("com.bank", "com.bank.Home")

        assertEquals(setOf("com.bank"), tracker.foregroundPackages)

        tracker.onActivityResumed("com.bank", "com.bank.Pay")
        tracker.onActivityInactive("com.bank", "com.bank.Home")

        assertEquals(setOf("com.bank"), tracker.foregroundPackages)
    }

    @Test
    fun twoAppsCanBeForegroundAtOnce() {
        tracker.onActivityResumed("com.bank", "com.bank.Main")
        tracker.onActivityResumed("com.notes", "com.notes.Main")

        assertEquals(setOf("com.bank", "com.notes"), tracker.foregroundPackages)

        tracker.onActivityInactive("com.notes", "com.notes.Main")

        assertEquals(setOf("com.bank"), tracker.foregroundPackages)
    }

    @Test
    fun repeatedResumeIsIdempotent() {
        tracker.onActivityResumed("com.bank", "com.bank.Main")
        tracker.onActivityResumed("com.bank", "com.bank.Main")
        tracker.onActivityInactive("com.bank", "com.bank.Main")

        assertEquals(emptySet<String>(), tracker.foregroundPackages)
    }

    @Test
    fun unknownOrNullClassNamesAreHandled() {
        tracker.onActivityInactive("com.nothing", "x")
        tracker.onActivityResumed("com.bank", null)

        assertEquals(setOf("com.bank"), tracker.foregroundPackages)

        tracker.onActivityInactive("com.bank", null)

        assertEquals(emptySet<String>(), tracker.foregroundPackages)
    }

    @Test
    fun clearForgetsEverything() {
        tracker.onActivityResumed("com.bank", "com.bank.Main")
        tracker.onActivityResumed("com.notes", "com.notes.Main")

        tracker.clear()

        assertEquals(emptySet<String>(), tracker.foregroundPackages)
    }
}
