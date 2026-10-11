import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.evanaronson.linguize"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.evanaronson.linguize"
        // Galaxy S21 runs Android 15; nothing older needs supporting.
        minSdk = 30
        targetSdk = 36
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toInt() ?: 1
        versionName = "0.1.$versionCode"
    }

    // Release builds come out unsigned. CI signs the APK afterwards, in a separate job that runs
    // only apksigner, so neither Gradle nor any build dependency ever sees the release key.
    buildTypes {
        release {
            // Shrunk release builds start noticeably faster than debug builds,
            // which matters when every check may cold-start the app.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // For trying a release build on a phone: ./gradlew assembleRelease -PdebugSignedRelease
            // signs it with the local debug key instead.
            if (providers.gradleProperty("debugSignedRelease").isPresent) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric tests read the app's resources and manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
