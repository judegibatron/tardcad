import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.judegibatron.phoneagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.judegibatron.phoneagent"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // The Anthropic SDK serialises through Jackson reflection; shrinking stays off until
            // keep rules are verified on a device. proguard-rules.pro already carries the rules.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Same applicationId as release on purpose: root/appops grants are keyed by package.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Jackson/Kotlin jars ship duplicate license files and multi-release class dirs.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**",
                "META-INF/*.version",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Official Anthropic Java SDK (Kotlin uses it directly). Includes the OkHttp transport.
    implementation("com.anthropic:anthropic-java:2.60.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // The SDK depends on kotlin-reflect 1.9.x; align it with the compiler version used here.
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.21")

    testImplementation("junit:junit:4.13.2")
}
