plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.ytlite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ytlite"
        // minSdk 21 covers Android 5.0+; Redmi Go (Go edition) ships Android 8.1 (API 27)
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true       // R8 code shrinking + obfuscation
            isShrinkResources = true     // Strip unused resources
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDebuggable = false
        }
        debug {
            // No shrinking on debug: this is the build we're actually
            // sideloading/testing right now, so it needs to be a normal,
            // unmodified, guaranteed-to-install APK. Shrinking stays only
            // on the release build type above.
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        viewBinding = false
        buildConfig = false
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    packaging {
        resources.excludes.add("META-INF/*")
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    // Zero third-party dependencies — only the core Android SDK is used.
}
