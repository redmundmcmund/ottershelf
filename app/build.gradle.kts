import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.LocalDate
import java.util.Properties

// AGP 9 compiles Kotlin itself (built-in Kotlin): never apply org.jetbrains.kotlin.android here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Release signing (ARCHITECTURE.md, "Release build (R8)"): the key and its passwords never enter
// the repository. They are read from a properties file outside it, named by the Gradle property
// `ottershelf.keystore` or else ~/.ottershelf/keystore.properties: storeFile (relative to that
// file's folder, or absolute), storePassword, keyAlias, keyPassword. No file, or one missing a
// value: assembleRelease makes an unsigned APK. A missing value is named in a warning on every
// build instead of failing it, so the debug build and the tests still run.
val releaseKeystoreFile: File = providers.gradleProperty("ottershelf.keystore").orNull?.let { rootDir.resolve(it) }
    ?: File(System.getProperty("user.home"), ".ottershelf/keystore.properties")
val releaseKeystore: Properties? = releaseKeystoreFile.takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
    ?.takeIf { keys ->
        val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").filter { keys.getProperty(it).isNullOrBlank() }
        if (missing.isNotEmpty()) logger.warn("Release build left unsigned: ${missing.joinToString()} missing from $releaseKeystoreFile")
        missing.isEmpty()
    }

fun releaseKeystoreValue(key: String): String = releaseKeystore!!.getProperty(key)

// The package of the dev and debug builds (one install that either replaces with `install -r`).
// Unset, it is io.github.ottershelf.dev. A device that already runs a dev build under another
// package keeps that install (its sign-in, downloads and queued changes) by naming the package in
// `ottershelf.devApplicationId`: in local.properties (not committed), else as a Gradle property
// (-P, or ~/.gradle/gradle.properties).
val devApplicationId: String = run {
    val local = rootProject.file("local.properties").takeIf { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
        ?.getProperty("ottershelf.devApplicationId")
    (local ?: providers.gradleProperty("ottershelf.devApplicationId").orNull)?.trim()?.takeIf { it.isNotEmpty() }
        ?: "io.github.ottershelf.dev"
}

// The About screen's legal notices (BookOrbit's ADDITIONAL_TERMS.md, sections 1-3): when this
// modified version was last changed (the last commit's date; today outside a git checkout) and
// where its complete source is (this version's tag).
val modifiedDate: String = runCatching {
    providers.exec { commandLine("git", "log", "-1", "--format=%cs") }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: LocalDate.now().toString()
val sourceRepository = "https://github.com/redmundmcmund/ottershelf"

android {
    namespace = "io.github.ottershelf"
    compileSdk = 37

    defaultConfig {
        // The release package. The dev and debug builds take devApplicationId (androidComponents below).
        applicationId = "io.github.ottershelf"
        minSdk = 35
        targetSdk = 36
        versionCode = 13
        versionName = "0.1.12"
        // On-device tests (src/androidTest): run them by hand with `am instrument`, never with
        // connectedAndroidTest, which uninstalls the app afterwards (ARCHITECTURE.md, "Device tests").
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 64-bit ARM only, as every phone this app is tested on. Tesseract's and
        // zxing-cpp's native libraries would otherwise add about 26 MB of x86 and 32-bit copies.
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "MODIFIED_DATE", "\"$modifiedDate\"")
        // The project's home page (the Wikimedia User-Agent's contact).
        buildConfigField("String", "PROJECT_URL", "\"$sourceRepository\"")
        buildConfigField("String", "SOURCE_URL", "\"$sourceRepository/tree/v$versionName\"")
        buildConfigField("String", "ADDITIONAL_TERMS_URL", "\"$sourceRepository/blob/v$versionName/ADDITIONAL_TERMS.md\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystoreFile.parentFile.resolve(releaseKeystoreValue("storeFile"))
                storePassword = releaseKeystoreValue("storePassword")
                keyAlias = releaseKeystoreValue("keyAlias")
                keyPassword = releaseKeystoreValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs as devApplicationId, next to a release build; its name ("Ottershelf (dev)") is
            // in src/debug/res. Only for tests and devtools: day to day, the dev build below.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed only once the keystore file above exists. Choose the key with care before the
            // first release install: changing keys later means uninstalling (and losing downloads).
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        // The day-to-day test build: release's R8 and resource shrinking, not debuggable, but the
        // debug build's package (devApplicationId) and key, so it installs over "Ottershelf (dev)"
        // and keeps its sign-in and downloads (ARCHITECTURE.md, "Build types"). Its name is in
        // src/dev/res. Always the debug key, never the release keystore. Tests stay on debug
        // (testBuildType).
        create("dev") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric and Roborazzi need the merged resources and manifest.
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "1g"
                // Robolectric's SDK 36 sandbox reaches into FileDescriptor internals on JDK 17+.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
                // Renders shadows and elevation like a device does in screenshot tests.
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                // BaselineProfileTest reads the profile from src/main: run again when only it changes.
                // files(), not file(): a missing file is allowed, so deleting the profile (and its
                // test) can't break every test task.
                it.inputs.files("src/main/baseline-prof.txt").withPropertyName("baselineProfile")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            }
        }
    }
}

// The dev and debug builds share one package, devApplicationId (above); the device tests' APK is
// that package plus ".test".
androidComponents {
    onVariants { variant ->
        if (variant.buildType == "debug" || variant.buildType == "dev") {
            variant.applicationId.set(devApplicationId)
            variant.deviceTests.values.forEach { it.applicationId.set("$devApplicationId.test") }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

roborazzi {
    // Default location, spelled out because ARCHITECTURE.md points at it.
    outputDir.set(file("build/outputs/roborazzi"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    // Quotes (feature.quotes): photograph a page and read its text on the device.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.tesseract4android)
    // ISBN scanning (feature.scan): EAN-13 barcodes read on the device by zxing-cpp.
    implementation(libs.zxing.cpp.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Registers the ComponentActivity that Compose/Roborazzi tests launch under Robolectric.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    // On-device tests: the reader's WebView, the barcode reader (zxing-cpp), the page reading
    // (Tesseract), Look up's client and the library grid's pinch on the phone itself
    // (src/androidTest).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
