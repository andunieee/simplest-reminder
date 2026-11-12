plugins {
    id("com.android.application")
}

// Release signing comes from the environment, which is how both the CI
// workflow (decoded from repository secrets) and ~/android-release/env.sh
// (local builds) provide the keystore.
val keystorePath = System.getenv("ANDROID_KEYSTORE")
val hasReleaseKey = !keystorePath.isNullOrBlank()

android {
    namespace = "com.andunie.reminder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.andunie.reminder"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystorePath!!)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "ANDROID_KEYSTORE is not set: the release APK will be signed with the " +
                        "debug key. Source ~/android-release/env.sh to sign with the release key."
                )
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
