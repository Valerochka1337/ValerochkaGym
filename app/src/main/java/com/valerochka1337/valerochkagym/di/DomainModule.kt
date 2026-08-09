package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.ActiveWorkoutRepositoryImpl
import com.valerochka1337.valerochkagym.data.ai.AndroidInBodyPhotoEncoder
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerator
import com.valerochka1337.valerochkagym.data.ai.InBodyPhotoEncoder
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiReader
import com.valerochka1337.valerochkagym.data.ai.OpenRouterInBodyReportAiReader
import com.valerochka1337.valerochkagym.data.ai.OpenRouterExerciseAiGenerator
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCaseImpl
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporterImpl
import com.valerochka1337.valerochkagym.data.settings.AndroidKeystoreSecretCipher
import com.valerochka1337.valerochkagym.data.settings.EncryptedOpenRouterKeyStore
import com.valerochka1337.valerochkagym.data.settings.OpenRouterKeyStore
import com.valerochka1337.valerochkagym.data.settings.SecretCipher
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerMeasurementUploadScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerUploadScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {

    @Binds
    @Singleton
    abstract fun bindActiveWorkoutRepository(
        impl: ActiveWorkoutRepositoryImpl,
    ): ActiveWorkoutRepository

    @Binds
    @Singleton
    abstract fun bindUploadScheduler(impl: WorkManagerUploadScheduler): UploadScheduler

    @Binds
    @Singleton
    abstract fun bindMeasurementUploadScheduler(
        impl: WorkManagerMeasurementUploadScheduler,
    ): MeasurementUploadScheduler

    @Binds
    @Singleton
    abstract fun bindDatabaseExporter(impl: DatabaseExporterImpl): DatabaseExporter

    @Binds
    @Singleton
    abstract fun bindClearDataUseCase(impl: ClearDataUseCaseImpl): ClearDataUseCase

    @Binds
    @Singleton
    abstract fun bindOpenRouterKeyStore(impl: EncryptedOpenRouterKeyStore): OpenRouterKeyStore

    @Binds
    @Singleton
    abstract fun bindSecretCipher(impl: AndroidKeystoreSecretCipher): SecretCipher

    @Binds
    @Singleton
    abstract fun bindExerciseAiGenerator(impl: OpenRouterExerciseAiGenerator): ExerciseAiGenerator

    @Binds
    @Singleton
    abstract fun bindInBodyPhotoEncoder(impl: AndroidInBodyPhotoEncoder): InBodyPhotoEncoder

    @Binds
    @Singleton
    abstract fun bindInBodyReportAiReader(impl: OpenRouterInBodyReportAiReader): InBodyReportAiReader
}
