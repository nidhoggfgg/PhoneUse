plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.nidhoggfgg.phoneuse"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ai.nidhoggfgg.phoneuse"
        minSdk = 30
        targetSdk = 37
        versionCode = 4
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }
    bundle {
        // Keep translated notifications available after an app language change.
        language { enableSplit = false }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// The real-device suite launches the separate fixture package. Installation must finish before
// instrumentation starts; independent command-line tasks may otherwise execute in parallel.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":fixture:installDebug")
}

// Ship the current source client and references, never credentials or arbitrary skill files.
// Stable ZIP metadata makes the digest in a copied onboarding URL reproducible.
val bundledSkillFiles = mapOf(
    "LICENSE" to "LICENSE",
    "SKILL.md" to "skills/phone-use/SKILL.md",
    "README.zh-CN.md" to "skills/phone-use/README.zh-CN.md",
    "agents/openai.yaml" to "skills/phone-use/agents/openai.yaml",
    "scripts/phoneuse_client.py" to "tools/phoneuse_client.py",
    "references/api.md" to "docs/api.md",
    "references/api.zh-CN.md" to "docs/api.zh-CN.md",
    "references/transport-security.md" to "docs/transport-security.md",
    "references/transport-security.zh-CN.md" to "docs/transport-security.zh-CN.md",
)
val bundlePhoneUseSkill = tasks.register<Zip>("bundlePhoneUseSkill") {
    archiveFileName.set("phone-use.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/skillAssets"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    includeEmptyDirs = false
    bundledSkillFiles.forEach { (target, source) ->
        from(rootProject.file(source)) {
            into("phone-use/" + target.substringBeforeLast('/', ""))
            rename { target.substringAfterLast('/') }
        }
    }
    doFirst {
        bundledSkillFiles.values.forEach { source ->
            check(rootProject.file(source).isFile) {
                "Missing $source; run git submodule update --init --recursive"
            }
        }
    }
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundlePhoneUseSkill, Zip::getDestinationDirectory)
}
// Zip declares archiveFile as its output, not destinationDirectory, so wire execution explicitly.
tasks.named("preBuild").configure { dependsOn(bundlePhoneUseSkill) }
