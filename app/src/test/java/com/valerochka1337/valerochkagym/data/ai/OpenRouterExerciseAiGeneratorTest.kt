package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.settings.OpenRouterKeyStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenRouterExerciseAiGeneratorTest {

    @Test
    fun `generator sends the full catalogue and returns a validated new exercise`() = runTest {
        val api = FakeOpenRouterApi(
            content = """
                {"kind":"new","name":"Тяга гантели к поясу","type":"STRENGTH","loads":[
                  {"muscle":"LATS","contribution":100},
                  {"muscle":"BICEPS","contribution":55}
                ]}
            """.trimIndent(),
        )
        val generator = generator(api = api)

        val result = generator.generate("Тяну гантель одной рукой к поясу в наклоне")

        val exercise = result as ExerciseAiGenerationResult.New
        assertEquals("Тяга гантели к поясу", exercise.name)
        assertEquals(ExerciseType.STRENGTH, exercise.type)
        assertEquals(mapOf(Muscle.LATS to 100, Muscle.BICEPS to 55), exercise.loads.associate { it.muscle to it.contribution })
        val request = api.request!!
        assertEquals("google/gemma-4-26b-a4b-it:free", request.model)
        assertEquals(0.1, request.temperature!!, 0.0)
        assertTrue(request.provider.requireParameters)
        assertTrue(request.responseFormat.jsonSchema.strict)
        assertTrue(request.messages.first().content.contains("РОВНО ОДИН JSON-ОБЪЕКТ"))
        assertTrue(request.messages.first().content.contains("Если есть сомнение, выбери kind=\"new\""))
        assertTrue(request.messages.last().content.contains("Жим штанги лёжа"))
        assertTrue(request.messages.last().content.contains("\"contribution\":100"))
        assertTrue(request.messages.last().content.contains("<user_description_json>"))
        assertTrue(request.messages.last().content.contains("Тяну гантель одной рукой"))
    }

    @Test
    fun `generator returns an existing exercise only for a known id`() = runTest {
        val generator = generator(api = FakeOpenRouterApi("{\"kind\":\"existing\",\"existingExerciseId\":2}"))

        assertEquals(ExerciseAiGenerationResult.Existing(2L), generator.generate("Жим ногами в тренажёре"))
    }

    @Test
    fun `generator rejects an unknown existing id`() = runTest {
        val generator = generator(api = FakeOpenRouterApi("{\"kind\":\"existing\",\"existingExerciseId\":99}"))

        assertTrue(generator.generate("Неизвестное").isFailure())
    }

    @Test
    fun `generator accepts an existing branch when the provider also sends malformed unused draft fields`() = runTest {
        val generator = generator(
            api = FakeOpenRouterApi(
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
            api = FakeOpenRouterApi(
                """{"kind":"new","existingExerciseId":"not-an-id","name":"Жим в тренажёре",
                    |"type":"STRENGTH","loads":[{"muscle":"CHEST","contribution":100}]}"""
                    .trimMargin(),
            ),
        )

        assertTrue(generator.generate("Жим в тренажёре") is ExerciseAiGenerationResult.New)
    }

    @Test
    fun `generator shows an in body provider error instead of a generic failure`() = runTest {
        val generator = generator(
            api = FakeOpenRouterApi(
                apiResponse = OpenRouterChatResponse(
                    choices = listOf(
                        OpenRouterChoice(
                            finishReason = "error",
                            error = OpenRouterApiError(
                                code = 503,
                                metadata = OpenRouterErrorMetadata(errorType = "provider_unavailable"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            ExerciseAiGenerationResult.Failure(
                "Бесплатная модель со структурированным ответом сейчас недоступна — попробуйте позже",
            ),
            generator.generate("Жим лёжа"),
        )
    }

    @Test
    fun `generator rejects an invalid muscle map`() = runTest {
        val generator = generator(
            api = FakeOpenRouterApi(
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
    fun `generator keeps a new branch instead of inferring a duplicate from its title`() = runTest {
        val generator = generator(
            api = FakeOpenRouterApi(
                """
                    {"kind":"new","name":"  жим   штанги лёжа ","type":"STRENGTH","loads":[
                      {"muscle":"CHEST","contribution":100}
                    ]}
                """.trimIndent(),
            ),
        )

        assertTrue(generator.generate("жим лёжа") is ExerciseAiGenerationResult.New)
    }

    private fun generator(api: OpenRouterApi): OpenRouterExerciseAiGenerator =
        OpenRouterExerciseAiGenerator(
            api = api,
            keyStore = FakeOpenRouterKeyStore(),
            exerciseDao = FakeExerciseDao(catalogue),
            exerciseMuscleDao = FakeExerciseMuscleDao(muscles),
            json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
            computeDispatcher = UnconfinedTestDispatcher(),
        )

    private class FakeOpenRouterApi(
        private val content: String? = null,
        private val apiResponse: OpenRouterChatResponse? = null,
    ) : OpenRouterApi {
        var request: OpenRouterChatRequest? = null
            private set

        override suspend fun createCompletion(
            authorization: String,
            request: OpenRouterChatRequest,
        ): OpenRouterChatResponse {
            this.request = request
            assertEquals("Bearer test-key", authorization)
            return apiResponse ?: OpenRouterChatResponse(
                choices = listOf(
                    OpenRouterChoice(
                        message = OpenRouterResponseMessage(
                            role = "assistant",
                            content = JsonPrimitive(requireNotNull(content)),
                        ),
                    ),
                ),
            )
        }
    }

    private class FakeOpenRouterKeyStore : OpenRouterKeyStore {
        override val isConfigured: Flow<Boolean> = MutableStateFlow(true)

        override suspend fun save(value: String) = Unit

        override suspend fun read(): String = "test-key"

        override suspend fun clear() = Unit
    }

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
