plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = "com.frobill"
version = "1.0.0"

android {
    namespace = "com.frobill.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Chrome Custom Tabs.
    implementation("androidx.browser:browser:1.8.0")
    // ActivityResultContract / registerForActivityResult.
    api("androidx.activity:activity:1.9.1")

    testImplementation("junit:junit:4.13.2")
}

// Publication locale uniquement (`publishToMavenLocal`) : coordonnées com.frobill:frobill-android:1.0.0.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.frobill"
                artifactId = "frobill-android"
                version = "1.0.0"
            }
        }
    }
}
