pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nacre-apps"

include(":core")

file("apps").listFiles()
    ?.filter { it.resolve("build.gradle.kts").exists() }
    ?.forEach { include(":apps:${it.name}") }
