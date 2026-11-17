import java.util.zip.ZipFile
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hiynet.aspatoys"
    compileSdk {
        version = release(37)
    }

    // 版本号由 CI 传入（tag 发布时取 tag 名），本地构建用默认值。
    val appVersionName = (project.findProperty("appVersionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.3.1"
    val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 10301

    defaultConfig {
        applicationId = "com.hiynet.aspatoys"
        minSdk = 31
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 正式包签名：本地/CI 用环境变量注入，不把 keystore 或密码写进仓库。
    val releaseKeyStore = System.getenv("ASPATOYS_KEYSTORE")
    signingConfigs {
        if (!releaseKeyStore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeyStore)
                storePassword = System.getenv("ASPATOYS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ASPATOYS_KEY_ALIAS") ?: "aspatoys"
                keyPassword = System.getenv("ASPATOYS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
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
    testOptions { unitTests.isReturnDefaultValues = true }
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

                // 发行版清单里的版本号：App 写 installed.json 时用它，避免为了一个版本号再手改代码。
                val manifestEntry = zip.getEntry("cesium-loader.json")
                if (manifestEntry != null) {
                    val text = zip.getInputStream(manifestEntry).use { it.readBytes().toString(Charsets.UTF_8) }
                    val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
                        .find(text)?.groupValues?.get(1)
                    if (!version.isNullOrBlank()) {
                        File(assetsCesium, "loader.json").writeText(
                            "{\"version\":\"$version\",\"source\":\"${cesiumReleaseZip.name}\"}\n",
                            Charsets.UTF_8,
                        )
                        logger.lifecycle("加载器版本 -> assets/cesium/loader.json  $version")
                    } else {
                        logger.warn("发行版清单里没有 version 字段，沿用 assets 里已有的 loader.json")
                    }
                } else {
                    logger.warn("发行版 zip 里没有 cesium-loader.json，沿用 assets 里已有的 loader.json")
                }
            }
        } else {
            logger.warn("没有指定发行版 zip（-PcesiumReleaseZip），跳过内置模组同步")
        }

        // 构建发行版模组之后再合入安卓悬浮窗，避免发行版同步重建 mods 时将它删除。
        val overlayAssets = File(repoRootDir, "build/builtin-overlay/assets")
        if (overlayAssets.isDirectory) {
            overlayAssets.copyRecursively(assetsCesium, overwrite = true)
        }
        require(File(assetsCesium, "CesiumAndroidApi.dll").isFile &&
            File(assetsCesium, "mods/CesiumOverlayMod/CesiumOverlayMod.dll").isFile &&
            File(assetsCesium, "mods/SpeedHackMod/SpeedHackMod.dll").isFile &&
            File(assetsCesium, "mods/SpeedHackMod/SpeedHackMod.json").takeIf { it.isFile }
                ?.readText()?.contains("2.5.5-android-panel") == true &&
            File(assetsCesium, "mods/FreeCameraMod/FreeCameraMod.dll").isFile &&
            File(assetsCesium, "mods/FreeCameraMod/FreeCameraMod.json").takeIf { it.isFile }
                ?.readText()?.contains("2.7.2-android-pinch") == true) {
            "缺少内置悬浮窗：先运行 python mods/build.py --refs <游戏引用程序集目录>（详见 mods/README.md）"
        }
        val recovery = File(assetsCesium, "recovery/original.bundle")
        require(recovery.isFile) { "缺少离线恢复原包：先运行 python installer/prepare-recovery.py --bundle <原版__data>" }
        val recoveryHash = MessageDigest.getInstance("SHA-256").digest(recovery.readBytes())
            .joinToString("") { "%02x".format(it) }
        require(recoveryHash == "77da946bf7d331351a93ff16707aab426f534b7577a771f21267478b7bc70c87") {
            "离线恢复原包指纹不符，拒绝打包"
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
