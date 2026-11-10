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

compose.desktop {
    application {
        mainClass = "com.kaixinguo.pc.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "开心果"
            packageVersion = "1.2.6"
            // 安装包 / 快捷方式 / 任务栏图标（与安卓启动图标同一张）
            windows {
                iconFile.set(project.layout.projectDirectory.file("brand.ico"))
            }
            // 把 ffmpeg.exe 一起打进安装包（HEIC 封面 + HEVC 播放都靠它）
            appResourcesRootDir.set(project.layout.projectDirectory.dir("pack"))
        }
    }
}
