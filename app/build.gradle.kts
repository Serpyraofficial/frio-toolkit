plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pctoolkit.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.serpyra.toolkit"
        minSdk = 26
        targetSdk = 35
        versionCode = 87
        versionName = "5.24"
    }

    // Assinatura fixa (sempre a mesma): sem ela o Android recusa a atualizacao por cima.
    // O arquivo e a senha vem dos segredos do GitHub (KEYSTORE_B64 / KEYSTORE_SENHA).
    val ks = System.getenv("SERPYRA_KS")
    signingConfigs {
        if (ks != null && file(ks).exists()) {
            create("fixa") {
                storeFile = file(ks)
                storePassword = System.getenv("SERPYRA_KS_SENHA")?.trim()
                keyAlias = "serpyra"
                keyPassword = System.getenv("SERPYRA_KS_SENHA")?.trim()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("fixa") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
}
