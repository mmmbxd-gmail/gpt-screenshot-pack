plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.gptscreenshotpack"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.gptscreenshotpack"
        minSdk = 28
        targetSdk = 36
        versionCode = 7
        versionName = "0.6.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Optional local signing; never store the keystore or passwords in the repository.
    val signingPath = providers.environmentVariable("PACK_KEYSTORE").orNull
    if (signingPath != null) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(signingPath)
                storePassword = providers.environmentVariable("PACK_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("PACK_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("PACK_KEY_PASSWORD").get()
            }
            create("release") {
                initWith(getByName("debug"))
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    lint { abortOnError = true; warningsAsErrors = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.heifwriter:heifwriter:1.1.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
