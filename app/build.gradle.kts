import java.util.Properties

plugins { id("com.android.application") }

val signing = Properties()
val signingFile = providers.gradleProperty("pkuAutoBusSigningProperties")
    .orElse(providers.environmentVariable("PKUAUTOBUS_SIGNING_PROPERTIES")).orNull?.let(::file)
signingFile?.let {
    require(it.isFile) { "Signing properties file not found: ${it.absolutePath}" }
    it.inputStream().use { input -> signing.load(input) }
}

android {
    namespace = "app.pkuautobus"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.pkuautobus"
        minSdk = 26
        targetSdk = 36
        versionCode = 2 // Keep installed builds upgradeable without clearing app data.
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (signing.isNotEmpty()) signingConfigs.create("localRelease") {
        storeFile = file(signing.getProperty("storeFile"))
        storePassword = signing.getProperty("storePassword")
        keyAlias = signing.getProperty("keyAlias")
        keyPassword = signing.getProperty("keyPassword")
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = false
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("test").resources.directories.add("src/main/assets")
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
