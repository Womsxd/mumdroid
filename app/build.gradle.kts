plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

val gitHash: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    workingDir = rootProject.projectDir
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifEmpty { "unknown" }

android {
    namespace = "dev.woms.mumdroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.woms.mumdroid"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("Long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        buildConfigField("String", "versionCodeName", "\"Mondstadt\"")
        buildConfigField("String", "versionCodeNameZH", "\"蒙德\"")

        ndkVersion = "30.0.15729638"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Enables R8 code shrinking and resource shrinking (AGP 9.3+ DSL).
            optimization {
                enable = true
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    packaging {
        resources {
            excludes.add("META-INF/LICENSE.md")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.concentus)
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.protobuf.javalite)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}