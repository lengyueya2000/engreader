plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties

/**
 * Release signing, read from `keystore.properties`.
 *
 * The file is git-ignored, so a fresh clone has none. Asking for a *release* artifact
 * without it used to exit 0 and leave `app-release-unsigned.apk` behind 鈥?an APK that
 * cannot be installed 鈥?which a release script would then publish. That is an error
 * now; `-Pengreader.allowUnsignedRelease=true` restores the old behaviour for the rare
 * case of wanting to inspect an unsigned release build.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val requiredSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val missingSigningKeys = requiredSigningKeys.filter { keystoreProperties.getProperty(it).isNullOrBlank() }
val hasReleaseSigning = missingSigningKeys.isEmpty()
val signingStoreFile = keystoreProperties.getProperty("storeFile")
    ?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }

// Checked while the build is being configured, not in `packageRelease`: a partial
// `keystore.properties` used to fail after the whole release compilation had run.
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
val unsignedReleaseAllowed = providers.gradleProperty("engreader.allowUnsignedRelease").isPresent
if (releaseRequested && !hasReleaseSigning && !unsignedReleaseAllowed) {
    error(
        "keystore.properties is missing or incomplete (missing: ${missingSigningKeys.joinToString()}); " +
            "a release APK must be signed. Pass -Pengreader.allowUnsignedRelease=true to build one anyway.",
    )
}
if (releaseRequested && signingStoreFile != null && !signingStoreFile.exists()) {
    error("keystore.properties names a storeFile that does not exist: ${signingStoreFile.path}")
}

android {
    namespace = "com.engreader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.engreader.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "2.0.1"
    }

    // One APK per ABI rather than one fat APK: the speech engine's native library is
    // ~25 MB per architecture, so a phone would otherwise download a copy it can
    // never execute. armeabi-v7a and x86 are left out 鈥?the bundled voices are 77 MB
    // of assets on their own, and a 32-bit process has no room for a 19 MB graph
    // beside its runtime.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Shrinking stays off: turning it on changes every release APK, and the
            // bundled speech engine is reached through JNI by name. The keep rules it
            // would need are in proguard-rules.pro, written up front so that enabling
            // this is a one-line change instead of a debugging session 鈥?the vendored
            // sherpa-onnx AAR carries an empty proguard.txt, so nothing else keeps them.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // The bundled dictionary is a SQLite file; storing it uncompressed keeps
    // the first-run copy to internal storage cheap.
    androidResources {
        noCompress.add("db")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // sherpa-onnx has no Maven artifact, so the AAR is vendored under app/libs.
    // It carries the offline VITS speech engine used for reading aloud; see
    // app/src/main/java/com/engreader/app/tts/NeuralTts.kt.
    implementation(files("libs/sherpa-onnx.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * Full-size books for [com.engreader.app.book.BookRealFileTest], which are far too
 * large to commit. Point `-Pengreader.bookFixtures=<dir>` at a directory holding
 * `pride.epub`, `pride.mobi` and `pride.azw3` to run those tests; without it they
 * skip, and the synthetic fixtures under `src/test/resources/book` still cover every
 * branch of both parsers.
 */
tasks.withType<Test>().configureEach {
    // A project property, not a system property: `-P` does not reach System.getProperty
    // in the build script, and the test JVM needs its own copy.
    (project.findProperty("engreader.bookFixtures") as String?)?.let {
        systemProperty("engreader.bookFixtures", it)
    }
}

