import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.oss.licenses)
}
configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")
    resolutionStrategy.eachDependency {
        if (requested.group == "com.atlassian.commonmark") {
            useTarget("org.commonmark:${requested.name}:${libs.versions.commonmark.get()}")
            because("The library moved from com.atlassian.commonmark to org.commonmark, causing duplicate classes")
        }
    }
}
val appVersionMajor = 3
val appVersionMinor = 0
val appVersionPatch = 0

android {
    namespace = "io.github.stardomains3.oxproxion"
    compileSdk = 37

    defaultConfig {
        // Own id, separate from upstream oxproxion. Renamed off grokion; existing
        // grokion installs do not update into this id.
        applicationId = "io.github.warexpor.gradation"
        minSdk = 31
        targetSdk = 36
        // One place to bump. versionCode is derived (3.0.0 -> 30000, 3.4.2 -> 30402), so it can only
        // grow with the version, and Android never refuses an update as a downgrade.
        versionCode = appVersionMajor * 10000 + appVersionMinor * 100 + appVersionPatch
        versionName = "$appVersionMajor.$appVersionMinor.$appVersionPatch"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = false
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    // Release signing comes from Gradle properties (never committed):
    // -Pgradation.storeFile=... -Pgradation.storePassword=... -Pgradation.keyAlias=... -Pgradation.keyPassword=...
    val releaseStoreFile = providers.gradleProperty("gradation.storeFile").orNull
    signingConfigs {
        // Dev-only key, committed so every session signs dev APKs the same way and they
        // install as updates. Standard public debug password; never use it for release.
        create("dev") {
            storeFile = file("dev.keystore")
            storePassword = "android"
            keyAlias = "gradation-dev"
            keyPassword = "android"
        }
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("gradation.storePassword").orNull
                keyAlias = providers.gradleProperty("gradation.keyAlias").orNull
                keyPassword = providers.gradleProperty("gradation.keyPassword").orNull
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")


            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

        }
        getByName("debug") {
            isDebuggable = true
            // Dev APK: arm64 phones + x86_64 emulators only (drops unused 32-bit ABIs).
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        // Dev APK for the phone: release-like R8 build (same keep rules) with real names kept,
        // installed as a separate .dev app and signed with the committed dev key.
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            signingConfig = signingConfigs.getByName("dev")
            matchingFallbacks += listOf("release")
            proguardFiles("proguard-dev.pro")
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
    // A release APK signed with anything but the release key cannot update an installed release,
    // so refuse to build one rather than produce it quietly unsigned or debug-signed.
    gradle.taskGraph.whenReady {
        val releaseBuild = allTasks.any { it.project == project && Regex("^(assemble|bundle|package)Release$").matches(it.name) }
        if (releaseBuild && releaseStoreFile == null) {
            throw GradleException(
                "Release builds must be signed with the release key. Run scripts/release.sh " +
                    "(see docs/RELEASING.md), or pass the -Pgradation.* signing properties."
            )
        }
    }
    // Prefer installed build-tools (avoid AGP auto-download of 35.0.0 when offline/proxy)
    buildToolsVersion = "36.0.0"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    lint {
        // Only the checks that guard the resource and accessibility rules; everything else stays quiet.
        checkOnly += setOf("UnusedResources", "HardcodedText", "ContentDescription", "SmallSp", "ClickableViewAccessibility")
        abortOnError = false
    }
    // Room exports one schema JSON per version (commit them); MigrationTestHelper reads them from
    // the test assets, so the migration test can build an old database and migrate it.
    // Robolectric reads the merged assets of the variant under test, not the test source set's, so
    // the debug build (never shipped: users get dev or release) carries them for the unit tests.
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // The screenshot tests write PNGs outside the declared outputs, so a cache hit would skip them.
            test.outputs.doNotCacheIf("screenshots are side effects") { true }
            // Logic tests only by default (~20 s): the screenshot classes are ~85% of the run.
            // `-Pfull` adds them (UI changes, and always before a release). Naming tests with
            // `--tests` runs exactly those, screenshots included. `-Pfast` is kept as a no-op.
            val named = gradle.startParameter.taskNames.any { it == "--tests" || it.startsWith("--tests=") }
            if (!project.hasProperty("full") && !named) test.filter.excludeTestsMatching("*ScreenshotTest")
            // Robolectric keeps every booted Android around: one JVM for the whole run ran out of heap
            // and then hung for minutes. Two JVMs in parallel, each recycled every 40 test classes.
            test.maxParallelForks = 2
            test.setForkEvery(40)
            test.maxHeapSize = "2g"
            // A hung test fails the run instead of stalling it (the full suite takes ~4 min).
            test.timeout.set(Duration.ofMinutes(8))

        }
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/11/OSGI-INF/MANIFEST.MF",
                // Keep license files for attribution purposes
                // Only exclude problematic duplicates if needed
            )
        }
    }

}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.markwon.simple)
    implementation(libs.markwon.core)
    implementation(libs.markwon.html)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.taskList)
    implementation(libs.markwon.image.coil)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.syntax.highlight)
    implementation(libs.prism4j.core)
    implementation(libs.androidx.documentfile)
    implementation(libs.zxing.embedded)
    implementation(libs.biometric)
    implementation(libs.coil.kt)
    implementation(libs.commonmark.task.list)
    implementation(libs.commonmark.autolink)
    implementation(libs.commonmark.footnotes)
    implementation(libs.commonmark.heading.anchor)
    implementation(libs.commonmark.ext.ins)
    implementation(libs.linkify)
    implementation(libs.gson)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation (libs.okhttp.brotli)
    implementation (libs.androidx.activity.ktx)
    implementation (libs.androidx.fragment.ktx)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
   // implementation(libs.kotlin.stdlib)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.auth)
    implementation(libs.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android) { artifact { type = "aar" } }
    implementation(libs.androidx.core.ktx)
    implementation(libs.openlocationcode)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.oss.licenses.parser)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}