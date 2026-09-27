import com.google.protobuf.gradle.*
import java.nio.file.Paths

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("com.google.protobuf") // proto
    kotlin("plugin.serialization")
}

android {
    compileSdk = 37
    // 37 这一代开始按 minor 分平台（SDK 里是 android-37.0），要显式声明 minor 才能对上
    compileSdkMinor = 0

    defaultConfig {
        minSdk = 21
        version = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // 凭证加密 AES 密钥：可通过 gradle.properties 的 BILIMIAO_AES_KEY 覆盖，默认值兜底
        // 仅提高反编译门槛，非真正安全（要更高安全性需 NDK native 存储）
        buildConfigField(
            "String",
            "AES_KEY",
            "\"${project.findProperty("BILIMIAO_AES_KEY") as String? ?: "Message Word"}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    namespace = "com.a10miaomiao.bilimiao.comm"

    sourceSets["main"].proto {
        srcDir("src/main/proto") // 模块下的proto文件夹
        include("**/*.proto")
    }
}


protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.12.0"  // 相当于proto编译器
    }
    plugins {
        id("pbandk") {
            artifact = "pro.streem.pbandk:protoc-gen-pbandk-jvm:0.16.0:jvm8@jar"
        }
    }
    generateProtoTasks {
        val generatorModule = "grpc-generator"
        val generatorClass = "cn.a10miaomiao.generator.GrpcServiceGenerator"
        // grpc-generator/build/libs/grpc-generator.jar
        val generatorJarFile = Paths.get(
            project(":grpc-generator").buildDir.path,
            "libs",
            "$generatorModule.jar"
        ).toFile()
        all().forEach { task ->
            task.plugins {
                id("pbandk") {
                    // ★ 必须**无条件**依赖生成器 jar。
                    //   原来写成 `if (!generatorJarFile.exists())`：这个判断发生在**配置期**，
                    //   而 `clean` 是**执行期**才把 jar 删掉 —— 于是
                    //   `./gradlew clean :app:assembleFossRelease` 这种全量编译必然缺包：
                    //   ClassNotFoundException: cn.a10miaomiao.generator.GrpcServiceGenerator
                    //   （增量编译因为 jar 还在而侥幸能过，所以一直没暴露）
                    task.dependsOn(":$generatorModule:jar")
                    option("log=debug")
                    var jarPath = generatorJarFile.path
                    jarPath.indexOf(':')
                        .takeIf { it != -1 }
                        ?.let {
                            // option不能传递`:`符号，故windows情况下只能去除盘符
                            jarPath = jarPath.substring(it + 1, jarPath.length)
                        }
                    option("kotlin_service_gen=${jarPath}|$generatorClass")
                }
            }
        }
    }
}


// ★ Gradle 9 起，Copy 类任务遇到"重名条目"直接报错（Gradle 8 只是警告）。
//   protobuf 插件生成的 processXxxProtoResources 输入有两份同样的 proto：
//   一份来自 src/main/proto，另一份是 AGP 把 proto 当 java 资源拷进 build/intermediates/java_res/… 的，
//   于是必然重名。这里显式指定策略（EXCLUDE = 保留先到的那份，等价于 Gradle 8 的老行为）。
//   用 afterEvaluate 是为了确保在 protobuf / AGP 插件自己配置完这个任务之后再改，
//   否则可能被插件随后的配置覆盖掉。类型用 AbstractCopyTask（Copy/Sync/Jar 都覆盖）。
afterEvaluate {
    tasks.matching { it.name.endsWith("ProtoResources") }.configureEach {
        (this as? org.gradle.api.tasks.AbstractCopyTask)?.duplicatesStrategy =
            org.gradle.api.file.DuplicatesStrategy.EXCLUDE
        logger.lifecycle("[proto] $name 类型=${this::class.java.name} 重复策略已设为 EXCLUDE")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kodein.di)
    implementation(libs.glide)
    implementation(libs.kongzue.dialogx) {
        exclude("com.github.kongzue.DialogX", "DialogXInterface")
    }

    implementation(libs.okhttp3)
    implementation(libs.pbandk.runtime)

    implementation("javax.annotation:javax.annotation-api:1.3.2")

    implementation(project(":DanmakuFlameMaster"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}