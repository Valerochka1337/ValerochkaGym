package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.google.CalendarApi
import com.valerochka1337.valerochkagym.data.google.SheetsApi
import com.valerochka1337.valerochkagym.data.ai.AiApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Сетевой слой для Google Sheets и Calendar API. Токен подставляется заголовком в каждом
 * запросе (см. [SheetsApi]/[CalendarApi]), поэтому OkHttp-клиент без авторизующего интерсептора.
 * У Sheets, Calendar и пользовательского OpenAI-совместимого API разные адреса, поэтому
 * используются отдельные Retrofit-инстансы. Долгие запросы к модели получают свой OkHttp-клиент,
 * чтобы не растягивать таймауты обычных Google-запросов.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val SHEETS_BASE_URL = "https://sheets.googleapis.com/"
    private const val CALENDAR_BASE_URL = "https://www.googleapis.com/"
    /** Retrofit требует base URL, но каждый запрос к модели передаёт полный пользовательский @Url. */
    private const val AI_API_PLACEHOLDER_BASE_URL = "https://ai.invalid/"
    internal const val AI_CONNECT_TIMEOUT_SECONDS = 20L
    internal const val AI_WRITE_TIMEOUT_SECONDS = 120L
    internal const val AI_READ_TIMEOUT_SECONDS = 300L
    internal const val AI_CALL_TIMEOUT_SECONDS = 360L

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

    /**
     * Генерация может долго не присылать ни одного байта, а фото дополнительно требует времени
     * на отправку. Общий дедлайн остаётся конечным; отмена coroutine немедленно отменяет Call.
     */
    @Provides
    @Singleton
    @Named("ai")
    fun provideAiOkHttpClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .connectTimeout(AI_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(AI_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AI_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(AI_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(SHEETS_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideSheetsApi(retrofit: Retrofit): SheetsApi = retrofit.create(SheetsApi::class.java)

    @Provides
    @Singleton
    @Named("calendar")
    fun provideCalendarRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(CALENDAR_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideCalendarApi(@Named("calendar") retrofit: Retrofit): CalendarApi =
        retrofit.create(CalendarApi::class.java)

    @Provides
    @Singleton
    @Named("ai")
    fun provideAiApiRetrofit(
        @Named("ai") client: OkHttpClient,
        json: Json,
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(AI_API_PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideAiApi(@Named("ai") retrofit: Retrofit): AiApi =
        retrofit.create(AiApi::class.java)
}
