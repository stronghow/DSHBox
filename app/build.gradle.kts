import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

// ---- 运行环境 assets：发布目录与路径 ---------------------------------------------
// 运行环境大层（base/node/android-side/dsh）不进源码仓库，单独放在**仓库外**的发布目录
// runtime/android-assets。下面的相对路径相对 **app/ 模块目录**解析，即
// <仓库>/../runtime/android-assets —— 与仓库同级、不在仓库内（仓库在 /work/DSHBox 时落点是
// /work/runtime/android-assets）。AGP 对不存在的 srcDir 只做静默跳过，于是缺件时会编出
// 「看着成功、其实少了 4 个条目」的包：
//   assets/runtime/android-side.tar.zst（及其 .sha256）、assets/dexopt/baseline.prof（及其 .profm）
// 后果：全新安装 / 清过数据的设备上「在线导入 Linux 层」硬失败（自检未通过：android-side），
// 覆盖升级不受影响（老设备数据分区里已有解包好的层）。故文件末尾的「运行环境 assets 缺件守卫」
// 默认让构建失败，而不是静默丢包。
// 路径可用 -Pdshbox.runtimeAssetsDir=<绝对路径> 覆盖；显式跳过检查用
// -Pdshbox.allowMissingRuntimeAssets=true（理由与告警见守卫处）。
val runtimeAssetsDir = file(
    providers.gradleProperty("dshbox.runtimeAssetsDir").getOrElse("../../runtime/android-assets")
)
val allowMissingRuntimeAssets = providers.gradleProperty("dshbox.allowMissingRuntimeAssets")
    .getOrElse("false")
    .toBoolean()

android {
    namespace = "com.dshbox.app"
    compileSdk = 36

    signingConfigs {
        create("release") {
            // 显式开启 v3：minSdk 29 下 v2 就够装，但 v3 才能支持**密钥轮换**，
            // 且上架校验工具链（apksigner / 商店）普遍期望 v2+v3 同时存在。
            // 只签 v2 时 apksigner 会报 v1=false v3=false，故此处补齐。
            enableV2Signing = true
            enableV3Signing = true
            if (hasReleaseKeystore) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.dshbox.app"
        minSdk = 29
        targetSdk = 36
        // 多语言版本：联合国六语 + 语言选择器 + 硬编码清零 + 布局恒 LTR + i18n 门禁。
        versionCode = 8
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ENABLE_WEBVIEW_DEBUGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ENABLE_WEBVIEW_DEBUGGING", "false")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            // 运行环境大层（base/node/android-side/dsh）不进入源码仓库，单独放在发布目录
            // runtime/android-assets（runtime/ 与 dsh/ 子目录），保证打包后仍是 assets/runtime/*
            // 与 assets/dsh/* 路径。仓库单独 clone 时请先获取 runtime/。
            // 注意这是**相对 app/ 模块目录**的路径（解析为 <仓库>/../runtime/android-assets，仓库外、
            // 与仓库同级，不是 <仓库>/runtime/...）；路径与缺件检查见下方「运行环境 assets 缺件守卫」。
            assets.srcDirs(runtimeAssetsDir)
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // BouncyCastle（bcprov/bcutil/bcpg 三个 jar 各自带一份同名文件）会在这里撞车：
        // "3 files found with path 'META-INF/versions/9/OSGI-INF/MANIFEST.MF'"，
        // 缺这一行整个 assemble 阶段就过不去（debug/release 都一样）。
        // 该文件是给 OSGi 用的，APK 里不需要。仅打包管道层面的排除，不改任何功能代码。
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

// ---- 运行环境 assets 缺件守卫 -----------------------------------------------------
// assets.srcDirs 指向的发布目录在仓库外，缺件时 AGP 只做静默跳过（见文件开头的说明），
// 于是构建"成功"却丢包。这里默认让构建**当场失败**，并给出两种出路；只有显式开关才放行。
val runtimeAssetsNeeded = listOf(
    "runtime/android-side.tar.zst",
    "runtime/android-side.tar.zst.sha256",
    "dexopt/baseline.prof",
    "dexopt/baseline.profm",
)
val runtimeAssetsHelp = """
    |  期望路径：${runtimeAssetsDir.absolutePath}
    |            由 app/build.gradle.kts 的 assets.srcDirs 指定，默认相对 app/ 模块目录解析为
    |            <仓库>/../runtime/android-assets，可用 -Pdshbox.runtimeAssetsDir=<绝对路径> 覆盖。
    |  为什么需要它：运行环境大层（base/node/android-side/dsh）不在源码仓库里，只存在于这个发布目录。
    |            AGP 对不存在的 srcDir 只做静默跳过，缺件时会编出"看着成功"但少了下列条目的包：
    |              assets/runtime/android-side.tar.zst          assets/runtime/android-side.tar.zst.sha256
    |              assets/dexopt/baseline.prof                 assets/dexopt/baseline.profm
    |            这种包装到全新安装或清过数据的设备上，「在线导入 Linux 层」会硬失败（自检未通过：
    |            android-side）；覆盖升级不受影响（老设备数据分区里已有解包好的层）。
    |  出路 ①（推荐）把发布目录摆到正确位置：与仓库**同级**的 runtime/android-assets。
    |            例如仓库在 /work/DSHBox 时，落点是 /work/runtime/android-assets，
    |            而不是 /work/DSHBox/runtime/android-assets。
    |            也可以用 -Pdshbox.runtimeAssetsDir=/abs/path/to/android-assets 指向任意位置。
    |  出路 ② 确实不需要运行环境（只出覆盖升级包 / 只想编译调试）时，显式跳过本检查：
    |              ./gradlew :app:assembleDebug -Pdshbox.allowMissingRuntimeAssets=true
    |            或在 gradle.properties 里写 dshbox.allowMissingRuntimeAssets=true。
    |            此时产物将缺少 assets/runtime/** 与 assets/dexopt/**（影响见上），不可用于全新安装。
""".trimMargin()
if (!runtimeAssetsDir.isDirectory) {
    if (allowMissingRuntimeAssets) {
        logger.warn(
            "[DSHBox] !!! dshbox.allowMissingRuntimeAssets=true：已跳过运行环境 assets 缺件检查。\n" +
                "[DSHBox] !!! 产物将缺少 assets/runtime/** 与 assets/dexopt/**（android-side.tar.zst 及其 .sha256、\n" +
                "[DSHBox] !!! baseline.prof/.profm），把它装到全新安装 / 清过数据的设备上会导致「在线导入 Linux 层」\n" +
                "[DSHBox] !!! 失败（自检未通过：android-side）；只适合覆盖升级包或纯编译调试。\n" +
                "[DSHBox] !!! 发布目录不存在：${runtimeAssetsDir.absolutePath}\n" +
                runtimeAssetsHelp
        )
    } else {
        throw GradleException(
            "[DSHBox] 运行环境 assets 发布目录不存在，已中止构建（默认必须失败，避免静默丢包）。\n" +
                runtimeAssetsHelp
        )
    }
} else {
    val runtimeSubEntries = runtimeAssetsDir.resolve("runtime")
        .listFiles()?.map { it.name }?.sorted() ?: emptyList<String>()
    val dexoptSubEntries = runtimeAssetsDir.resolve("dexopt")
        .listFiles()?.map { it.name }?.sorted() ?: emptyList<String>()
    logger.lifecycle(
        "[DSHBox] 运行环境 assets 发布目录已包含：${runtimeAssetsDir.absolutePath}\n" +
            "[DSHBox]   runtime/ ：${runtimeSubEntries.joinToString(", ").ifEmpty { "（无此子目录或为空）" }}\n" +
            "[DSHBox]   dexopt/ ：${dexoptSubEntries.joinToString(", ").ifEmpty { "（无此子目录或为空）" }}"
    )
    val runtimeAssetsMissing = runtimeAssetsNeeded.filter { !runtimeAssetsDir.resolve(it).isFile }
    if (runtimeAssetsMissing.isNotEmpty()) {
        // 只告警不失败：目录在场说明发布流程已走通，缺条目更可能是"这次只增量发布某一层"（例如只更新
        // dexopt 基线）；且 android-side 只影响清数据后的在线导入，覆盖升级不受影响，为它阻断全部构建
        // 得不偿失。目录**整体**不存在则几乎必然是路径摆错/发布目录没同步，那种情况上面已直接失败。
        logger.warn(
            "[DSHBox] !!! 发布目录存在，但缺少以下条目：${runtimeAssetsMissing.joinToString(", ")}\n" +
                "[DSHBox] !!! 缺 runtime/android-side.tar.zst（或 .sha256）时，全新安装 / 清过数据的设备上\n" +
                "[DSHBox] !!! 「在线导入 Linux 层」会硬失败（自检未通过：android-side）；覆盖升级不受影响，\n" +
                "[DSHBox] !!! 故此处只告警、不中止构建。要出全新安装包请补齐发布目录。"
        )
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":bridge"))
    implementation(project(":sandbox-manager"))
    implementation(project(":terminal-session"))
    implementation(project(":terminal-view"))
    implementation(project(":plugin-manager"))
    // 手机助手适配层。核心层（interlock-relay-core）由它 api 传递，故此处不必再写一行。
    implementation(project(":pilot:dshbox-adapter"))

    implementation(libs.androidx.core.ktx)
    // 应用内语言切换（AppCompatDelegate.setApplicationLocales；
    // API<33 走 appcompat 持久化 + Activity 重建，API 33+ 委托系统 LocaleManager）
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)
    implementation(libs.commons.compress)
    // 代码编辑核心（撤销/搜索/行号），LGPL-2.1-or-later 未修改 aar
    implementation(libs.sora.editor)
    // Markdown 预览（Apache-2.0）
    implementation(libs.markwon.core)
    // Markdown 表格（GFM 扩展，ext-tables；Apache-2.0 同 markwon）
    implementation(libs.markwon.ext.tables)
    // XmlPullParser 仅测试期依赖（kxml2 供 JVM 单测；设备上走平台自带实现——
    // 若随 APK 打包会与平台库类冲突致 R8 失败，且徒增体积）
    testImplementation(libs.kxml2)
    // tar.zst 条目枚举（zstd-jni classes；arm64 .so 已在 jniLibs，零下载）
    implementation(files("$rootDir/libs/zstd-jni-1.5.7-15-classes.jar"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// 注：`:pilot` 摘除后，ABI 过滤脚本（原 pilot/gradle/pilot-abi.gradle，动机来自 OpenCV/ML Kit 的
// 冗余 ABI）一并移除，恢复 AGP 默认全 ABI 打包。若将来重新引入多 ABI 依赖，按需再挂。

