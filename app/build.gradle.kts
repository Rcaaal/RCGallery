plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

android {
    namespace = "com.example.rcgallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.rcgallery"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    // 自定义 APK 输出命名：RCGallery_年月日_时分.apk
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val date = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            output.outputFileName = "RCGallery_${date}.apk"
        }
    }

    // 打包完成后自动归档到 apk_archive（永不删除）
    afterEvaluate {
        tasks.matching { it.name.startsWith("assemble") }.configureEach {
            doLast {
                val variantName = name.replace("assemble", "").replaceFirstChar { it.lowercase() }
                val apkDir = file("build/outputs/apk/$variantName")
                apkDir.listFiles()?.forEach { apk ->
                    if (apk.name.endsWith(".apk") && !apk.name.contains("unsigned")) {
                        val archiveDir = file("${rootProject.projectDir}/apk_archive")
                        mkdir(archiveDir)
                        val targetFile = file("${archiveDir}/${apk.name}")
                        if (!targetFile.exists()) {
                            apk.copyTo(targetFile)
                            println("📦 APK 归档: ${targetFile.absolutePath}")
                        }
                    }
                }
            }
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose (via BOM)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Coil — 图片 + 视频缩略图 + GIF（Compose 版 + 原生 ImageView 版）
    implementation(libs.coil.compose)
    implementation(libs.coil)
    implementation(libs.coil.video)

    // RecyclerView — 原生高性能网格
    implementation(libs.recyclerview)

    // Media3 — 视频播放 + PiP MediaSession + SMB 缓存
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ffmpeg.decoder)  // AV1 + WMV (Jellyfin FFmpeg 软解)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    // media3-datasource 包含 SimpleCache/CacheDataSource 等缓存类
    implementation(libs.media3.datasource)

    // Room — 自定义相册 / 收藏 / 回收站
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // jcifs-ng — SMB 网络共享访问
    implementation(libs.jcifs.ng)

    // ZXing core - 本地生成 B 站扫码登录二维码，不启用扫码 Activity
    implementation("com.google.zxing:core:3.5.4")

    // yt-dlp Android runtime; downloads and muxing remain native in this app.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
}
