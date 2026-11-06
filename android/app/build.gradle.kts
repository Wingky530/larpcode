plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val ciKeystorePath = System.getenv("LARPCODE_KEYSTORE_PATH")
val ciKeystore = ciKeystorePath?.let(::file)
val hasCiSigning = ciKeystore?.exists() == true

android {
    namespace = "dev.wingky530.larpcode"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.wingky530.larpcode"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (hasCiSigning) {
            create("ci") {
                storeFile = ciKeystore
                storeType = "PKCS12"
                storePassword = System.getenv("LARPCODE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LARPCODE_KEY_ALIAS")
                keyPassword = System.getenv("LARPCODE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
