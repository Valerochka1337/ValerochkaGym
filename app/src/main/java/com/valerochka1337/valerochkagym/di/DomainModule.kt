package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.ActiveWorkoutRepositoryImpl
import com.valerochka1337.valerochkagym.data.ExerciseCatalogRepositoryImpl
import com.valerochka1337.valerochkagym.data.GymRepositoryImpl
import com.valerochka1337.valerochkagym.data.ai.AiResponseLogger
import com.valerochka1337.valerochkagym.data.ai.AndroidInBodyPhotoEncoder
import com.valerochka1337.valerochkagym.data.ai.DebugAiResponseLogger
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerator
import com.valerochka1337.valerochkagym.data.ai.InBodyPhotoEncoder
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiReader
import com.valerochka1337.valerochkagym.data.ai.RemoteAiModelCatalog
import com.valerochka1337.valerochkagym.data.ai.StoredAiApiConfigurationProvider
import com.valerochka1337.valerochkagym.data.ai.AiApiConfigurationProvider
import com.valerochka1337.valerochkagym.data.ai.AiApiInBodyReportAiReader
import com.valerochka1337.valerochkagym.data.ai.AiApiExerciseAiGenerator
import com.valerochka1337.valerochkagym.data.ai.HealthReportAiReader
import com.valerochka1337.valerochkagym.data.ai.AiApiHealthReportAiReader
import com.valerochka1337.valerochkagym.data.ai.HealthDocumentRenderer
import com.valerochka1337.valerochkagym.data.ai.AndroidHealthDocumentRenderer
import com.valerochka1337.valerochkagym.data.ai.HealthRestrictionAiInterpreter
import com.valerochka1337.valerochkagym.data.ai.AiHealthRestrictionAiInterpreter
import com.valerochka1337.valerochkagym.data.ai.AiModelCatalog
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCaseImpl
import com.valerochka1337.valerochkagym.data.health.HealthDocumentRepository
import com.valerochka1337.valerochkagym.data.health.LocalHealthDocumentRepository
import com.valerochka1337.valerochkagym.data.health.HealthArchiveExporter
import com.valerochka1337.valerochkagym.data.health.ZipHealthArchiveExporter
import com.valerochka1337.valerochkagym.data.measurements.LocalMeasurementDocumentRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentLifecycleHook
import com.valerochka1337.valerochkagym.data.measurements.NoOpMeasurementDocumentLifecycleHook
import com.valerochka1337.valerochkagym.data.google.HealthSheetsRepository
import com.valerochka1337.valerochkagym.data.google.HealthSheetsRepositoryImpl
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporterImpl
import com.valerochka1337.valerochkagym.data.settings.AndroidKeystoreSecretCipher
import com.valerochka1337.valerochkagym.data.settings.EncryptedAiApiKeyStore
import com.valerochka1337.valerochkagym.data.settings.AiApiKeyStore
import com.valerochka1337.valerochkagym.data.settings.SecretCipher
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepository
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import com.valerochka1337.valerochkagym.worker.ConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerMeasurementUploadScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerRoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerUploadScheduler
import com.valerochka1337.valerochkagym.worker.HealthSyncScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerHealthSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class DomainModule {

    @Binds
    @Singleton
    abstract fun bindActiveWorkoutRepository(
        impl: ActiveWorkoutRepositoryImpl,
    ): ActiveWorkoutRepository

    @Binds
    @Singleton
    abstract fun bindGymRepository(impl: GymRepositoryImpl): GymRepository

    @Binds
    @Singleton
    abstract fun bindExerciseCatalogRepository(
        impl: ExerciseCatalogRepositoryImpl,
    ): ExerciseCatalogRepository

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
    abstract fun bindRoutineUploadScheduler(
        impl: WorkManagerRoutineUploadScheduler,
    ): RoutineUploadScheduler

    @Binds
    @Singleton
    abstract fun bindConfigurationUploadScheduler(
        impl: WorkManagerConfigurationUploadScheduler,
    ): ConfigurationUploadScheduler

    @Binds
    @Singleton
    abstract fun bindDatabaseExporter(impl: DatabaseExporterImpl): DatabaseExporter

    @Binds
    @Singleton
    abstract fun bindClearDataUseCase(impl: ClearDataUseCaseImpl): ClearDataUseCase

    @Binds
    @Singleton
    abstract fun bindHealthDocumentRepository(impl: LocalHealthDocumentRepository): HealthDocumentRepository

    @Binds
    @Singleton
    abstract fun bindMeasurementDocumentRepository(
        impl: LocalMeasurementDocumentRepository,
    ): MeasurementDocumentRepository

    @Binds
    @Singleton
    abstract fun bindMeasurementDocumentLifecycleHook(
        impl: NoOpMeasurementDocumentLifecycleHook,
    ): MeasurementDocumentLifecycleHook

    @Binds
    @Singleton
    abstract fun bindHealthArchiveExporter(impl: ZipHealthArchiveExporter): HealthArchiveExporter

    @Binds
    @Singleton
    abstract fun bindHealthSheetsRepository(impl: HealthSheetsRepositoryImpl): HealthSheetsRepository

    @Binds
    @Singleton
    abstract fun bindHealthSyncScheduler(impl: WorkManagerHealthSyncScheduler): HealthSyncScheduler

    @Binds
    @Singleton
    abstract fun bindAiApiKeyStore(impl: EncryptedAiApiKeyStore): AiApiKeyStore

    @Binds
    @Singleton
    abstract fun bindSecretCipher(impl: AndroidKeystoreSecretCipher): SecretCipher

    @Binds
    @Singleton
    abstract fun bindExerciseAiGenerator(impl: AiApiExerciseAiGenerator): ExerciseAiGenerator

    @Binds
    @Singleton
    abstract fun bindInBodyPhotoEncoder(impl: AndroidInBodyPhotoEncoder): InBodyPhotoEncoder

    @Binds
    @Singleton
    abstract fun bindInBodyReportAiReader(impl: AiApiInBodyReportAiReader): InBodyReportAiReader

    @Binds
    @Singleton
    abstract fun bindHealthReportAiReader(impl: AiApiHealthReportAiReader): HealthReportAiReader

    @Binds
    @Singleton
    abstract fun bindHealthDocumentRenderer(impl: AndroidHealthDocumentRenderer): HealthDocumentRenderer

    @Binds @Singleton
    abstract fun bindHealthRestrictionAiInterpreter(impl: AiHealthRestrictionAiInterpreter): HealthRestrictionAiInterpreter

    @Binds
    @Singleton
    abstract fun bindAiModelCatalog(
        impl: RemoteAiModelCatalog,
    ): AiModelCatalog

    @Binds
    @Singleton
    abstract fun bindAiApiConfigurationProvider(
        impl: StoredAiApiConfigurationProvider,
    ): AiApiConfigurationProvider

    @Binds
    @Singleton
    abstract fun bindAiResponseLogger(impl: DebugAiResponseLogger): AiResponseLogger
}
