package com.guardianangel.mithrildefense.di

import com.guardianangel.mithrildefense.config.AppConfig
import com.guardianangel.mithrildefense.data.local.SessionStore
import com.guardianangel.mithrildefense.data.remote.ApiService
import com.guardianangel.mithrildefense.data.remote.AuthInterceptor
import com.guardianangel.mithrildefense.data.remote.OAuthHelper
import com.guardianangel.mithrildefense.data.remote.SessionEvents
import com.guardianangel.mithrildefense.data.remote.UnauthorizedInterceptor
import com.guardianangel.mithrildefense.data.socket.SocketHelper
import com.guardianangel.mithrildefense.domain.interact.AuthInteract
import com.guardianangel.mithrildefense.domain.interact.HomeInteract
import com.guardianangel.mithrildefense.domain.interact.MapInteract
import com.guardianangel.mithrildefense.presentation.home.HomeViewModel
import com.guardianangel.mithrildefense.presentation.map.MapSessionViewModel
import com.guardianangel.mithrildefense.presentation.onboarding.OnboardingViewModel
import com.guardianangel.mithrildefense.presentation.splash.SplashViewModel
import com.guardianangel.mithrildefense.utils.AppLog
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

val appModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }

    single { SessionStore(androidContext(), get()) }
    single { SessionEvents() }
    single { AuthInterceptor(get()) }
    single { UnauthorizedInterceptor(get(), get()) }
    single { OAuthHelper() }

    single {
        val logging = HttpLoggingInterceptor { message ->
            val safe = if (message.startsWith("Authorization:", ignoreCase = true)) {
                "Authorization: ${AppLog.redact(message.substringAfter(':').trim())}"
            } else {
                message
            }
            AppLog.d("OkHttp", safe)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .addInterceptor(get<AuthInterceptor>())
            .addInterceptor(get<UnauthorizedInterceptor>())
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    /** Unauthenticated client for public S3 mosaic tiles (no JWT). */
    single(named("tileClient")) {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Dedicated WebSocket client: no short read timeout (API client is 30s and would kill
     * quiet sockets), plus ping frames so long incidents stay alive past the ~2h drop.
     */
    single(named("wsClient")) {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    single {
        val json: Json = get()
        Retrofit.Builder()
            .baseUrl(AppConfig.apiBaseUrl)
            .client(get())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    single<ApiService> { get<Retrofit>().create(ApiService::class.java) }
    single { SocketHelper(get(named("wsClient")), get()) }

    single { AuthInteract(get(), get()) }
    single { HomeInteract(get(), get()) }
    single { MapInteract(get()) }

    viewModel { SplashViewModel(get()) }
    viewModel { OnboardingViewModel(get(), get()) }
    viewModel { HomeViewModel(get(), get(), get()) }
    viewModel { MapSessionViewModel(get(), get(), get()) }
}
