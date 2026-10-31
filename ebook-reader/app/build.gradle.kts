plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ebookreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ebookreader"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    // 데모용 서명 키. 저장소에 함께 올려서 누가 빌드해도 같은 서명이 되게 한다
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
    implementation("com.google.android.material:material:1.12.0")
    // 마크다운(.md)을 서식 있는 텍스트(Spanned)로 바꿔주는 라이브러리
    implementation("io.noties.markwon:core:4.6.2")
    // PDF에서 글자를 뽑아내는 라이브러리 (구글 드라이브는 구글 문서도 PDF로 넘겨준다)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
