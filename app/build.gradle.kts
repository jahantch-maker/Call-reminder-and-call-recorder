plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.callbackreminder.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.callbackreminder.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "0.17.1"
    }

    // A fixed test key so every new build installs as an update over the previous one.
    // (A separate private key will be created before publishing to the Play Store.)
    signingConfigs {
        create("shared") {
            storeFile = file("../keystore/callback-test.jks")
            storePassword = "callback123"
            keyAlias = "callback"
            keyPassword = "callback123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        // The build people install. Release (not debug) so animations run at full speed:
        // debug builds skip Android's and R8's optimizations and visibly stutter.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("shared")
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
