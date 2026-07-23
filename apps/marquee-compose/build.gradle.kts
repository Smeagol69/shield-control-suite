import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

android {
    namespace = "dev.roesler.marquee"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.roesler.marquee"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "2.3.0"
    }

    val marqueeKeyStore = localProperties.getProperty("marquee.keystore")
        ?.let(rootProject::file)
        ?.takeIf { it.isFile }

    signingConfigs {
        if (marqueeKeyStore != null) {
            create("marqueeLocal") {
                storeFile = marqueeKeyStore
                storePassword = localProperties.getProperty("marquee.storePassword")
                keyAlias = localProperties.getProperty("marquee.keyAlias")
                keyPassword = localProperties.getProperty("marquee.keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("marqueeLocal")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("marqueeLocal")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.tv.material)
    implementation(libs.tv.foundation)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
