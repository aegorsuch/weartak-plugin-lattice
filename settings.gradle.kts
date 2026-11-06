pluginManagement {
    repositories {
        val user = listOf("ARTIFACTORY_CONSUME_USER", "ARTIFACTORY_USER", "TAK_USERNAME")
            .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
        val password = listOf("ARTIFACTORY_CONSUME_PASSWORD", "ARTIFACTORY_PASSWORD", "TAK_PASSWORD")
            .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
        if (user != null && password != null) {
            maven("https://artifacts.tak.gov/artifactory/maven-release") {
                credentials { username = user; this.password = password }
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val takUser = listOf("ARTIFACTORY_CONSUME_USER", "ARTIFACTORY_USER", "TAK_USERNAME")
    .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
val takPassword = listOf("ARTIFACTORY_CONSUME_PASSWORD", "ARTIFACTORY_PASSWORD", "TAK_PASSWORD")
    .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
if (takUser != null && takPassword != null) {
    gradle.extra["testRepositoryUser"] = takUser
    gradle.extra["testRepositoryPassword"] = takPassword
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (takUser != null && takPassword != null) {
            maven("https://artifacts.tak.gov/artifactory/maven-release") {
                credentials { username = takUser; password = takPassword }
            }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "weartak-plugin-lattice"
include(":core", ":app")
includeBuild(".dependencies/sdk") {
    dependencySubstitution {
        substitute(module("com.weartak:plugin-sdk")).using(project(":"))
    }
}
