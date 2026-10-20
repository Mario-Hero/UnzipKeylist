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
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                // 不要包含 kotlin.**：kotlin-stdlib 的多文件 facade 类
                // （CollectionsKt / CollectionsKt__IterablesKt / CollectionsKt__IteratorsKt 等）
                // 是跨包继承的链式结构，一旦被改名并重打包到不同包，
                // 运行时会抛 IllegalAccessError: Class ... extended by class ... is inaccessible
                packageScope = setOf("androidx.**", "kotlinx.**")
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
