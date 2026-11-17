package com.guardianangel.dronetelementry.di

import com.guardianangel.dronetelementry.config.AppConfig
import com.guardianangel.dronetelementry.data.local.SessionStore
import com.guardianangel.dronetelementry.data.remote.ApiService
import com.guardianangel.dronetelementry.data.remote.AuthInterceptor
import com.guardianangel.dronetelementry.data.remote.OAuthHelper
import com.guardianangel.dronetelementry.data.remote.SessionEvents
import com.guardianangel.dronetelementry.data.remote.TokenRefresher
import com.guardianangel.dronetelementry.data.remote.UnauthorizedInterceptor
import com.guardianangel.dronetelementry.data.socket.SocketHelper
import com.guardianangel.dronetelementry.domain.interact.AuthInteract
import com.guardianangel.dronetelementry.domain.interact.HomeInteract
import com.guardianangel.dronetelementry.domain.interact.MapInteract
import com.guardianangel.dronetelementry.presentation.settings.SettingsViewModel
import com.guardianangel.dronetelementry.presentation.settings.ZoomPreviewViewModel
import com.guardianangel.dronetelementry.presentation.home.HomeViewModel
import com.guardianangel.dronetelementry.presentation.map.MapSessionViewModel
import com.guardianangel.dronetelementry.presentation.onboarding.OnboardingViewModel
import com.guardianangel.dronetelementry.presentation.splash.SplashViewModel
import com.guardianangel.dronetelementry.utils.AppLog
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit

private const val TILE_DISK_CACHE_BYTES = 250L * 1024L * 1024L
private const val TILE_CACHE_MAX_AGE_SEC = 7 * 24 * 60 * 60

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
    single { TokenRefresher(get(), get(), get()) }
    single { UnauthorizedInterceptor(get()) }
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
            // Disk cache so returning to a floor/building reads tiles from disk, not S3
            // (Mithril: Coil disk cache). The in-memory bitmap LRU lives in MosaicMapView.
            .cache(Cache(File(androidContext().cacheDir, "map_tiles"), TILE_DISK_CACHE_BYTES))
            .addNetworkInterceptor { chain ->
                // Tiles are immutable per URL; S3 often sends no Cache-Control, which leaves
                // OkHttp only a short heuristic freshness window.
                val response = chain.proceed(chain.request())
                if (response.isSuccessful && response.header("Cache-Control") == null) {
                    response.newBuilder()
                        .header("Cache-Control", "public, max-age=$TILE_CACHE_MAX_AGE_SEC")
                        .build()
                } else {
                    response
                }
            }
            // All tiles come from one S3 host; OkHttp's default is 5 parallel requests per host.
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 12 })
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
    single { HomeInteract(get()) }
    single { MapInteract(get(), get()) }

    viewModel { SplashViewModel(get()) }
    viewModel { OnboardingViewModel(get(), get(), get()) }
    viewModel { HomeViewModel(get(), get()) }
    viewModel { MapSessionViewModel(get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
    viewModel { ZoomPreviewViewModel(get()) }
}
