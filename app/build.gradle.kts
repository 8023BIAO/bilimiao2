
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.serialization)
}

android {
    namespace = "com.a10miaomiao.bilimiao"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.a10miaomiao.bilimiao.mod"
        minSdk = 23
        targetSdk = 36
        versionCode = 53
        versionName = "v2026.06.24-01"

        flavorDimensions("default")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 保留全ABI
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "bilimiao dev")
            manifestPlaceholders["channel"] = "Development"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 跳过 lintVital，节省大量时间
            lint { checkReleaseBuilds = false }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 使用debug签名（指纹A9:7D...）
            signingConfig = signingConfigs.getByName("debug")
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    productFlavors {
        create("foss") {
            dimension = flavorDimensionList[0]
            manifestPlaceholders["channel"] = "FOSS"
        }
    }

    compileOptions {
        // Flag to enable support for the new language APIs
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        checkReleaseBuilds = true
        abortOnError = false
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kodein.di) // 依赖注入
    implementation(libs.jupnp.core)
    implementation(libs.jupnp.support)

    implementation(libs.kongzue.dialogx) {
        exclude("com.github.kongzue.DialogX", "DialogXInterface")
    }
    implementation(libs.materialkolor)


    implementation(libs.splitties.android.base)
    implementation(libs.splitties.android.base.with.views.dsl)
    implementation(libs.splitties.android.appcompat)
    implementation(libs.splitties.android.appcompat.with.views.dsl)
    implementation(libs.splitties.android.material.components)
    implementation(libs.splitties.android.material.components.with.views.dsl)

    implementation(libs.mojito)
    implementation(libs.mojito.sketch)
    implementation(libs.mojito.glide)

    // 播放器相关
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.decoder)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.gsy.video.player)

    implementation(libs.okhttp3)
    implementation(libs.pbandk.runtime)
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)
    implementation(libs.microg.safeparcel)

    implementation(project(":bilimiao-comm"))
    implementation(project(":bilimiao-download"))
    implementation(project(":bilimiao-cover"))
    implementation(project(":bilimiao-compose"))
    // 弹幕引擎
    implementation(project(":DanmakuFlameMaster"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}