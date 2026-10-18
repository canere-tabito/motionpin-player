import com.android.build.api.artifact.SingleArtifact

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.caneretabito.motionpin"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "28.1.13356709"

    defaultConfig {
        applicationId = "com.caneretabito.motionpin"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0-alpha2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // A canonical, durably backed-up release key must be supplied externally.
            // This build deliberately has no fallback signing configuration.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // MediaPipe must load the checked offline model directly from the APK assets.
    androidResources { noCompress += "tflite" }
    packaging {
        jniLibs { useLegacyPackaging = false }
        resources.merges += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kapt {
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.mediapipe:tasks-vision:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    kapt("androidx.room:room-compiler:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Fail the local build if the exact offline person-detector model is unavailable.
val prepareTrackerModel by tasks.registering(Exec::class) {
    commandLine("bash", rootProject.file("scripts/setup-tracker-model.sh").absolutePath)
}
tasks.named("preBuild") { dependsOn(prepareTrackerModel) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(prepareTrackerModel)
}

// Kotlin compilation and debug tests cannot detect R8 removing fields/methods
// accessed through protobuf reflection or MediaPipe JNI. Inspect the actual DEX.
val verifyReleaseRuntimeContracts by tasks.registering(Exec::class) {
    dependsOn("packageRelease")
    doFirst {
        val aars = configurations.getByName("releaseRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts.filter {
                it.moduleVersion.id.group == "com.google.mediapipe" &&
                    it.name in setOf("tasks-core", "tasks-vision") && it.extension == "aar"
            }.map { it.file }.sortedBy { it.name }
        check(aars.size == 2) { "Both MediaPipe AARs are required for the Release runtime contract gate" }
        commandLine(listOf("python3", rootProject.file("scripts/verify-protobuf-apk.py").absolutePath,
            "--apk", layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk").get().asFile.absolutePath,
            "--mapping", layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile.absolutePath,
            "--report", layout.buildDirectory.file("reports/release-runtime-contracts.json").get().asFile.absolutePath
        ) + aars.flatMap { listOf("--aar", it.absolutePath) })
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyReleaseRuntimeContracts)
}

// Inspect merged manifests, because a transitive AAR can reintroduce network
// permissions even when the application's source manifest never declares them.
androidComponents {
    onVariants(selector().all()) { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val verifyOfflineManifest = tasks.register<Exec>("verify${variantName}OfflineManifest") {
            inputs.file(mergedManifest)
            doFirst {
                commandLine("python3", rootProject.file("scripts/verify-offline-manifest.py").absolutePath,
                    mergedManifest.get().asFile.absolutePath)
            }
        }
        tasks.matching {
            it.name == "compile${variantName}Kotlin" || it.name == "lint$variantName" ||
                it.name == "test${variantName}UnitTest"
        }.configureEach { dependsOn(verifyOfflineManifest) }
        tasks.named("check") { dependsOn(verifyOfflineManifest) }
    }
}
