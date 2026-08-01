package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.google.CalendarApi
import com.valerochka1337.valerochkagym.data.google.SheetsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Named
import javax.inject.Singleton

/**
 * Сетевой слой для Google Sheets и Calendar API. Токен подставляется заголовком в каждом
 * запросе (см. [SheetsApi]/[CalendarApi]), поэтому OkHttp-клиент без авторизующего интерсептора.
 * У Sheets и Calendar разные хосты, поэтому — два Retrofit-инстанса (calendar помечен
 * `@Named("calendar")`) поверх общих OkHttp-клиента и Json.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val SHEETS_BASE_URL = "https://sheets.googleapis.com/"
    private const val CALENDAR_BASE_URL = "https://www.googleapis.com/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

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
}
