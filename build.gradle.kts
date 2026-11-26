import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // 无边框窗口的原生拖动（Windows HTCAPTION）：让系统接管拖拽，
    // 才能触发现有 Aero Snap / Win11 贴靠分区
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

// 自用版构建参数（公开仓库/GitHub 构建不传 → 三个值都是空，源码和安装包里不含任何地址）：
//   -PplayBases=http://...,https://...   -PplayToken=...   -PsignUrl=http://...   -PhistoryBase=https://...
val buildPlayBases = (findProperty("playBases") as String?)?.trim().orEmpty()
val buildPlayToken = (findProperty("playToken") as String?)?.trim().orEmpty()
val buildSignUrl = (findProperty("signUrl") as String?)?.trim().orEmpty()
val buildHistoryBase = (findProperty("historyBase") as String?)?.trim().orEmpty()
// 配对服务网关地址（与「每机唯一本地签名」清空的 signUrl 解耦；发版单独注入）
val buildPairBase = (findProperty("pairBase") as String?)?.trim().orEmpty()

// 版本号唯一来源：发版只改这一行，packageVersion 和代码里的 BuildInfo 都从这里生成。
// tools/release.py 可用 -PpcVersion 覆盖，源码仍是默认值。
val pcVersion = (findProperty("pcVersion") as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: "1.2.136"
val pcCode = pcVersion.split('.').let { it[0].toInt() * 10000 + it[1].toInt() * 100 + it[2].toInt() }

val generateBuildDefaults = tasks.register("generateBuildDefaults") {
    val outDir = layout.buildDirectory.dir("generated/defaults")
    outputs.dir(outDir)
    val pb = buildPlayBases
    val pt = buildPlayToken
    val su = buildSignUrl
    val hb = buildHistoryBase
    val pbase = buildPairBase
    val ver = pcVersion
    val verCode = pcCode
    doLast {
        val f = outDir.get().asFile.resolve("com/kaixinguo/pc/BuildDefaults.kt")
        f.parentFile.mkdirs()
        f.writeText(
            """
            |// 由 build.gradle.kts generateBuildDefaults 生成，不要手改（见仓库规则：私有参数不入库）
            |package com.kaixinguo.pc
            |
            |object BuildDefaults {
            |    const val PLAY_BASES = "$pb"
            |    const val PLAY_TOKEN = "$pt"
            |    const val SIGN_URL = "$su"
            |    const val HISTORY_BASE = "$hb"
            |    const val PAIR_BASE = "$pbase"
            |}
            |""".trimMargin(),
        )
        // BuildInfo 也一起生成：以前版本号在 build.gradle 和 Updater.kt 各写一份，
        // 1.2.72~1.2.75 只改了安装包版本，程序里还认为自己是 1.2.71，
        // 设置页显示错误、检查更新永远提示有新版本。
        val fv = outDir.get().asFile.resolve("com/kaixinguo/pc/BuildInfo.kt")
        fv.writeText(
            """
            |// 由 build.gradle.kts 生成，不要手改；版本号只改 build.gradle.kts 的 pcVersion
            |package com.kaixinguo.pc
            |
            |object BuildInfo {
            |    const val VERSION = "$ver"
            |    const val CODE = $verCode
            |}
            |""".trimMargin(),
        )
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(generateBuildDefaults)
// PC/Android 共用核心：只保留一份源码
kotlin.sourceSets["main"].kotlin.srcDir("shared/src/main/kotlin")

compose.desktop {
    application {
        mainClass = "com.kaixinguo.pc.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "开心果"
            packageVersion = pcVersion
            // 安装包 / 快捷方式 / 任务栏图标（与安卓启动图标同一张）
            windows {
                iconFile.set(project.layout.projectDirectory.file("brand.ico"))
            }
            // 把 ffmpeg.exe 一起打进安装包（HEIC 封面 + HEVC 播放都靠它）
            appResourcesRootDir.set(project.layout.projectDirectory.dir("pack"))
        }
    }
}

tasks.register<JavaExec>("probe") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.kaixinguo.pc.ProbeKt")
}