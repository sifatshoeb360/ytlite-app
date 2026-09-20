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
        versionCode = 1
        versionName = "1.0"

        // Only need to support a single ABI worth of native code (none used here,
        // but this keeps things clean if any future native libs are ever added).
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
            // Also shrink debug builds: debug APKs are automatically signed
            // with the built-in debug keystore, so this is the easiest way
            // to get a small, installable APK without setting up your own
            // signing key (useful when you're building via GitHub Actions
            // instead of Android Studio and just want to sideload the APK).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // No ViewBinding/DataBinding — keeps generated code (and APK size) to a minimum
    buildFeatures {
        viewBinding = false
        buildConfig = false
    }

    // Only build a single universal APK — no per-density/per-ABI splits needed
    // since there are no native libs or density-specific drawables.
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
    // No Retrofit, no Glide, no AndroidX AppCompat/Material even — a plain
    // Activity + WebView needs none of that, and skipping it keeps the
    // APK in the 1-2 MB range.
}
