plugins { alias(libs.plugins.android.application) }

android {
    namespace = "ai.nidhoggfgg.phoneuse.fixture"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "ai.nidhoggfgg.phoneuse.fixture"
        minSdk = 30
        targetSdk = 37
        versionCode = 4
        versionName = "1.3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
// This exported, controllable application is a test instrument, never a release artifact.
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }
