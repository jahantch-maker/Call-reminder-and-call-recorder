plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.callbackreminder.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.callbackreminder.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 100
        versionName = "0.59.1"
    }

    // Two apps from the same code: "full" is the main app, "cr" is the CR test copy. Only the name, the icon
    // colour, the package (.test) and the update channel differ (see src/cr/res). Both are in google-services.json.
    flavorDimensions += "edition"
    productFlavors {
        create("full") { dimension = "edition" }
        create("cr") {
            dimension = "edition"
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
    }

    // The signing key is NOT in the code. The build gets it from GitHub secrets (KEYSTORE_PATH / KEYSTORE_PASSWORD).
    // Without it (a fork, or a plain local build) the debug build uses Android's own debug key and release stays unsigned.
    val keystore = System.getenv("KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("shared") {
            storeFile = keystore
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "rafiq"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
        // The build people install. Release (not debug) so animations run at full speed:
        // debug builds skip Android's and R8's optimizations and visibly stutter.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("shared")?.let { signingConfig = it }
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
    // Screen tests at every display size run on the computer (Robolectric draws the real screens).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "3g"
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Home screen widgets
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // Team edition: Google sign-in, the shared task list (works offline, syncs when data is on) and the
    // background check every 15 minutes.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    // 🟢 Online now, live (Realtime Database presence with onDisconnect).
    implementation("com.google.firebase:firebase-database")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Display size tests: nothing may go off screen at any size.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
