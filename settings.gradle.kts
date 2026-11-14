pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Astral Party Toys"
include(":app")

// 加载器核心（容器读写、盘上布局、启用索引）与加载器仓库共用一份源码，避免两套实现漂移。
include(":cesiumcore")
project(":cesiumcore").projectDir = file("../android_cesiumloader/core")
