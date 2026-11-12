import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.Test

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
        require(versionCode in 1..999_999) { "Release version must fit the six-digit sequence" }
        versionName = "v" + versionCode.toString().padStart(6, '0')
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
        unitTests.all {
            it.testLogging.exceptionFormat = TestExceptionFormat.FULL
            // Desired contracts for confirmed Bonanza defects are deliberately red until fixed.
            // Keep them executable without teaching the regression suite to expect broken behavior.
            val knownDefects = providers.gradleProperty("bonanzaKnownDefects").isPresent
            it.useJUnit {
                val category = "com.evanaronson.linguize.testing.KnownBonanzaDefect"
                if (knownDefects) includeCategories(category) else excludeCategories(category)
            }
            if (knownDefects) {
                it.reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/${it.name}-known-defects"))
                it.reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/${it.name}-known-defects"))
                it.binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/${it.name}-known-defects/binary"))
            }
        }
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
    testImplementation(libs.kotlinx.coroutines.test)
}

// Offline eval tooling reuses the app's compiled preparation code. It does not run the test
// suite or add a second production module. The Python bridge strips provider credentials.
tasks.register<JavaExec>("prepareCheckEval") {
    group = "verification"
    description = "Export exact app/base check requests for an evaluation JSONL dataset"
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    classpath = unitTests.classpath
    dependsOn("compileDebugUnitTestKotlin")
    mainClass.set("com.evanaronson.linguize.eval.CheckPreparationExport")
    val cases = providers.gradleProperty("checkEvalCases").getOrElse(
        rootProject.layout.projectDirectory.file("eval/cases.jsonl").asFile.absolutePath,
    )
    val output = providers.gradleProperty("checkEvalOutput").getOrElse(
        layout.buildDirectory.file("eval/prepared-checks.jsonl").get().asFile.absolutePath,
    )
    val mode = providers.gradleProperty("checkEvalMode").getOrElse("app")
    val assets = layout.projectDirectory.dir("src/main/assets").asFile
    inputs.file(cases)
    inputs.dir(assets)
    inputs.property("mode", mode)
    outputs.file(output)
    args(assets.absolutePath, cases, output, mode)
}
