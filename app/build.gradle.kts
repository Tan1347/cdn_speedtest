import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties()
val keystoreFile = rootProject.file("release.keystore")
if (keystoreFile.exists()) {
    keystoreProperties.load(keystoreFile.inputStream())
}

android {
    namespace = "org.tan.cdntest"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.tan.cdntest"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "1.3.0"

        resourceConfigurations += listOf("zh", "en")

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = System.getenv("KEYSTORE_STORE_PASSWORD")
                ?: keystoreProperties.getProperty("storePassword")
                ?: "cdntest123"
            keyAlias = System.getenv("KEYSTORE_KEY_ALIAS")
                ?: keystoreProperties.getProperty("keyAlias")
                ?: "cdntest"
            keyPassword = System.getenv("KEYSTORE_KEY_PASSWORD")
                ?: keystoreProperties.getProperty("keyPassword")
                ?: "cdntest123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = false
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "**/kotlin.kotlin_builtins",
                "kotlin-tooling-metadata.json"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }


}

android.applicationVariants.all {
    outputs.all {
        val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        output.outputFileName = "cdntest_${versionName}.apk"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.tukaani:xz:1.9")
    implementation("com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
}
