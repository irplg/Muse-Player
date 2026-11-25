plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    // SF Symbols 的公开完整目录 + 构建期按引用打包符号数据。
    id("io.github.nevodev.sfcompose")
}

android {
    namespace = "app.muse.player"
    // SFCompose 0.1.2 的 AAR 声明 minCompileSdk=37.1，低于这个值构建会直接失败。
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "app.muse.player"
        // sfcompose 的 AAR manifest 是 minSdk 28，低于 28 合并清单会报错。
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            // 只产出一个通用 APK：不混淆、不拆 ABI、不出 AAB；用调试签名，装机即可用
            // 只产出一个通用 APK：不拆 ABI、不出 AAB；用调试签名，装机即可用。
            // 这里必须开 R8：SFCompose 的公开目录是一个约 1.15 万个类的 AAR，未引用的
            // 符号类只有靠 release 代码压缩才会被裁掉，否则安装包会从 12MB 涨到 45MB。
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    // compose-bom 要跟上 SFCompose 0.1.2 的编译期版本（2026.11.01 → compose 1.13.0），
    // 否则运行时会因为 Compose runtime 版本低于库的编译版本而崩溃。
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    // 底栏与迷你播放条的实时毛玻璃。
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // io.github.nevodev:sfcompose-catalog 由 SFCompose 插件自动加入，无需显式声明。
}
