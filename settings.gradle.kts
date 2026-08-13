pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // easytier-android-jni 独立构建产物（GitHub Pages 托管的 Maven 仓库）
        maven { url = uri("https://williamgao1130.github.io/maven") }
        google()
        mavenCentral()
    }
}

rootProject.name = "AnTier"
include(":app")
