plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 取流代理用 OkHttp：安卓和 PC(JVM) 都能直接用
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JSON：安卓由系统提供（compileOnly 避免重复打包），PC 由依赖提供
    compileOnly("org.json:json:20240303")
}
