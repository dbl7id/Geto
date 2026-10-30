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

plugins {
    alias(libs.plugins.com.android.geto.application)
    alias(libs.plugins.com.android.geto.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.android.geto"

    defaultConfig {
        applicationId = "com.android.geto"
        versionCode = 173
        versionName = "1.17.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Only set up when the environment provides a keystore (see .github/workflows/Release-APK.yml).
    // Without it the release build stays unsigned, exactly as before.
    val releaseKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("SIGNING_KEY_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")

            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(projects.broadcastReceiver)
    implementation(projects.common)
    implementation(projects.designSystem)
    implementation(projects.data.repository)
    implementation(projects.domain.common)
    implementation(projects.domain.framework)
    implementation(projects.domain.repository)
    implementation(projects.domain.useCase)

    implementation(projects.feature.apps)
    implementation(projects.feature.appSettings)
    implementation(projects.feature.home)
    implementation(projects.feature.settings)

    implementation(projects.framework.assetManager)
    implementation(projects.framework.drawable)
    implementation(projects.framework.launcherApps)
    implementation(projects.framework.notificationManager)
    implementation(projects.framework.packageManager)
    implementation(projects.framework.secureSettings)
    implementation(projects.framework.shortcutManager)
    implementation(projects.launchMonitor)
    implementation(projects.ui)

    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
}
