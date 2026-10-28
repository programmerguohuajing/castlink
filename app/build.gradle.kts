plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.castlink.receiver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.castlink.receiver"
        minSdk = 26
        targetSdk = 34
        // v0.3.1：QA 修复版（国际化硬编码收敛、删无用权限、补 launcher icon、注释修正）。
        // 注意：沙箱内实际打包走 tools/build_manual.sh（手工链），此处仅保持声明同步。
        versionCode = 74
        versionName = "0.1.53"
    }

    buildTypes {
        debug {
            // MVP 调试包：不混淆，保留行号便于定位
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 纯框架 API，不用 AndroidX
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    // 二维码（TV 待机页），core 无传递依赖
    implementation("com.google.zxing:core:3.5.3")
    // v0.2.0：AirPlay 移植新增依赖（build_manual.sh 手工链同步引入）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4") // Dispatchers.Main
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1") // MIT；PairingSession/PairingKeys 的 X25519/Ed25519
    implementation("com.googlecode.plist:dd-plist:1.28") // MIT；PlistCodec 的 bplist 编解码
}
