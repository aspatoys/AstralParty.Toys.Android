plugins {
    id("com.android.library")
}

android {
    namespace = "com.hiynet.cesiumloader.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// 容器解析的测试要读一份真实的 __data（仓库里不放 8 MB 游戏数据）。
// 用 -PcesiumBundle=<路径> 传进来，转发给测试 JVM 的 CESIUM_BUNDLE；没传则相关用例自动跳过。
tasks.withType<Test>().configureEach {
    val bundle = (project.findProperty("cesiumBundle") as String?) ?: System.getenv("CESIUM_BUNDLE")
    if (!bundle.isNullOrBlank()) environment("CESIUM_BUNDLE", bundle)

    val patched = (project.findProperty("cesiumPatchedDll") as String?) ?: System.getenv("CESIUM_PATCHED_DLL")
    if (!patched.isNullOrBlank()) environment("CESIUM_PATCHED_DLL", patched)

    val out = (project.findProperty("cesiumOut") as String?) ?: System.getenv("CESIUM_OUT")
    if (!out.isNullOrBlank()) environment("CESIUM_OUT", out)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
