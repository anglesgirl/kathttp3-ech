plugins {
    id("com.android.library") version "8.12.0" apply false
    id("com.android.application") version "8.12.0" apply false
    // 2026-10-01 降级到 AGP 8.12 + Kotlin 2.1.20：宿主 CO3（RN）用该版本
    // 编译链，AGP 9 内置 Kotlin 2.4 的 AAR 元数据（2.4.0）CO3 读不了。
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    // Compose 编译器与 Kotlin 版本配对（example 模块用）。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

val nativeDepsAbi = providers.gradleProperty("androidNativeDepsAbi")
val nativeDepsJobs = providers.gradleProperty("androidNativeDepsJobs")
val nativeDepsParallelAbis = providers.gradleProperty("androidNativeDepsParallelAbis")

tasks.register<Exec>("buildAndroidNativeDeps") {
    group = "build"
    description = "Cross-compiles pinned BoringSSL, nghttp3, and ngtcp2 for Android"
    workingDir(rootDir)
    val command = mutableListOf("bash", "scripts/build-android-deps.sh")
    nativeDepsAbi.orNull?.takeIf { it.isNotBlank() }?.let { command += listOf("--abi", it) }
    nativeDepsJobs.orNull?.takeIf { it.isNotBlank() }?.let { command += listOf("--jobs", it) }
    nativeDepsParallelAbis.orNull?.takeIf { it.isNotBlank() }?.let {
        command += listOf("--parallel-abis", it)
    }
    commandLine(command)
    inputs.file("scripts/build-android-deps.sh")
    inputs.file("third_party/versions.env")
    inputs.file("third_party/versions.cmake")
    inputs.dir("third_party/patches")
    outputs.dir("third_party/android-deps")
}
