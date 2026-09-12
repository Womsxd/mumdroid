plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

// Release signing configuration + expected-signature digest live in the
// isolated signing.gradle.kts script. Applying it evaluates the config and
// publishes the results to the project `extra`; we read them back below.
apply(from = file("signing.gradle.kts"))

val releaseStoreFilePath: String? = extra["releaseSigningStoreFile"] as? String
val releaseStorePassword: String? = extra["releaseSigningStorePassword"] as? String
val releaseKeyAlias: String? = extra["releaseSigningKeyAlias"] as? String
val releaseKeyPassword: String? = extra["releaseSigningKeyPassword"] as? String
val expectedSignatureSha256: String = (extra["releaseSigningSha256"] as? String).orEmpty()

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

    signingConfigs {
        create("release") {
            val storeFilePath = releaseStoreFilePath
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
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
        buildConfigField("String", "EXPECTED_SIGNATURE_SHA256", "\"$expectedSignatureSha256\"")

        ndkVersion = "30.0.16248370"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8 code shrinking + resource shrinking + optimisation (AGP 9.3+ DSL).
            //
            // Debug-info contract for release crash reports: every stack frame
            // must keep the original class name, method name and source line.
            // That is enforced by:
            //   * `-dontobfuscate` + `-keepattributes SourceFile,LineNumberTable`
            //     in src/main/keepRules/rules.keep
            //   * targeted keeps for JNI / Room / protobuf / components in
            //     src/main/keepRules/keep-rules.keep
            // Do NOT add a blanket `-keep class dev.woms.mumdroid.** { *; }`;
            // it silently disables shrinking again.
            optimization {
                enable = true
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
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
    // Local JVM unit tests run against the mockable android.jar, where every
    // android.* method throws `RuntimeException("Stub!")`. Classes such as
    // LibOpusNative log on their failure path (no native lib on the JVM), so
    // without defaults the stub throws inside <clinit> and the whole class
    // fails to initialise. Let the stubs return defaults instead.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
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
