plugins { id("com.android.application"); kotlin("android"); id("com.google.devtools.ksp") }
android {
    namespace = "ai.backfm"
    compileSdk = 34
    defaultConfig {
        applicationId = "ai.backfm"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "vehicle"
    productFlavors {
        create("demo") { dimension = "vehicle"; applicationIdSuffix = ".demo" }
        create("pleos") { dimension = "vehicle" }
    }
    buildFeatures { buildConfig = true }
    testBuildType = providers.gradleProperty("backfmTestBuildType").getOrElse("debug")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
            val keyPath = System.getenv("BACKFM_KEYSTORE_PATH")
            if (!keyPath.isNullOrBlank()) {
                signingConfig = signingConfigs.create("submission") {
                    storeFile = file(keyPath)
                    storePassword = System.getenv("BACKFM_STORE_PASSWORD")
                    keyAlias = System.getenv("BACKFM_KEY_ALIAS")
                    keyPassword = System.getenv("BACKFM_KEY_PASSWORD")
                }
            }
        }
    }
    useLibrary("android.car", false)
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    "pleosImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
