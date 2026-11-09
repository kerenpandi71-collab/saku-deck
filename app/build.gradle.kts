plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes the run number so every build is a newer version and Shizuku restarts the helper.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
// Fixed signing key (from GitHub secrets) so updates install over the old version.
val keystorePath: String? = System.getenv("SAKU_KEYSTORE_FILE")

android {
    namespace = "com.saku.deck"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.saku.deck"
        minSdk = 33
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        create("saku") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SAKU_KEYSTORE_PASSWORD")
                keyAlias = "saku"
                keyPassword = System.getenv("SAKU_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("saku")
            } else {
                signingConfigs.getByName("debug")
            }
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
        aidl = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
