plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.bleconfig"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bleconfig"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true


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

    buildFeatures {
        viewBinding = true
        dataBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // 依赖 A 厂和 B 厂模块
    implementation(project(":module-radar"))
    implementation(project(":module-sleepboard"))
    implementation(files("libs/sdkcore.jar"))
    implementation(files("libs/wificonfigsdk.jar"))

    // Android 基础库
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.vectordrawable:vectordrawable:1.1.0")
    // Gson 依赖
    implementation("com.google.code.gson:gson:2.10.1")  // 添加 Gson 依赖


    // 测试依赖
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}