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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock

/**
 * `TYPE_WINDOW_STATE_CHANGED` fires for far more than "an app came to the front": dialogs,
 * popups, the keyboard, the notification shade, permission prompts. Taking every event at face
 * value would make an app look closed the moment a dialog from somewhere else appeared, and
 * because a window underneath does not get a new event when the one above it goes away, it
 * would never look open again.
 *
 * So an event only counts when both of these hold:
 *  1. The class name is a real `Activity` (dialogs, popups and IME windows report view or
 *     window classes, which do not resolve as activities).
 *  2. The package is something the user launches: it has a launcher entry, or it is a home
 *     screen (which is how "the user went home" is noticed). That filters out system UI,
 *     permission controller, the resolver/chooser and similar transient owners.
 *
 * Main thread only; the caches are not synchronised.
 */
internal class ForegroundWindowClassifier(context: Context) {
    private val packageManager = context.packageManager

    private val cache = TtlCache(ttlMillis = CACHE_TTL_MILLIS)

    private var homePackages: Set<String> = emptySet()
    private var homePackagesLoadedAt: Long? = null

    /** The package to treat as being in front, or `null` to ignore this event. */
    fun foregroundPackageOrNull(packageName: CharSequence?, className: CharSequence?): String? {
        val pkg = packageName?.toString() ?: return null
        val cls = className?.toString() ?: return null

        if (!cache.get("activity:$pkg/$cls") { isActivity(pkg, cls) }) return null

        if (!cache.get("user-facing:$pkg") { isUserFacing(pkg) }) return null

        return pkg
    }

    private fun isActivity(packageName: String, className: String): Boolean = try {
        val component = ComponentName(packageName, className)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(component, 0)
        }

        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun isUserFacing(packageName: String): Boolean = packageManager.getLaunchIntentForPackage(packageName) != null ||
        packageName in loadHomePackages()

    private fun loadHomePackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        val loadedAt = homePackagesLoadedAt

        if (loadedAt == null || now - loadedAt > CACHE_TTL_MILLIS) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }

            homePackages = resolved.map { it.activityInfo.packageName }.toSet()
            homePackagesLoadedAt = now
        }

        return homePackages
    }

    /** Tiny cache so a burst of events does not turn into a burst of binder calls. */
    private class TtlCache(private val ttlMillis: Long) {
        private val entries = HashMap<String, Pair<Boolean, Long>>()

        fun get(key: String, compute: () -> Boolean): Boolean {
            val now = SystemClock.elapsedRealtime()
            val cached = entries[key]

            if (cached != null && now - cached.second <= ttlMillis) return cached.first

            if (entries.size >= MAX_ENTRIES) entries.clear()

            return compute().also { entries[key] = it to now }
        }
    }

    private companion object {
        const val CACHE_TTL_MILLIS = 60_000L
        const val MAX_ENTRIES = 256
    }
}
