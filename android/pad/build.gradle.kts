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
        versionCode = 212
        versionName = "2.2.92"
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

    // debug 签名固定用仓库里的 keystore（与服务器构建同源）：
    // 老设备的已装包都是这个签名，本机构建若用默认 debug keystore 会签名不一致，
    // 覆盖安装直接被拒（2.2.79 电视实测）。换机器/重装系统也不影响出可升级的包。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // 发版改用 release（非 debuggable）：debug 包在 ART 上是解释/JIT 执行，
            // 电视实测冷启动 10s+（类加载一条消息就 3.3s）；release 安装时会 AOT，
            // 冷启动明显更快。签名仍用仓库里的 debug keystore，保证老设备覆盖升级。
            signingConfig = signingConfigs.getByName("debug")
            // 先不开 R8：Go 绑定（dev.openhongguo.**）走 JNI 按类名/方法名查找，
            // 混淆必须配 keep 规则，单独一轮验证后再开。
            isMinifyEnabled = false
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


