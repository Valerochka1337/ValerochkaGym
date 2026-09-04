package com.valerochka1337.valerochkagym.di

import javax.inject.Qualifier

/**
 * CPU-диспетчер для тяжёлых чистых вычислений (пересчёт аналитики). Квалификатор нужен, чтобы тесты
 * подставляли тестовый диспетчер и оставались синхронными на виртуальном времени.
 */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ComputeDispatcher
