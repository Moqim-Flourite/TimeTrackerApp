plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.operit.timetracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.operit.timetracker"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release-key.jks")
            // Read from local.properties
            val props = mutableMapOf<String, String>()
            val f = file("../local.properties")
            if (f.exists()) {
                f.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.contains("=") && !trimmed.startsWith("#")) {
                        val (k, v) = trimmed.split("=", limit = 2)
                        props[k.trim()] = v.trim()
                    }
                }
            }
            storePassword = props["keystore.storePassword"] ?: ""
            keyAlias = props["keystore.keyAlias"] ?: ""
            keyPassword = props["keystore.keyPassword"] ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Force use of ARM64 binaries for AAPT2 in Proot environment
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
            useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation("androidx.compose.material:material-icons-core")
    
    // Navigation Compose
    implementation(libs.androidx.navigation.compose)
    
    // ViewModel Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // 网络请求（OpenAI API + 同步）
    implementation(libs.okhttp)
    // WorkManager 定时同步
    implementation(libs.androidx.work.runtime.ktx)
    // 加密存储（API key）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
