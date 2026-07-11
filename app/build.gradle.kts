import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息：本地读 keystore.properties（gitignored），CI 读环境变量（GitHub Secrets）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signProp(name: String): String? = System.getenv(name) ?: keystoreProps.getProperty(name)

android {
    namespace = "com.snownamida.touchgrass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.snownamida.touchgrass"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.5.1"
    }

    signingConfigs {
        val ksFile = rootProject.file(signProp("KEYSTORE_FILE") ?: "release.keystore")
        if (ksFile.exists()) {
            create("release") {
                storeFile = ksFile
                storePassword = signProp("KEYSTORE_PASSWORD")
                keyAlias = signProp("KEY_ALIAS") ?: "touchgrass"
                keyPassword = signProp("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    // JVM 单测里 android.jar 的 org.json 是 stub，换真实现
    testImplementation("org.json:json:20240303")
}
