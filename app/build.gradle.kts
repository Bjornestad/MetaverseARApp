import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.example.metaversearapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.metaversearapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        
        manifestPlaceholders["arcoreApiKey"] = localProperties.getProperty("ARCORE_API_KEY") ?: ""

        // GitHub Gist secrets — defined in local.properties, never committed to VCS
        buildConfigField("String", "GITHUB_TOKEN",       "\"${localProperties.getProperty("GITHUB_TOKEN")       ?: ""}\"")
        buildConfigField("String", "NAV_GRAPH_GIST_ID",  "\"${localProperties.getProperty("NAV_GRAPH_GIST_ID")  ?: ""}\"")
        buildConfigField("String", "ROOMS_GIST_ID",      "\"${localProperties.getProperty("ROOMS_GIST_ID")      ?: ""}\"")

        // ARCore Cloud Anchor keyless auth — service account credentials.
        // Properties.load() turns \n sequences into real newlines; re-escape them
        // so the generated BuildConfig.java contains a valid single-line string literal.
        fun escapeForJavaString(s: String) = s
            .replace("\\", "\\\\")   // backslash must come first
            .replace("\"", "\\\"")   // embedded quotes
            .replace("\n", "\\n")    // real newlines → \n escape
            .replace("\r", "\\r")    // carriage returns

        buildConfigField("String", "ARCORE_CLIENT_EMAIL",
            "\"${escapeForJavaString(localProperties.getProperty("ARCORE_CLIENT_EMAIL") ?: "")}\"")
        buildConfigField("String", "ARCORE_PRIVATE_KEY",
            "\"${escapeForJavaString(localProperties.getProperty("ARCORE_PRIVATE_KEY")  ?: "")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true   // required to expose BuildConfig fields to Kotlin sources
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    
    // AR & Location
    implementation(libs.arsceneview)
    implementation(libs.arcore)
    implementation(libs.play.services.location)

    // ML Kit
    implementation(libs.mlkit.barcode.scanning)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Networking
    val ktor_version = "2.3.12"
    implementation("io.ktor:ktor-client-android:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Tiled map (OsmDroid) for minimap overlay
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
