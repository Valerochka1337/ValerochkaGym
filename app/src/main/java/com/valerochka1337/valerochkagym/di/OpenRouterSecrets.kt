package com.valerochka1337.valerochkagym.di

import javax.inject.Qualifier

/** Отдельный DataStore с зашифрованным ключом OpenRouter; он не попадает в бэкап. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OpenRouterSecrets
