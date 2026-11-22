import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.kayloh20.kyliemail"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kayloh20.kyliemail"
        minSdk = 26
        targetSdk = 36
        // CI's APK builds count up so each one installs over the previous one (see android.yml).
        // Everything else, tests included, stays at 1, so an unchanged app reuses its test results.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "0.1.0"

        // Firebase settings for push from the relay, read from google-services.json the way Google's
        // plugin would. Firebase starts up from these resources on its own.
        val firebase = file("google-services.json").takeIf { it.exists() }?.let {
            @Suppress("UNCHECKED_CAST")
            groovy.json.JsonSlurper().parse(it) as Map<String, Any?>
        }
        if (firebase != null) {
            val project = firebase["project_info"] as Map<*, *>
            val client = (firebase["client"] as List<*>).map { it as Map<*, *> }.first {
                ((it["client_info"] as Map<*, *>)["android_client_info"] as Map<*, *>)["package_name"] == applicationId
            }
            val apiKey = ((client["api_key"] as List<*>).first() as Map<*, *>)["current_key"] as String
            resValue("string", "google_app_id", (client["client_info"] as Map<*, *>)["mobilesdk_app_id"] as String)
            resValue("string", "google_api_key", apiKey)
            resValue("string", "gcm_defaultSenderId", project["project_number"] as String)
            resValue("string", "project_id", project["project_id"] as String)
        }
    }

    // Release builds are signed with a private key supplied by the environment (GitHub secrets in CI).
    // The key is never stored in the repository.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storeType = "PKCS12"
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "kyliemail"
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Lets a development build sit next to the release app instead of replacing it.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    testOptions {
        // Screenshot tests render real layouts, fonts and colors on the JVM.
        unitTests.isIncludeAndroidResources = true
        // Write the images to app/build/screenshots on every run.
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            // Each Robolectric test loads a whole Android app: give them room.
            it.maxHeapSize = "2g"
            // Run test classes side by side, up to 4 at a time (about 8 GB at most). On CI's 4 cores this
            // takes the tests from about 4.4 to 3 minutes; KeepAllMailTest, the slowest class, sets the floor.
            it.maxParallelForks = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            // CI skips them: they download a 200 MB Android runtime and are for looking at, not pass/fail.
            if (System.getenv("CI") == "true") it.filter.excludeTestsMatching("*ScreenshotTest")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.md",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)
    implementation(libs.webkit)
    implementation(libs.coroutines.android)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.android.mail)
    implementation(libs.android.activation)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    // A real IMAP and SMTP server for tests; it uses the app's own mail library instead of bringing its own.
    testImplementation(libs.greenmail) { exclude(group = "com.sun.mail") }
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
