plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hongguotv.pad"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        versionCode = 192
        versionName = "2.2.72"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }

        // 服务器地址不写死在源码里：自用版构建时传 -PsignUrl=... -PplayBases=... -PplayToken=...
        // 公开版什么都不传 → 三个值都是空，由使用者在「设置」里自己填，仓库里也不会泄露任何地址。
        fun prop(name: String): String = (project.findProperty(name) as String?)?.trim().orEmpty()
        buildConfigField("String", "DEFAULT_SIGN_URL", "\"${prop("signUrl")}\"")
        buildConfigField("String", "DEFAULT_PLAY_BASES", "\"${prop("playBases")}\"")
        buildConfigField("String", "PLAY_TOKEN", "\"${prop("playToken")}\"")
        buildConfigField("String", "UPDATE_BASES", "\"${prop("updateBases")}\"")
        buildConfigField("String", "HISTORY_BASE", "\"${prop("historyBase")}\"")
        buildConfigField("String", "DEVICE_IID", "\"${prop("deviceIid")}\"")
        buildConfigField("String", "DEVICE_ID", "\"${prop("deviceId")}\"")
        buildConfigField("String", "DEVICE_CDID", "\"${prop("deviceCdid")}\"")
    }

    // 原项目就是两个包名两个包：TV 与 Pad 共用同一份源码
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            applicationId = "com.hongguotv.nativeapp"
            // 同一个设备上可能两个包都装，桌面名字要能分开
            resValue("string", "app_name", "开心果TV")
        }
        create("pad") {
            dimension = "device"
            applicationId = "com.hongguotv.pad"
            resValue("string", "app_name", "开心果")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // release 上的 lintVital 分析要 35 秒（97 秒里占三分之一），它只是静态检查、
    // 不产出任何功能；关掉后发版明显变快
    lint { checkReleaseBuilds = false }
    buildFeatures {
        compose = true
        // BuildConfig 里放的是服务器地址开关（默认空），必须显式打开生成
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
    // 资源已被混淆成短名，不能按名字收缩，否则会误删
    androidResources { noCompress += listOf("ttf") }
}

dependencies {
    // 三端共用逻辑（模型 / 解密 / 签名 / 取流代理）
    implementation(project(":core"))
    // 本机取流：gomobile 生成的 Go 绑定（Apache-2.0，见 NOTICE）
    implementation(files("libs/openhongguo.jar"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // 播放：ExoPlayer + 媒体抽取（原工程就用的这套）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-extractor:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
