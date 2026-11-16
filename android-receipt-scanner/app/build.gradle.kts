plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ro.cretu.bonscanner"
    compileSdk = 34
    defaultConfig {
        applicationId = "ro.cretu.bonscanner"
        minSdk = 26; targetSdk = 34; versionCode = 5; versionName = "1.4"
    }
    // cheie de semnare fixă: actualizările se instalează peste versiunea veche, fără dezinstalare
    signingConfigs {
        getByName("debug") {
            storeFile = file("contabil-primar.keystore")
            storePassword = "contabilprimar"
            keyAlias = "contabilprimar"
            keyPassword = "contabilprimar"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
