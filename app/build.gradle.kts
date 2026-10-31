plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.meupainel.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.meupainel.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "2.1"

        buildConfigField("boolean", "TELA_DESENHADA", "true")
        manifestPlaceholders["carCategory"] = "androidx.car.app.category.NAVIGATION"
        manifestPlaceholders["appName"] = "Meu Painel"
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
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media:media:1.7.0")
}
