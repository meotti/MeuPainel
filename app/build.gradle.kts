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
        versionCode = 3
        versionName = "1.2"
    }

    buildFeatures {
        buildConfig = true
    }

    // Duas versões:
    //  - "nav": tela inicial desenhada (mostrador). Entra no Android Auto como app de navegação.
    //  - "simples": telas em grade/lista padrão. Entra como app de "lugares" (POI).
    flavorDimensions += "tipo"
    productFlavors {
        create("nav") {
            dimension = "tipo"
            buildConfigField("boolean", "TELA_DESENHADA", "true")
            manifestPlaceholders["carCategory"] = "androidx.car.app.category.NAVIGATION"
            manifestPlaceholders["appName"] = "Meu Painel"
        }
        create("simples") {
            dimension = "tipo"
            applicationIdSuffix = ".simples"
            versionNameSuffix = "-simples"
            buildConfigField("boolean", "TELA_DESENHADA", "false")
            manifestPlaceholders["carCategory"] = "androidx.car.app.category.POI"
            manifestPlaceholders["appName"] = "Meu Painel Simples"
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

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
