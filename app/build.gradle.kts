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
val appVersionName = "1.1.3"
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

android {
    namespace = "io.github.shaonialife.carptocar"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.shaonialife.carptocar"
        minSdk = 31
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
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
            versionNameSuffix = "-debug"
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
        aidl = true
        buildConfig = true
    }

    lint {
        // 新代码引入 lint 问题（error 级）直接使构建失败；存量问题在基线文件中豁免。
        //  ./gradlew :app:lintDebug          —— 检查（pre-commit 钩子提交前自动执行）
        //  ./gradlew :app:updateLintBaseline —— 修复问题后重建基线（有意豁免时）
        abortOnError = true
        baseline = file("lint-baseline.xml")
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

    testImplementation("junit:junit:4.13.2")
}
