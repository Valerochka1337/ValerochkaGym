package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.google.CalendarApi
import com.valerochka1337.valerochkagym.data.update.GitHubReleaseApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Сетевой слой для Google Sheets и Calendar API. Токен подставляется заголовком в каждом запросе
 * (см. [SheetsApi]/[CalendarApi]), поэтому OkHttp-клиент без авторизующего интерсептора.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

  private const val CALENDAR_BASE_URL = "https://www.googleapis.com/"
  private const val GITHUB_BASE_URL = "https://api.github.com/"

  @Provides
  @Singleton
  fun provideJson(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @Provides @Singleton fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

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
  @Named("github")
  fun provideGitHubRetrofit(client: OkHttpClient, json: Json): Retrofit =
      Retrofit.Builder()
          .baseUrl(GITHUB_BASE_URL)
          .client(client)
          .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
          .build()

  @Provides
  @Singleton
  fun provideGitHubReleaseApi(@Named("github") retrofit: Retrofit): GitHubReleaseApi =
      retrofit.create(GitHubReleaseApi::class.java)
}
