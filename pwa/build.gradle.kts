plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Se pasan desde el workflow: -PpwaId=pwa.com.ejemplo.app -PpwaVersion=12
val pwaId = (findProperty("pwaId") as String?) ?: "pwa.example.app"
val pwaVersion = ((findProperty("pwaVersion") as String?) ?: "1").toInt()
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.johancarrion.pwashell"
    compileSdk = 34

    defaultConfig {
        applicationId = pwaId
        minSdk = 26
        targetSdk = 34
        versionCode = pwaVersion
        versionName = "1.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("pwa") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("pwa") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
