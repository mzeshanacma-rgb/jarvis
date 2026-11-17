plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The school server the app opens. Leave empty to let the user type it on first launch.
// Set at build time with -PserverUrl=https://app.example.com or the APP_SERVER_URL environment variable.
val serverUrl: String = (findProperty("serverUrl") as String?) ?: System.getenv("APP_SERVER_URL") ?: ""

android {
    namespace = "com.assignmentreview.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.assignmentreview.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SERVER_URL", "\"${serverUrl.replace("\"", "")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Fixed debug key so each debug build installs over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Signed with your private key when CI provides it (see android/README.md).
            System.getenv("RELEASE_STORE_FILE")?.let { store ->
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(store)
                    storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "assignmentreview"
                    keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
                }
            }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
