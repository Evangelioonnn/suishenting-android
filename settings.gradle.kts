pluginManagement {
    repositories {
        providers.gradleProperty("museumReader.localMaven")
            .orElse(providers.environmentVariable("MUSEUM_READER_LOCAL_MAVEN")).orNull
            ?.takeIf { it.isNotBlank() }?.let { location ->
                require(file(location).isDirectory) { "Configured optional local Maven directory does not exist" }
                maven { url = uri(location) }
            }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.gradleProperty("museumReader.localMaven")
            .orElse(providers.environmentVariable("MUSEUM_READER_LOCAL_MAVEN")).orNull
            ?.takeIf { it.isNotBlank() }?.let { location ->
                require(file(location).isDirectory) { "Configured optional local Maven directory does not exist" }
                maven { url = uri(location) }
            }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "MuseumReader"
include(":app")
