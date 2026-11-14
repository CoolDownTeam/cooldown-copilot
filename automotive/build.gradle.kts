import java.util.Properties

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
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 공모전 제출 빌드: 에뮬레이터에서 차속·기어를 직접 넣을 수 없어서, 녹화한 차량 신호 재생(--es scenario)을 release에서도 연다.
        // 정식 출시 때는 false로 바꾼다 (재생 중에는 실제 차량 신호 대신 녹화 신호를 쓰기 때문)
        buildConfigField("boolean", "DEMO_SCENARIOS", "true")
    }

    // release 서명 키는 저장소 밖(~/.android-keys/cooldown-release.properties)에 둔다. 없으면 서명 없이 빌드된다
    val releaseKeyFile = File(System.getProperty("user.home"), ".android-keys/cooldown-release.properties")
    signingConfigs {
        if (releaseKeyFile.exists()) {
            val key = Properties().apply { releaseKeyFile.inputStream().use { load(it) } }
            create("release") {
                storeFile = File(key.getProperty("storeFile"))
                storePassword = key.getProperty("storePassword")
                keyAlias = key.getProperty("keyAlias")
                keyPassword = key.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 공모전 제출본: 에뮬레이터에서 검증한 코드를 그대로 담도록 코드 축소(R8)는 하지 않는다
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // AAOS CarPropertyManager (차속)
    useLibrary("android.car")
    // JVM 단위 테스트에서 android.util.Log 등 스텁이 예외 대신 기본값을 돌려주게 한다
    testOptions {
        unitTests.isReturnDefaultValues = true
        // 시나리오 테스트가 CSV를 직접 읽으므로, CSV가 바뀌면 테스트를 다시 돌린다
        unitTests.all { it.inputs.dir("src/main/assets/scenarios") }
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

// 공모전 GitHub 제출 규칙: release APK를 저장소 apk/ 폴더에 팀명_앱이름_release.apk로 둔다
tasks.register<Copy>("exportReleaseApk") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*-release.apk") }
    rename { "CoolDownTeam_CooldownCopilot_release.apk" }
    into(rootProject.layout.projectDirectory.dir("apk"))
}
