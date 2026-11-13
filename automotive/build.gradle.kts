plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pleos.cooldown"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pleos.cooldown"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // 심사 제출용: debug와 같은 시연 기능(시나리오 재생)을 두고 R8 코드·리소스 축소만 더한다
        create("demo") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            matchingFallbacks += listOf("debug")
            // 실차(arm64-v8a)와 PC 에뮬레이터(x86_64)용 네이티브 라이브러리만 남긴다
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }
    // demo 빌드도 시연 시나리오(src/debug/assets)를 담는다
    sourceSets.getByName("demo").assets.srcDir("src/debug/assets")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    // AAOS CarPropertyManager (차속)
    useLibrary("android.car")
    // JVM 단위 테스트에서 android.util.Log 등 스텁이 예외 대신 기본값을 돌려주게 한다
    testOptions {
        unitTests.isReturnDefaultValues = true
        // 시나리오 테스트가 CSV를 직접 읽으므로, CSV가 바뀌면 테스트를 다시 돌린다
        unitTests.all { it.inputs.dir("src/debug/assets/scenarios") }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Pleos SDK
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)

    // Coroutines
    implementation(libs.coroutines.android)

    // Room (어노테이션 처리는 KSP)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// 공모전 제출용 APK를 저장소 루트 submission/CoolDownCopilot.apk로 복사한다 (*.apk는 gitignore)
tasks.register<Copy>("exportSubmissionApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) { include("*-debug.apk") }
    rename { "CoolDownCopilot.apk" }
    into(rootProject.layout.projectDirectory.dir("submission"))
}

// 같은 시연 기능에 R8 축소를 더한 demo 빌드를 submission/CoolDownCopilot_r8.apk로 복사한다
tasks.register<Copy>("exportSubmissionDemoApk") {
    dependsOn("assembleDemo")
    from(layout.buildDirectory.dir("outputs/apk/demo")) { include("*-demo.apk") }
    rename { "CoolDownCopilot_r8.apk" }
    into(rootProject.layout.projectDirectory.dir("submission"))
}
