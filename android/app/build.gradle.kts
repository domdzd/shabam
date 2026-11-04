plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// ---------------------------------------------------------------------------------------------
// Apple ShazamKit Android SDK
//
// The AAR is licensed by Apple and can only be downloaded after signing in to
// developer.apple.com, so it is never committed. When it is present, the real ShazamKit provider
// (src/shazamkit/kotlin) is compiled in; otherwise a stub (src/noshazamkit/kotlin) reports that
// the engine is not included in this build. See docs/RECOGNITION_PROVIDERS.md.
// ---------------------------------------------------------------------------------------------
val shazamKitAar: File = providers.gradleProperty("nanotune.shazamkitAar")
    .map { file(it) }
    .getOrElse(file("libs/shazamkit-android-release.aar"))
val shazamKitBundled: Boolean = shazamKitAar.isFile
logger.lifecycle(
    if (shazamKitBundled) "NanoTune: building WITH ShazamKit (${shazamKitAar.name})."
    else "NanoTune: ShazamKit AAR not found at ${shazamKitAar.path}; building without the ShazamKit provider."
)

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val apiBaseUrl: String = providers.gradleProperty("nanotune.apiBaseUrl").getOrElse("")
val musicBrainzContact: String = providers.gradleProperty("nanotune.musicBrainzContact")
    .getOrElse("https://github.com/domdzd/shabam")
val appVersionCode: Int = providers.environmentVariable("NANOTUNE_VERSION_CODE")
    .map { it.toInt() }
    .getOrElse(1)
val appVersionName = "0.2.0"

// Optional release signing, injected by CI or your shell (never commit keystores).
val releaseKeystorePath: String? = providers.environmentVariable("NANOTUNE_KEYSTORE_FILE").orNull

android {
    namespace = "app.nanotune"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = providers.gradleProperty("nanotune.applicationId").getOrElse("io.github.domdzd.nanotune")
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEFAULT_API_BASE_URL", apiBaseUrl.asBuildConfigString())
        buildConfigField("String", "MUSICBRAINZ_CONTACT", musicBrainzContact.asBuildConfigString())
        buildConfigField("boolean", "SHAZAMKIT_BUNDLED", shazamKitBundled.toString())
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("NANOTUNE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("NANOTUNE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("NANOTUNE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a configured release keystore the release APK is signed with the local
            // debug key so it can still be sideloaded. See docs/RELEASE.md.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    sourceSets {
        named("main") {
            kotlin.directories += if (shazamKitBundled) "src/shazamkit/kotlin" else "src/noshazamkit/kotlin"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Lint findings are reported (and printed to the CI log) but do not block the APK.
        abortOnError = false
        checkReleaseBuilds = false
        // OkHttp/Retrofit are pinned on purpose (see libs.versions.toml).
        disable += "NewerVersionAvailable"
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    if (shazamKitBundled) {
        implementation(files(shazamKitAar))
        // Documented by Apple as a dependency used by the ShazamKit AAR.
        implementation(libs.retrofit.converter.gson)
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.compose.ui.test.manifest)
}
