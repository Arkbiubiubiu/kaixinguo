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

val generateBuildDefaults = tasks.register("generateBuildDefaults") {
    val outDir = layout.buildDirectory.dir("generated/defaults")
    outputs.dir(outDir)
    val pb = buildPlayBases
    val pt = buildPlayToken
    val su = buildSignUrl
    val hb = buildHistoryBase
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
            |}
            |""".trimMargin(),
        )
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(generateBuildDefaults)

compose.desktop {
    application {
        mainClass = "com.kaixinguo.pc.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "开心果"
            packageVersion = "1.2.34"
            // 安装包 / 快捷方式 / 任务栏图标（与安卓启动图标同一张）
            windows {
                iconFile.set(project.layout.projectDirectory.file("brand.ico"))
            }
            // 把 ffmpeg.exe 一起打进安装包（HEIC 封面 + HEVC 播放都靠它）
            appResourcesRootDir.set(project.layout.projectDirectory.dir("pack"))
        }
    }
}




