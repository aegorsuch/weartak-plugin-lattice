plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.weartak.plugin.lattice"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.weartak.plugin.lattice"
        minSdk = 30
        targetSdk = 34
        versionCode = providers.environmentVariable("CI_PIPELINE_IID").orElse("1").get().toInt()
        versionName = "5.8.4"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    val releaseKeystore = providers.environmentVariable("WEARTAK_RELEASE_KEYSTORE_FILE").orNull
    if (releaseKeystore != null) {
        fun signingValue(name: String): String =
            providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)
                ?: error("Missing release signing variable: $name")
        val releaseSigning = signingConfigs.create("weartakRelease") {
            storeFile = rootProject.file(releaseKeystore).also {
                check(it.isFile) { "WearTAK release keystore missing." }
            }
            storePassword = signingValue("RELEASE_STORE_PASSWORD")
            keyAlias = signingValue("RELEASE_KEY_ALIAS")
            keyPassword = signingValue("RELEASE_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = releaseSigning
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            if (gradle.extra.has("testRepositoryUser")) {
                it.systemProperty("robolectric.dependency.repo.url", "https://artifacts.tak.gov/artifactory/maven-release")
                it.systemProperty("robolectric.dependency.repo.username", requireNotNull(gradle.extra["testRepositoryUser"]))
                it.systemProperty("robolectric.dependency.repo.password", requireNotNull(gradle.extra["testRepositoryPassword"]))
            }
        }
    }
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":core"))
    implementation("com.weartak:plugin-sdk:0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
tasks.register<Copy>("packageDebugArtifact") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/lattice/debug"))
    val pipeline = providers.environmentVariable("CI_PIPELINE_IID").orElse("0").get()
    rename { "WearTAK-Plugin-Lattice-5.8.4-p$pipeline.apk" }
}
val verifySdkRevision by tasks.registering {
    doLast {
        val expected = rootProject.file("sdk-revision.txt").readText().trim()
        val sdk = rootProject.file(".dependencies/sdk")
        val actual = providers.exec {
            commandLine("git", "-C", sdk.absolutePath, "rev-parse", "HEAD")
        }.standardOutput.asText.get().trim()
        val dirty = providers.exec {
            commandLine("git", "-C", sdk.absolutePath, "status", "--porcelain", "--untracked-files=all")
        }.standardOutput.asText.get().trim()
        check(expected.matches(Regex("[a-f0-9]{40}")) && actual == expected && dirty.isEmpty()) {
            "SDK source differs from pin. Run python ci/prepare_sdk.py with a clean pinned checkout."
        }
        check(sdk.resolve("LICENSE").isFile) { "SDK license missing." }
    }
}
val legalAssets by tasks.registering(Sync::class) {
    from(rootProject.projectDir) { include("LICENSE", "NOTICE", "third_party/Apache-2.0.txt") }
    into(layout.buildDirectory.dir("generated/legalAssets/legal"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/legalAssets"))
tasks.named("preBuild") { dependsOn(verifySdkRevision, legalAssets) }
