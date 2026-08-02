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
        google()
        mavenCentral()
        // Legacy XposedBridge API jar. compileOnly only — provided by the framework at runtime.
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "XVideoCatcher"
include(":app")
