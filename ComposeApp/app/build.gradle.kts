import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 签名配置：从项目根 keystore.properties 读取（本地文件，不入库，见 .gitignore）。
// 文件缺失时 release 不签名（仍可构建 debug / 未签名 release）。
val keystoreProps = Properties().apply {
    // 项目根 = ComposeApp 的上级目录（class/）
    val root = rootProject.projectDir.parentFile
    val f = File(root, "keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val hasSigning = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.example.composeapp"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.example.composeapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.7.1"

        // 仅保留 arm64-v8a（真机为麒麟 arm64 芯片）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    if (hasSigning) {
        signingConfigs {
            create("release") {
                val rootDir = rootProject.projectDir.parentFile
                storeFile = File(rootDir, keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) {
                // 正式签名（密钥来自 keystore.properties，不在仓库）
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME：关于页/设置页统一读取，避免多处硬编码漂移
        buildConfig = true
    }

    lint {
        // lifecycle 2.8.x lint detector 与 Kotlin 2.1.21 K2 UAST 的已知崩溃
        // （NonNullableMutableLiveDataDetector IncompatibleClassChangeError），
        // 本应用不使用 LiveData，禁用该检查器即可通过 release 构建
        disable += "NullSafeMutableLiveData"
    }
}

ksp {
    // Room schema 导出：schema JSON 入库，配合 MigrationTestHelper 做迁移测试
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose + Material 3：material3 1.4.0（Expressive 组件/动效进入稳定线），
    // 显式指定版本，其余由 BOM 统一管理；1.4.0 不再传递依赖 material-icons，需显式引入
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Room（KSP 编译期处理）
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // PaddleOCR SDK（PP-OCRv6 small，ONNX Runtime 推理）
    implementation(project(":ppocr-sdk"))

    // PDF 渲染：系统 PdfRenderer（本 PDF 为 STSong-Light 非嵌入字体，
    // PDFBox 无对应字体渲染出豆腐块，故回退系统渲染器）
    // 保留 PDFBox 依赖备查；如需 PDFBox 渲染需额外打包 CJK 字体 + FontMapper
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // 单元测试（ScheduleXlsParser 班级课表 Excel 解析等）
    testImplementation("junit:junit:4.13.2")
}
