plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.unzipkeylist"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.unzipkeylist"
        // 26：commons-compress / commons-io 的 Builder 依赖 java.nio.file（API 26+）
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // 解压内核
    implementation(libs.zip4j)            // zip（含 AES 加密）
    implementation(libs.commons.compress) // 7z（含分卷）
    implementation(libs.xz)               // commons-compress 解 7z 的 LZMA/LZMA2 必需
    implementation(libs.junrar)           // rar / rar5（含分卷）
    implementation(libs.slf4j.nop)        // junrar 依赖 slf4j-api，用 nop 绑定静音
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
