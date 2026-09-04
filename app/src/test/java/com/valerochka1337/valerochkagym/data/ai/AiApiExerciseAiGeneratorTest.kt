package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class AiApiExerciseAiGeneratorTest {

    @Test
    fun `generator sends the full catalogue and returns a validated new exercise`() = runTest {
        val api = FakeAiApi(
            content = """
                {"kind":"new","name":"Тяга гантели к поясу","type":"STRENGTH","loads":[
                  {"muscle":"LATS","contribution":100},
                  {"muscle":"BICEPS","contribution":50}
                ]}
            """.trimIndent(),
        )
        val generator = generator(api = api)

        val result = generator.generate("Тяну гантель одной рукой к поясу в наклоне")

        val exercise = result as ExerciseAiGenerationResult.New
        assertEquals("Тяга гантели к поясу", exercise.name)
        assertEquals(ExerciseType.STRENGTH, exercise.type)
        assertEquals(mapOf(Muscle.LATS to 100, Muscle.BICEPS to 50), exercise.loads.associate { it.muscle to it.contribution })
        val request = api.request!!
        assertEquals(MODEL_ID, request.model)
        assertEquals(2_048, request.maxTokens)
        assertEquals("json_object", request.responseFormat.type)
        assertEquals(CHAT_COMPLETIONS_ENDPOINT, api.endpoint)
        val systemContent = (request.messages.first().content as JsonPrimitive).content
        val userContent = (request.messages.last().content as JsonPrimitive).content
        assertTrue(systemContent.contains("РОВНО ОДИН JSON-ОБЪЕКТ"))
        assertTrue(systemContent.contains("Используй минимум рассуждений"))
        assertTrue(systemContent.contains("\"existingExerciseId\":123"))
        assertTrue(systemContent.contains("\"existingExerciseId\":null"))
        assertTrue(systemContent.contains("Если есть сомнение, выбери kind=\"new\""))
        assertTrue(systemContent.contains("<response_schema_json>"))
        assertTrue(systemContent.contains("existingExerciseId"))
        assertTrue(userContent.contains("Жим штанги лёжа"))
        assertTrue(userContent.contains("\"contribution\":100"))
        assertTrue(userContent.contains("<user_description_json>"))
        assertTrue(userContent.contains("Тяну гантель одной рукой"))
    }

    @Test
    fun `generator returns an existing exercise only for a known id`() = runTest {
        val generator = generator(api = FakeAiApi("{\"kind\":\"existing\",\"existingExerciseId\":2}"))

        assertEquals(ExerciseAiGenerationResult.Existing(2L), generator.generate("Жим ногами в тренажёре"))
    }

    @Test
    fun `generator uses the model selected in settings`() = runTest {
        val api = FakeAiApi("{\"kind\":\"existing\",\"existingExerciseId\":1}")
        val generator = generator(api = api, modelId = "vision-model")

        generator.generate("Жим лёжа")

        assertEquals("vision-model", api.request?.model)
    }

    @Test
    fun `generator reports when the model response times out`() = runTest {
        val generator = generator(api = FakeAiApi(error = SocketTimeoutException()))

        val result = generator.generate("Жим лёжа")

        assertEquals(ExerciseAiGenerationResult.Failure(AI_REQUEST_TIMEOUT_MESSAGE), result)
    }

    @Test
    fun `generator embeds the response schema for portable json object mode`() = runTest {
        val api = FakeAiApi("{\"kind\":\"existing\",\"existingExerciseId\":1}")
        val generator = generator(
            api = api,
            modelId = "stealth/ox-alpha",
        )

        generator.generate("Жим лёжа")

        assertEquals("json_object", api.request?.responseFormat?.type)
        val systemContent = (api.request?.messages?.first()?.content as JsonPrimitive).content
        assertTrue(systemContent.contains("<response_schema_json>"))
        assertTrue(systemContent.contains("existingExerciseId"))
    }

    @Test
    fun `generator logs the raw API response before parsing it`() = runTest {
        val logger = RecordingAiResponseLogger()
        val generator = generator(
            api = FakeAiApi("{\"kind\":\"existing\",\"existingExerciseId\":1}"),
            responseLogger = logger,
        )

        generator.generate("Жим лёжа")

        assertEquals(listOf(AiResponseSource.EXERCISE), logger.sources)
        assertEquals(listOf(MODEL_ID), logger.modelIds)
        val response = logger.responses.single()
        assertEquals(
            "{\"kind\":\"existing\",\"existingExerciseId\":1}",
            (response.choices.single().message?.content as JsonPrimitive).content,
        )
    }

    @Test
    fun `generator logs the http status and API error body`() = runTest {
        val responseBody =
            """[{"kind":"failover","message":"upstream rejected the request","upstream_status_code":403}]"""
        val logger = RecordingAiResponseLogger()
        val generator = generator(
            api = FakeAiApi(error = HttpException(Response.error<Unit>(502, responseBody.toResponseBody()))),
            responseLogger = logger,
        )

        generator.generate("Жим лёжа")

        assertEquals(
            listOf(
                LoggedFailure(
                    source = AiResponseSource.EXERCISE,
                    modelId = MODEL_ID,
                    stage = "http",
                    httpCode = 502,
                    responseBody = responseBody,
                    throwableType = HttpException::class.java.name,
                ),
            ),
            logger.failures,
        )
    }

    @Test
    fun `generator rejects an unknown existing id`() = runTest {
        val generator = generator(api = FakeAiApi("{\"kind\":\"existing\",\"existingExerciseId\":99}"))

        assertTrue(generator.generate("Неизвестное").isFailure())
    }

    @Test
    fun `generator explains when an existing response omits its catalogue id`() = runTest {
        val generator = generator(api = FakeAiApi("{\"kind\":\"existing\",\"type\":\"STRENGTH\"}"))

        assertEquals(
            ExerciseAiGenerationResult.Failure("ИИ не указал существующее упражнение — попробуйте ещё раз"),
            generator.generate("Жим лёжа"),
        )
    }

    @Test
    fun `generator accepts an existing branch when the provider also sends malformed unused draft fields`() = runTest {
        val generator = generator(
            api = FakeAiApi(
                """{"kind":"existing","existingExerciseId":1,"name":{"unexpected":"value"},
                    |"loads":"not-an-array"}"""
                    .trimMargin(),
            ),
        )

        assertEquals(ExerciseAiGenerationResult.Existing(1L), generator.generate("Жим лёжа"))
    }

    @Test
    fun `generator accepts a new branch when the provider also sends a malformed unused existing id`() = runTest {
        val generator = generator(
            api = FakeAiApi(
                """{"kind":"new","existingExerciseId":"not-an-id","name":"Жим в тренажёре",
                    |"type":"STRENGTH","loads":[{"muscle":"UPPER_CHEST","contribution":100}]}"""
                    .trimMargin(),
            ),
        )

        assertTrue(generator.generate("Жим в тренажёре") is ExerciseAiGenerationResult.New)
    }

    @Test
    fun `generator exposes an embedded upstream error instead of a generic failure`() = runTest {
        val generator = generator(
            api = FakeAiApi(
                apiResponse = AiApiChatResponse(
                    choices = listOf(
                        AiApiChoice(
                            finishReason = "error",
                            error = AiApiError(
                                code = JsonPrimitive(503),
                                metadata = AiApiErrorMetadata(errorType = "provider_unavailable"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            ExerciseAiGenerationResult.Failure(
                MODEL_UNAVAILABLE_MESSAGE,
                modelUnavailable = true,
            ),
            generator.generate("Жим лёжа"),
        )
    }

    @Test
    fun `generator explains when the model exhausts its completion limit`() = runTest {
        val generator = generator(
            api = FakeAiApi(
                apiResponse = AiApiChatResponse(
                    choices = listOf(AiApiChoice(finishReason = "length")),
                ),
            ),
        )

        assertEquals(
            ExerciseAiGenerationResult.Failure(
                "Модель исчерпала лимит ответа — попробуйте ещё раз или выберите другую",
            ),
            generator.generate("Жим лёжа"),
        )
    }

    @Test
    fun `generator rejects an invalid muscle map`() = runTest {
        val generator = generator(
            api = FakeAiApi(
                """
                    {"kind":"new","name":"Новый жим","type":"STRENGTH","loads":[
                      {"muscle":"CHEST","contribution":100},
                      {"muscle":"CHEST","contribution":55}
                    ]}
                """.trimIndent(),
            ),
        )

        assertTrue(generator.generate("Новый жим").isFailure())
    }

    @Test
    fun `generator rejects a map without a primary role`() = runTest {
        val generator = generator(
            api = FakeAiApi(
                """
                    {"kind":"new","name":"Бег по лесу","type":"CARDIO","loads":[
                      {"muscle":"QUADS","contribution":20},
                      {"muscle":"CALVES","contribution":15}
                    ]}
                """.trimIndent(),
            ),
        )

        assertTrue(generator.generate("Бегу по лесу").isFailure())
    }

    @Test
    fun `generator keeps a new branch instead of inferring a duplicate from its title`() = runTest {
        val generator = generator(
            api = FakeAiApi(
                """
                    {"kind":"new","name":"  жим   штанги лёжа ","type":"STRENGTH","loads":[
                      {"muscle":"UPPER_CHEST","contribution":100}
                    ]}
                """.trimIndent(),
            ),
        )

        assertTrue(generator.generate("жим лёжа") is ExerciseAiGenerationResult.New)
    }

    private fun generator(
        api: AiApi,
        modelId: String = MODEL_ID,
        responseLogger: AiResponseLogger = NoOpAiResponseLogger,
    ): AiApiExerciseAiGenerator =
        AiApiExerciseAiGenerator(
            api = api,
            configurationProvider = FakeAiApiConfigurationProvider(modelId),
            exerciseDao = FakeExerciseDao(catalogue),
            exerciseMuscleDao = FakeExerciseMuscleDao(muscles),
            json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
            responseLogger = responseLogger,
            computeDispatcher = UnconfinedTestDispatcher(),
        )

    private class FakeAiApi(
        private val content: String? = null,
        private val apiResponse: AiApiChatResponse? = null,
        private val error: Exception? = null,
    ) : AiApi {
        var request: AiApiChatRequest? = null
            private set
        var endpoint: String? = null
            private set

        override suspend fun createCompletion(
            endpoint: String,
            authorization: String,
            request: AiApiChatRequest,
        ): AiApiChatResponse {
            this.endpoint = endpoint
            this.request = request
            assertEquals("Bearer test-key", authorization)
            error?.let { throw it }
            return apiResponse ?: AiApiChatResponse(
                choices = listOf(
                    AiApiChoice(
                        message = AiApiResponseMessage(
                            role = "assistant",
                            content = JsonPrimitive(requireNotNull(content)),
                        ),
                    ),
                ),
            )
        }

        override suspend fun getModels(
            endpoint: String,
            authorization: String,
        ): AiModelsResponse = error("unused")
    }

    private class FakeAiApiConfigurationProvider(
        private val modelId: String,
    ) : AiApiConfigurationProvider {
        override val isConfigured: Flow<Boolean> = MutableStateFlow(true)

        override suspend fun connection(): AiApiConnection = AiApiConnection(
            baseUrl = BASE_URL,
            apiKey = "test-key",
        )

        override suspend fun requestConfiguration(): AiApiRequestConfiguration =
            AiApiRequestConfiguration(connection = connection(), modelId = modelId)
    }

    private object NoOpAiResponseLogger : AiResponseLogger {
        override fun log(
            source: AiResponseSource,
            requestedModelId: String,
            response: AiApiChatResponse,
        ) = Unit
    }

    private class RecordingAiResponseLogger : AiResponseLogger {
        val sources = mutableListOf<AiResponseSource>()
        val modelIds = mutableListOf<String>()
        val responses = mutableListOf<AiApiChatResponse>()
        val failures = mutableListOf<LoggedFailure>()

        override fun log(
            source: AiResponseSource,
            requestedModelId: String,
            response: AiApiChatResponse,
        ) {
            sources += source
            modelIds += requestedModelId
            responses += response
        }

        override fun logFailure(
            source: AiResponseSource,
            requestedModelId: String,
            stage: String,
            httpCode: Int?,
            responseBody: String?,
            throwable: Throwable?,
        ) {
            failures += LoggedFailure(
                source = source,
                modelId = requestedModelId,
                stage = stage,
                httpCode = httpCode,
                responseBody = responseBody,
                throwableType = throwable?.javaClass?.name,
            )
        }
    }

    private data class LoggedFailure(
        val source: AiResponseSource,
        val modelId: String,
        val stage: String,
        val httpCode: Int?,
        val responseBody: String?,
        val throwableType: String?,
    )

    private class FakeExerciseDao(initial: List<ExerciseEntity>) : ExerciseDao {
        private val items = MutableStateFlow(initial)

        override fun getAll(): Flow<List<ExerciseEntity>> = items

        override suspend fun insert(exercise: ExerciseEntity): Long = error("unused")

        override suspend fun update(exercise: ExerciseEntity) = Unit

        override suspend fun insertAll(exercises: List<ExerciseEntity>) = Unit

        override suspend fun count(): Int = items.value.size

        override suspend fun getById(id: Long): ExerciseEntity? = items.value.firstOrNull { it.id == id }

        override suspend fun getAllOnce(): List<ExerciseEntity> = items.value
    }

    private class FakeExerciseMuscleDao(initial: List<ExerciseMuscleEntity>) : ExerciseMuscleDao {
        private val rows = MutableStateFlow(initial)

        override fun observeAll(): Flow<List<ExerciseMuscleEntity>> = rows

        override suspend fun getForExercise(exerciseId: Long): List<ExerciseMuscleEntity> =
            rows.value.filter { it.exerciseId == exerciseId }

        override suspend fun getMappedExerciseIds(): List<Long> = rows.value.map { it.exerciseId }.distinct()

        override suspend fun upsertAll(rows: List<ExerciseMuscleEntity>) = Unit

        override suspend fun deleteForExercise(exerciseId: Long) = Unit
    }

    private fun ExerciseAiGenerationResult.isFailure(): Boolean = this is ExerciseAiGenerationResult.Failure

    private companion object {
        const val BASE_URL = "https://ai.example.com/v1/"
        const val CHAT_COMPLETIONS_ENDPOINT = "https://ai.example.com/v1/chat/completions"
        const val MODEL_ID = "gpt-5.4"
        val catalogue = listOf(
            ExerciseEntity(1L, "Жим штанги лёжа", com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup.CHEST, ExerciseType.STRENGTH),
            ExerciseEntity(2L, "Жим ногами", com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup.LEGS, ExerciseType.STRENGTH),
        )
        val muscles = listOf(
            ExerciseMuscleEntity(1L, Muscle.CHEST, 100),
            ExerciseMuscleEntity(1L, Muscle.TRICEPS, 65),
            ExerciseMuscleEntity(2L, Muscle.QUADS, 100),
        )
    }
}
