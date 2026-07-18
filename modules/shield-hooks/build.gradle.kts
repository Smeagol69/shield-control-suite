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
    namespace = "dev.roesler.shieldhooks"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.roesler.shieldhooks"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    val shieldHooksKeyStore = localProperties.getProperty("shieldHooks.keystore")
        ?.let(rootProject::file)
        ?.takeIf { it.isFile }

    signingConfigs {
        if (shieldHooksKeyStore != null) {
            create("shieldHooksLocal") {
                storeFile = shieldHooksKeyStore
                storePassword = localProperties.getProperty("shieldHooks.storePassword")
                keyAlias = localProperties.getProperty("shieldHooks.keyAlias")
                keyPassword = localProperties.getProperty("shieldHooks.keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("shieldHooksLocal") ?: signingConfigs["debug"]
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "META-INF/services/javax.script.ScriptEngineFactory"
        }
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
    debugImplementation(libs.compose.ui.tooling)

    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.beanshell)

    testImplementation(libs.junit)
}
