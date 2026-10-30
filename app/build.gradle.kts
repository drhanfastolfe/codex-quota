plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "es.codexquota.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.codexquota.mobile.beta"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.2.0-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keyStorePath = providers.environmentVariable("CODEX_QUOTA_KEYSTORE").orNull
    if (keyStorePath != null) {
        signingConfigs {
            create("beta") {
                storeFile = file(keyStorePath)
                storePassword = providers.environmentVariable("CODEX_QUOTA_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("CODEX_QUOTA_KEY_ALIAS").getOrElse("codex-quota-beta")
                keyPassword = providers.environmentVariable("CODEX_QUOTA_KEY_PASSWORD").orNull ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keyStorePath != null) signingConfig = signingConfigs.getByName("beta")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort",
                "http.nonProxyHosts", "javax.net.ssl.trustStore").forEach { key ->
                System.getProperty(key)?.let { value -> it.systemProperty(key, value) }
            }
            it.maxHeapSize = "1g"
            it.maxParallelForks = 1
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
