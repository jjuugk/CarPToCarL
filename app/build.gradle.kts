import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 版本号约定：`MAJOR.MINOR.PATCH`（语义化版本）。
 *
 * `versionCode` 由 `versionName` **推导**而不是手填两处——两者不同步是更新失效的
 * 经典原因（系统只看 versionCode，填小了用户装不上新包，且不会有任何提示）。
 * 0.x 为实验阶段；**1.0.0 起为正式版**（1.0.0 → versionCode 10000，仍大于 0.2.19 的 219）。
 * 发布前只需改这一行。
 */
val appVersionName = "1.2.48"
val appVersionCode =
    appVersionName
        .split(".")
        .let { (major, minor, patch) -> major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt() }

/**
 * 发布签名配置：从仓库外的 `keystore.properties` 读取（已在 .gitignore 排除）。
 * 文件缺失时 release 退回 **debug 签名**——这样 fork 者与 CI 无需私钥也能
 * `assembleRelease` 出可安装的包；正式发布务必配置真实签名。
 *
 * 密钥泄露 = 任何人可签发你的更新，因此 keystore 与密码**绝不入库**。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps =
    Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null
val carPlus = providers.gradleProperty("carPlus").map(String::toBoolean).getOrElse(false)
val carPlusSdk = providers.gradleProperty("carPlusSdk").orNull?.let { rootProject.file(it) }
val carPlusSdkDex = providers.gradleProperty("carPlusSdkDex").orNull?.let { rootProject.file(it) }
val carPlusSdkStubs = providers.gradleProperty("carPlusSdkStubs").orNull?.let { rootProject.file(it) }
val carPlusSdkAssets = providers.gradleProperty("carPlusSdkAssets").orNull?.let { rootProject.file(it) }
val carPlusSdkJni = providers.gradleProperty("carPlusSdkJni").orNull?.let { rootProject.file(it) }
val carPlusRelayAssets = providers.gradleProperty("carPlusRelayAssets").orNull?.let { rootProject.file(it) }
val carPlusAudioRelay = carPlusSdkDex != null && carPlusRelayAssets != null
val carPlusVideoRelay = carPlusSdkDex != null
val carPlusRuntime = carPlus && (carPlusSdk != null || carPlusSdkDex != null)
val carPlusDebuggable = providers.gradleProperty("carPlusDebuggable").map(String::toBoolean).getOrElse(!carPlusRuntime)
val carPlusApkName = "CarPToCar+ICCOA.apk"
if (carPlus) {
    require(carPlusSdk == null || carPlusSdkDex == null) {
        "Choose one Car+ runtime input: an AAR or a DEX with SDK stubs"
    }
    require(carPlusSdk?.let { it.isFile && it.extension == "aar" } ?: (carPlusSdkStubs?.isFile == true)) {
        "Car+ needs -PcarPlusSdk=/path/to/receiver.aar or -PcarPlusSdkStubs=/path/to/icar-sdk-stubs.jar"
    }
    require(carPlusSdkAssets == null || carPlusRuntime && carPlusSdkAssets.resolve("ccd.data").isFile) {
        "-PcarPlusSdkAssets requires a Car+ runtime and an assets directory containing ccd.data"
    }
    if (carPlusSdkDex != null) {
        require(carPlusSdkDex.isFile && carPlusSdkDex.extension == "dex" && carPlusSdkAssets != null) {
            "-PcarPlusSdkDex requires a DEX file and -PcarPlusSdkAssets containing ccd.data"
        }
    }
    require(carPlusSdkJni == null || carPlusRuntime && carPlusSdkJni.isDirectory) {
        "-PcarPlusSdkJni requires a Car+ runtime and a directory with ABI subdirectories"
    }
    require(carPlusRelayAssets == null || carPlusRuntime &&
        listOf("injector", "relay.js", "client.dex", "server.dex", "COPYING").all {
            carPlusRelayAssets.resolve("iccoa-relay/$it").isFile
        }) {
        "-PcarPlusRelayAssets must contain the prepared ICCOA relay assets"
    }
    layout.buildDirectory.set(layout.projectDirectory.dir("build/carplus"))
}

android {
    namespace = "io.github.shaonialife.carptocar"
    compileSdk = if (carPlus) 36 else 34

    defaultConfig {
        applicationId = if (carPlus) "io.github.shaonialife.carptocar.carplus" else "io.github.shaonialife.carptocar"
        minSdk = 31
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("boolean", "CAR_PLUS", carPlus.toString())
        buildConfigField("boolean", "CAR_PLUS_RUNTIME", carPlusRuntime.toString())
        buildConfigField("boolean", "CAR_PLUS_AUDIO_RELAY", carPlusAudioRelay.toString())
        buildConfigField("boolean", "CAR_PLUS_VIDEO_RELAY", carPlusVideoRelay.toString())
        if (carPlus) manifestPlaceholders["carPlusRuntime"] = carPlusRuntime.toString()
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // **不做代码混淆**：本项目依赖反射（SystemProperties、Shizuku API）与
            // 自定义 AIDL，R8 需要额外 keep 规则；首版先保证可运行，混淆留待后续。
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
        debug {
            // **不要加 applicationIdSuffix**：本项目 debug 包就是日常使用的包——
            // Shizuku 授权是按包名授予的，改包名会要求重新授权。
            if (carPlus) {
                // ColorOS checks ELF alignment on 4 KB phones for debuggable APKs.
                isDebuggable = carPlusDebuggable
            }
            versionNameSuffix =
                when {
                    !carPlus -> "-debug"
                    carPlusDebuggable -> "-carplus-debug"
                    else -> "-carplus"
                }
        }
    }

    sourceSets["main"].java.srcDir(if (carPlus) "src/carPlus/java" else "src/carLife/java")
    if (carPlus) {
        sourceSets["debug"].manifest.srcFile("src/carPlus/AndroidManifest.xml")
        sourceSets["debug"].res.srcDir("src/carPlus/res")
        carPlusSdkAssets?.let { sourceSets["debug"].assets.srcDir(it) }
        carPlusRelayAssets?.let { sourceSets["debug"].assets.srcDir(it) }
        carPlusSdkJni?.let { sourceSets["debug"].jniLibs.srcDir(it) }
        sourceSets["androidTest"].java.srcDir("src/carPlusTest/java")
        sourceSets["androidTest"].assets.srcDir("src/carPlusTest/assets")
        defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (carPlus && carPlusSdkDex != null) packaging.jniLibs.useLegacyPackaging = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    lint {
        // 新代码引入 lint 问题（error 级）直接使构建失败；存量问题在基线文件中豁免。
        //  ./gradlew :app:lintDebug          —— 检查（pre-commit 钩子提交前自动执行）
        //  ./gradlew :app:updateLintBaseline —— 修复问题后重建基线（有意豁免时）
        checkReleaseBuilds = false
        abortOnError = true
        baseline = file("lint-baseline.xml")
        disable += "Instantiatable"
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        if (carPlus) variant.enable = false
    }
}

if (carPlusRuntime && carPlusSdkDex == null) {
    tasks.register<Copy>("packageCarPlusRuntime") {
        group = "build"
        description = "Builds the installable Car+ APK with the supplied ICCOA runtime AAR"
        dependsOn("assembleDebug")
        from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
        into(layout.buildDirectory.dir("outputs/apk/carplus-runtime"))
        rename { carPlusApkName }
    }
} else if (carPlusRuntime) {
    val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
    val baseApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val packagedApk = layout.buildDirectory.file("outputs/apk/carplus-runtime/$carPlusApkName")
    val debugKeyStore = checkNotNull(android.signingConfigs.getByName("debug").storeFile)
    val packagingScript = rootProject.file("scripts/package-carplus-runtime.py")
    tasks.register<Exec>("packageCarPlusRuntime") {
        group = "build"
        description = "Builds the installable Car+ APK with the supplied ICCOA runtime DEX"
        dependsOn("assembleDebug")
        inputs.files(baseApk, carPlusSdkDex, debugKeyStore, packagingScript)
        inputs.property("carPlusAudioRelay", carPlusAudioRelay)
        if (carPlusAudioRelay) inputs.file(rootProject.file("scripts/PatchCarPlusAudio.java"))
        inputs.file(rootProject.file("scripts/PatchCarPlusVideo.java"))
        outputs.file(packagedApk)
        doFirst {
            commandLine(
                "python3",
                packagingScript,
                baseApk.get().asFile,
                carPlusSdkDex,
                sdkDirectory.get().asFile.resolve("build-tools/34.0.0"),
                debugKeyStore,
                packagedApk.get().asFile,
            )
            if (carPlusAudioRelay) args("--audio-java", file(System.getProperty("java.home")).resolve("bin/java"))
            args("--video-java", file(System.getProperty("java.home")).resolve("bin/java"))
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // CarPlay 风格桌面：分页网格
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // AirPlay 配对密码学：X25519/Ed25519/ChaCha20-Poly1305/HKDF-SHA512
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    if (carPlus) {
        androidTestImplementation("androidx.test:runner:1.5.2")
        androidTestImplementation("androidx.test.ext:junit:1.1.5")
        if (carPlusSdk != null) {
            implementation(files(carPlusSdk))
        } else {
            compileOnly(files(checkNotNull(carPlusSdkStubs)))
        }
        if (carPlusRuntime) implementation("androidx.room:room-runtime:2.4.3")
    }

    testImplementation("junit:junit:4.13.2")
}
