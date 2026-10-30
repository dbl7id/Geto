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
package com.android.geto.feature.settings

import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.geto.designsystem.theme.supportsDynamicTheming
import com.android.geto.domain.model.AppLaunchDetection
import com.android.geto.domain.model.Theme
import com.android.geto.domain.model.UserData
import com.android.geto.feature.settings.dialog.AppLaunchDetectionDialog
import com.android.geto.feature.settings.dialog.ThemeDialog
import com.android.geto.launchmonitor.AppLaunchDetectionSetup
import com.android.geto.service.SettingsObserverService

@Composable
internal fun SettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settingsUiState by viewModel.settingsUiState.collectAsStateWithLifecycle()

    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()

    SettingsScreen(
        modifier = modifier,
        settingsUiState = settingsUiState,
        isServiceRunning = isServiceRunning,
        onUpdateTheme = viewModel::updateTheme,
        onUpdateDynamicTheme = viewModel::updateDynamicTheme,
        onUpdateAppLaunchDetection = viewModel::updateAppLaunchDetection,
    )
}

@VisibleForTesting
@Composable
internal fun SettingsScreen(
    modifier: Modifier = Modifier,
    settingsUiState: SettingsUiState,
    isServiceRunning: Boolean,
    onUpdateTheme: (Theme) -> Unit,
    onUpdateDynamicTheme: (Boolean) -> Unit,
    onUpdateAppLaunchDetection: (AppLaunchDetection) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        when (settingsUiState) {
            SettingsUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            is SettingsUiState.Success -> {
                Success(
                    userData = settingsUiState.userData,
                    isServiceRunning = isServiceRunning,
                    onUpdateDynamicTheme = onUpdateDynamicTheme,
                    onUpdateTheme = onUpdateTheme,
                    onUpdateAppLaunchDetection = onUpdateAppLaunchDetection,
                )
            }
        }
    }
}

@Composable
private fun Success(
    modifier: Modifier = Modifier,
    userData: UserData,
    isServiceRunning: Boolean,
    onUpdateDynamicTheme: (Boolean) -> Unit,
    onUpdateTheme: (Theme) -> Unit,
    onUpdateAppLaunchDetection: (AppLaunchDetection) -> Unit,
) {
    val context = LocalContext.current

    var showThemeDialog by remember { mutableStateOf(false) }

    var selectedTheme by remember {
        mutableIntStateOf(
            Theme.entries.indexOf(
                userData.theme,
            ),
        )
    }

    var showAppLaunchDetectionDialog by remember { mutableStateOf(false) }

    var selectedAppLaunchDetection by remember {
        mutableIntStateOf(AppLaunchDetection.entries.indexOf(userData.appLaunchDetection))
    }

    // Permissions are granted on other screens, so look again whenever this one comes back.
    var isDetectionReady by remember { mutableStateOf(true) }

    LifecycleResumeEffect(userData.appLaunchDetection) {
        isDetectionReady = AppLaunchDetectionSetup.isReady(context, userData.appLaunchDetection)

        onPauseOrDispose { }
    }

    Column(modifier = modifier.fillMaxSize()) {
        DynamicThemeSetting(
            dynamicTheme = userData.dynamicTheme,
            onUpdateDynamicTheme = onUpdateDynamicTheme,
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsColumn(
            title = stringResource(R.string.theme),
            subtitle = userData.theme.getTitle(),
            onClick = {
                showThemeDialog = true
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        val appLaunchDetectionActive = stringResource(R.string.app_launch_detection_active)

        SettingsColumn(
            title = stringResource(R.string.app_launch_detection),
            subtitle = when {
                userData.appLaunchDetection == AppLaunchDetection.Off -> {
                    stringResource(R.string.app_launch_detection_off)
                }

                isDetectionReady -> {
                    "${userData.appLaunchDetection.getTitle()} · $appLaunchDetectionActive"
                }

                else -> stringResource(R.string.app_launch_detection_needs_setup)
            },
            onClick = {
                if (userData.appLaunchDetection != AppLaunchDetection.Off && !isDetectionReady) {
                    // Finish what the last choice started instead of making the user pick again.
                    AppLaunchDetectionSetup.activate(context, userData.appLaunchDetection)
                        ?.let { context.startActivity(it) }
                } else {
                    selectedAppLaunchDetection =
                        AppLaunchDetection.entries.indexOf(userData.appLaunchDetection)

                    showAppLaunchDetectionDialog = true
                }
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsColumn(
            title = stringResource(R.string.settings_observer_service),
            subtitle = if (isServiceRunning) {
                stringResource(R.string.stop_service)
            } else {
                stringResource(R.string.start_service)
            },
            onClick = {
                val intent = Intent(context, SettingsObserverService::class.java)

                if (isServiceRunning) {
                    context.stopService(intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            },
        )
    }

    if (showAppLaunchDetectionDialog) {
        AppLaunchDetectionDialog(
            onDismissRequest = {
                showAppLaunchDetectionDialog = false
            },
            selected = selectedAppLaunchDetection,
            onSelect = { selectedAppLaunchDetection = it },
            onChangeClick = {
                val detection = AppLaunchDetection.entries[selectedAppLaunchDetection]

                onUpdateAppLaunchDetection(detection)

                AppLaunchDetectionSetup.activate(context, detection)?.let { context.startActivity(it) }

                showAppLaunchDetectionDialog = false
            },
        )
    }

    if (showThemeDialog) {
        ThemeDialog(
            onDismissRequest = {
                showThemeDialog = false
            },
            selected = selectedTheme,
            onSelect = { selectedTheme = it },
            onChangeClick = {
                onUpdateTheme(Theme.entries[selectedTheme])

                showThemeDialog = false
            },
        )
    }
}

@Composable
private fun DynamicThemeSetting(
    modifier: Modifier = Modifier,
    dynamicTheme: Boolean,
    onUpdateDynamicTheme: (Boolean) -> Unit,
) {
    if (supportsDynamicTheming()) {
        Row(
            modifier = modifier
                .clickable {
                    onUpdateDynamicTheme(!dynamicTheme)
                }
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dynamic_theme),
                    style = MaterialTheme.typography.bodyLarge,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.available_on_android_12),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Switch(
                checked = dynamicTheme,
                onCheckedChange = onUpdateDynamicTheme,
            )
        }
    }
}

@Composable
private fun SettingsColumn(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun Theme.getTitle() = when (this) {
    Theme.FOLLOW_SYSTEM -> stringResource(R.string.follow_system)
    Theme.LIGHT -> stringResource(R.string.light)
    Theme.DARK -> stringResource(R.string.dark)
}

@Composable
internal fun AppLaunchDetection.getTitle() = when (this) {
    AppLaunchDetection.Off -> stringResource(R.string.app_launch_detection_off)
    AppLaunchDetection.Accessibility -> stringResource(R.string.app_launch_detection_accessibility)
    AppLaunchDetection.UsageStats -> stringResource(R.string.app_launch_detection_usage_stats)
}

@Composable
internal fun AppLaunchDetection.getDescription() = when (this) {
    AppLaunchDetection.Off -> stringResource(R.string.app_launch_detection_off_description)
    AppLaunchDetection.Accessibility -> stringResource(R.string.app_launch_detection_accessibility_description)
    AppLaunchDetection.UsageStats -> stringResource(R.string.app_launch_detection_usage_stats_description)
}
