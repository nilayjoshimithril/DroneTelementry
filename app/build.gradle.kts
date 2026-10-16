plugins {
    alias(libs.plugins.android.application)
    // AGP 9+ ships a built-in Kotlin Android plugin — do not apply org.jetbrains.kotlin.android.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.guardianangel.mithrildefense"
    compileSdk = 36

    defaultConfig {
        // POC: same applicationId as Mithril so Microsoft AD / redirect work.
        applicationId = "com.guardianangel.mithrildefense"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-drone-poc"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Required by AppAuth library manifest merger (scheme of redirect URI).
        manifestPlaceholders["appAuthRedirectScheme"] = "http"

        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"https://dev-api.campusguardianangel.com/v1/\""
        )
        // Cognito hosted UI — easy to replace in one place (also via AppConfig).
        buildConfigField(
            "String",
            "MICROSOFT_AD_AUTHORIZE_URL",
            "\"https://us-east-1vmtjz6w3x.auth.us-east-1.amazoncognito.com/login?client_id=37d2tsdiofsn4gbvmr9l6qd6ue&response_type=code&scope=aws.cognito.signin.user.admin+email+openid+phone\""
        )
        buildConfigField(
            "String",
            "OAUTH_REDIRECT_URI",
            "\"http://localhost:3000/callback\""
        )
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.koin.android)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.appauth)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
