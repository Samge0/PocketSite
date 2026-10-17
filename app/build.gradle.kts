import java.util.Base64
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing: ONE key everywhere (CI + local builds) so APKs install over each other.
// Priority: CI env vars (base64 keystore from GitHub secrets) > local
// app/pocketsite-release.jks + .secrets/*.txt > debug fallback.
val ciKeystoreB64 = System.getenv("KEYSTORE_BASE64")
val ciStorePass = System.getenv("KEYSTORE_PASSWORD")
val ciKeyAlias = System.getenv("KEY_ALIAS")
val ciKeyPass = System.getenv("KEY_PASSWORD")
val hasCiSigning = !ciKeystoreB64.isNullOrBlank() && !ciStorePass.isNullOrBlank()

val localKs = rootProject.file("app/pocketsite-release.jks")
val localSecrets = rootProject.file(".secrets")
fun secret(name: String): String? =
    runCatching {
        val f = localSecrets.resolve("$name.txt")
        if (f.exists()) f.readText().trim().ifBlank { null } else null
    }.getOrNull()
val useLocalSigning = !hasCiSigning && localKs.exists() && secret("KEYSTORE_PASSWORD") != null

android {
    namespace = "com.samge.pocketsite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.samge.pocketsite"
        minSdk = 26
        targetSdk = 35
        versionCode = 101
        versionName = "1.0.1"
    }

    signingConfigs {
        if (hasCiSigning) {
            create("ci") {
                val tmp = File.createTempFile("pocketsite", ".jks")
                tmp.writeBytes(Base64.getDecoder().decode(ciKeystoreB64))
                storeFile = tmp
                storePassword = ciStorePass
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPass
            }
        } else if (useLocalSigning) {
            create("localRelease") {
                storeFile = localKs
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS") ?: "pocketsite"
                keyPassword = secret("KEY_PASSWORD") ?: secret("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = when {
                hasCiSigning -> signingConfigs.getByName("ci")
                useLocalSigning -> signingConfigs.getByName("localRelease")
                else -> null
            }
        }
        debug {
            // local test builds use the release key when present — same signature as
            // GitHub releases, so they install as updates over release APKs
            if (useLocalSigning) {
                signingConfig = signingConfigs.getByName("localRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
