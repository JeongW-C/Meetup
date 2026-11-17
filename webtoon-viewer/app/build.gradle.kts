plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.webtoonviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.webtoonviewer"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // 데모용 서명 키 (이북리더와 같은 파일). 저장소에 함께 올려서 누가 빌드해도 같은 서명이 되게 한다
    // (그래야 새 버전 APK를 기존 앱 위에 덮어서 설치할 수 있다).
    // Play 스토어에 올릴 때는 비공개 키로 바꿔야 한다.
    signingConfigs {
        create("demo") {
            storeFile = rootProject.file("demo.keystore")
            storePassword = "android"
            keyAlias = "demo"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("demo")
        }
        release {
            signingConfig = signingConfigs.getByName("demo")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
