import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hiynet.aspatoys"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hiynet.aspatoys"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// ---------------------------------------------------------------- 载荷同步
//
// SDK、托管引导、发行版内置模组、注入后的游戏程序集都是**构建产物**，不进版本库。
// 这个任务把它们从各自来源拷进 src/main/assets/cesium，替代以前的手工复制：
//
//   SDK          -PcesiumSdkDll=<dll>        （默认取工作区里的 loader 工程输出）
//   托管引导      -PcesiumBootstrapDll=<dll>  （默认取 loader/managed 的输出）
//   注入后的程序集 -PcesiumInjectedDll=<dll>   （默认取 outputs/phone 下的产物）
//   发行版模组    -PcesiumReleaseZip=<zip>    （可选；给了就把里面的内置模组同步过来）
val workspaceRoot = rootProject.projectDir.parentFile
val repoRootDir = rootProject.projectDir
val assetsCesium = layout.projectDirectory.dir("src/main/assets/cesium").asFile

fun resolveFile(property: String, env: String, fallback: File): File {
    val fromProperty = (project.findProperty(property) as String?)?.takeIf { it.isNotBlank() }
    val fromEnv = System.getenv(env)?.takeIf { it.isNotBlank() }
    return File(fromProperty ?: fromEnv ?: fallback.path)
}

val cesiumSdk = resolveFile("cesiumSdkDll", "CESIUM_SDK_DLL", File(workspaceRoot, "modding/msvc/loader/CesiumLoader.SDK/bin/Release/netstandard2.0/CesiumLoader.SDK.dll"))
val cesiumBootstrap = resolveFile("cesiumBootstrapDll", "CESIUM_BOOTSTRAP_DLL", File(rootProject.projectDir, "loader/managed/CesiumLoader.Android.Bootstrap/bin/Release/netstandard2.0/CesiumLoader.Android.Bootstrap.dll"))
val cesiumInjected = resolveFile("cesiumInjectedDll", "CESIUM_INJECTED_DLL", File(workspaceRoot, "outputs/phone/AstralParty.Runtime.injected.dll"))
val cesiumReleaseZip = resolveFile("cesiumReleaseZip", "CESIUM_RELEASE_ZIP", File(""))

val syncLoaderAssets = tasks.register("syncLoaderAssets") {
    // 直接读写 assets 目录、不参与增量跟踪，所以显式退出配置缓存而不是让它报错。
    notCompatibleWithConfigurationCache("同步 assets 载荷：直接文件 IO，不做增量跟踪")
    group = "cesium"
    description = "把 SDK / 引导 DLL / 发行版内置模组 / 注入产物同步到 assets/cesium"

    doLast {
        fun copyOrExplain(source: File, target: File, what: String) {
            if (!source.isFile) {
                if (!target.isFile) {
                    error(
                        "缺少 $what：$source\n" +
                            "先构建它（或给它对应的 -P<property> / 环境变量），" +
                            "或者保留 assets 里已有的产物。"
                    )
                }
                logger.warn("跳过 $what（源文件不存在：$source），沿用 assets 里已有的")
                return
            }
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
            logger.lifecycle("$what -> ${target.relativeTo(repoRootDir)}  ${source.length()} B")
        }

        copyOrExplain(cesiumSdk, File(assetsCesium, "sdk/CesiumLoader.SDK.dll"), "sdk")
        copyOrExplain(cesiumBootstrap, File(assetsCesium, "bootstrap/CesiumLoader.Android.Bootstrap.dll"), "bootstrap")
        copyOrExplain(cesiumInjected, File(assetsCesium, "AstralParty.Runtime.injected.dll"), "injected")

        if (cesiumReleaseZip.isFile) {
            // 整目录重建比增量拷贝可靠：发行版删掉的模组不会残留在 assets 里。
            val modsDir = File(assetsCesium, "mods")
            modsDir.deleteRecursively()
            ZipFile(cesiumReleaseZip).use { zip ->
                var files = 0
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (entry.isDirectory) continue
                    if (!name.startsWith("AstralParty_ModLoader/mods/")) continue
                    if (name.endsWith(".pdb", ignoreCase = true)) continue

                    val target = File(modsDir, name.removePrefix("AstralParty_ModLoader/mods/"))
                    target.parentFile.mkdirs()
                    val input = zip.getInputStream(entry)
                    try {
                        target.outputStream().use { output -> input.copyTo(output) }
                    } finally {
                        input.close()
                    }
                    files++
                }
                val mods = modsDir.listFiles()?.count { it.isDirectory } ?: 0
                logger.lifecycle("内置模组 -> assets/cesium/mods  $mods 个模组 / $files 个文件")
            }
        } else {
            logger.warn("没有指定发行版 zip（-PcesiumReleaseZip），跳过内置模组同步")
        }
    }
}

tasks.named("preBuild") { dependsOn(syncLoaderAssets) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":cesiumcore"))
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}