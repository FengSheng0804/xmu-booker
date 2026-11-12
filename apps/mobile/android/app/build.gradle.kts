import java.util.Properties
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Release credentials stay outside source control. No server is required.
val productionConfig = rootProject.file("../production.json").takeIf { it.exists() }
    ?.let { JsonSlurper().parse(it) as Map<*, *> }
val productionId = providers.gradleProperty("productionApplicationId").orNull
    ?: System.getenv("XMU_ANDROID_APPLICATION_ID")
    ?: (productionConfig?.get("application_id") as? String)
val keyPath = System.getenv("XMU_ANDROID_KEY_PROPERTIES")
val releaseKeys = Properties()
if (!keyPath.isNullOrBlank()) file(keyPath).inputStream().use { releaseKeys.load(it) }
val productionRelease = gradle.startParameter.taskNames.any {
    val task = it.substringAfterLast(':').lowercase()
    (task.contains("production") && task.contains("release")) ||
        task in setOf("assemble", "assemblerelease", "assembleproduction", "bundle", "bundlerelease", "bundleproduction", "build")
}
if (productionRelease) {
    require(productionId != null && productionId.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){2,}")) &&
        !productionId.startsWith("com.example.") &&
        productionId !in setOf("cn.xmubooker.client.mock", "cn.xmubooker.client.capture")) {
        "Production requires XMU_ANDROID_APPLICATION_ID (a non-example application ID)"
    }
    require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        !releaseKeys.getProperty(it).isNullOrBlank()
    }) { "Production requires external XMU_ANDROID_KEY_PROPERTIES with all signing fields" }
    require(runCatching {
        val store = KeyStore.getInstance(file(releaseKeys.getProperty("storeFile")),
            releaseKeys.getProperty("storePassword").toCharArray())
        val cert = store.getCertificate(releaseKeys.getProperty("keyAlias")) as? X509Certificate
        cert?.checkValidity()
        cert != null && !cert.subjectX500Principal.name.contains("CN=Android Debug", ignoreCase = true) &&
            store.getKey(releaseKeys.getProperty("keyAlias"), releaseKeys.getProperty("keyPassword").toCharArray()) is PrivateKey
    }.getOrDefault(false)) { "Production signing identity could not be verified; check the private keystore, alias and passwords" }
}

android {
    namespace = "com.example.xmu_sports_booking"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }
    defaultConfig.javaCompileOptions.annotationProcessorOptions.arguments["room.schemaLocation"] = "$projectDir/schemas"

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.xmu_sports_booking"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 30
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "OWN_SPORTS_AUTH_ENABLED", "false")
    }

    signingConfigs {
        if (releaseKeys.isNotEmpty()) {
            create("production") {
                storeFile = file(releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("production") {
            dimension = "environment"
            applicationId = productionId ?: "invalid.production.configuration"
            minSdk = 30
            buildConfigField("boolean", "OWN_SPORTS_AUTH_ENABLED", "true")
            proguardFiles("capture-proguard-rules.pro")
            if (releaseKeys.isNotEmpty()) signingConfig = signingConfigs.getByName("production")
        }
    }

    sourceSets.getByName("main") {
        java.srcDirs("src/capture/java", "src/capture/kotlin")
        assets.srcDir("src/capture/assets")
    }
    sourceSets.getByName("testProduction").java.srcDir("src/testCapture/java")
    providers.gradleProperty("privateCaptchaAssets").orNull?.let {
        sourceSets.getByName("androidTest").assets.srcDir(file(it))
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        // AOT compilation without changing the installed identity or losing saved authorization.
        getByName("profile") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".debug"
            matchingFallbacks += listOf("debug")
        }
        release {
            // Signing is selected explicitly by flavor; no production debug fallback.
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("androidx.room:room-runtime:2.7.2")
    annotationProcessor("androidx.room:room-compiler:2.7.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
