import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key, kept outside the repo: keystore.properties here (git-ignored), or
// ~/.android/ow18b-lcd-keystore.properties. Without either, release builds use the debug key,
// which is fine for building your own copy.
val keystoreProps = Properties().apply {
    val home = File(System.getProperty("user.home"), ".android/ow18b-lcd-keystore.properties")
    (rootProject.file("keystore.properties").takeIf { it.exists() } ?: home.takeIf { it.exists() })
        ?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.ssjrocks.ow18blcd"
    compileSdk = 35

    defaultConfig {
        // Crash-report URL, kept out of the repo: appReportsUrl in ~/.gradle/gradle.properties.
        // Without it the app builds and runs but sends no reports.
        manifestPlaceholders["appReportsUrl"] = (findProperty("appReportsUrl") as String?) ?: ""
        applicationId = "com.ssjrocks.ow18blcd"
        minSdk = 26
        targetSdk = 35
        // ow18b.versionCode/versionName/updateApi only exist to test the updater.
        versionCode = (findProperty("ow18b.versionCode") as String?)?.toInt() ?: 2
        versionName = (findProperty("ow18b.versionName") as String?) ?: "1.1.0"
        val updateApi = (findProperty("ow18b.updateApi") as String?)
            ?: "https://api.github.com/repos/ssjrocks/ow18b-lcd/releases/latest"
        buildConfigField("String", "UPDATE_API", "\"$updateApi\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
