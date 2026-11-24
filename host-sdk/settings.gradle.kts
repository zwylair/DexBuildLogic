pluginManagement {
    includeBuild("..")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("zwy.dex-settings")
}

rootProject.name = "host-sdk"