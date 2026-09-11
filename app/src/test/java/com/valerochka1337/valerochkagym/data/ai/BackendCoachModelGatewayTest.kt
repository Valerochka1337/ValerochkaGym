package com.valerochka1337.valerochkagym.data.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.valerochka1337.valerochkagym.data.backend.BackendResponse
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BackendCoachModelGatewayTest {
  @Test
  fun `gateway uses account scoped selected allowed model with pinned owner and epoch`() = runTest {
    val settings = SettingsRepository(FakeStore())
    settings.setCoachModel("owner", "model-b")
    val backend = FakeBackend()
    val gateway = BackendCoachModelGateway(backend, FakeSessions(), settings)

    val result =
        gateway.complete("owner", 7, listOf(AiApiMessage.text("user", "Привет")), emptyList())

    assertEquals(
        "Ответ",
        result.choices.single().message?.content?.let { (it as JsonPrimitive).content },
    )
    assertEquals(listOf("/ai/coach-models", "/ai/coach-turn"), backend.paths)
    assertEquals("model-b", backend.turn.model)
    assertEquals("owner", backend.owners.last())
    assertEquals(7L, backend.epochs.last())
  }

  @Test
  fun `gateway rejects an allowed model different from its requested model`() = runTest {
    val settings = SettingsRepository(FakeStore())
    settings.setCoachModel("owner", "model-b")
    val backend = FakeBackend(returnedModel = "model-a")
    val gateway = BackendCoachModelGateway(backend, FakeSessions(), settings)

    try {
      gateway.complete("owner", 7, listOf(AiApiMessage.text("user", "Привет")), emptyList())
      fail("Expected an allowlist rejection")
    } catch (_: IllegalArgumentException) {
      // Expected: a response must correspond to the model this request selected.
    }
  }

  @Test
  fun `gateway sends the server default when account has no selection`() = runTest {
    val backend = FakeBackend()
    val gateway = BackendCoachModelGateway(backend, FakeSessions(), SettingsRepository(FakeStore()))

    gateway.complete("owner", 7, listOf(AiApiMessage.text("user", "Привет")), emptyList())

    assertEquals("model-a", backend.turn.model)
  }

  @Test
  fun `gateway rejects a mismatched response request id`() = runTest {
    val gateway =
        BackendCoachModelGateway(
            FakeBackend(responseRequestId = "other-request"),
            FakeSessions(),
            SettingsRepository(FakeStore()),
        )

    try {
      gateway.complete("owner", 7, listOf(AiApiMessage.text("user", "Привет")), emptyList())
      fail("Expected response correlation rejection")
    } catch (_: IllegalArgumentException) {
      // Expected: only the response for this exact generated request may reach the agent.
    }
  }

  @Test
  fun `coach model selections stay isolated by account`() = runTest {
    val settings = SettingsRepository(FakeStore())

    settings.setCoachModel("account-a", "model-a")
    settings.setCoachModel("account-b", "model-b")

    assertEquals("model-a", settings.coachModel("account-a").first())
    assertEquals("model-b", settings.coachModel("account-b").first())
  }

  @Test
  fun `gateway rejects an oversized turn before it reaches the server`() = runTest {
    val backend = FakeBackend()
    val gateway = BackendCoachModelGateway(backend, FakeSessions(), SettingsRepository(FakeStore()))

    try {
      gateway.complete("owner", 7, List(81) { AiApiMessage.text("user", "x") }, emptyList())
      fail("Expected message limit rejection")
    } catch (_: IllegalArgumentException) {
      assertEquals(emptyList<String>(), backend.paths)
    }
  }

  @Test
  fun `gateway serializes tool protocol defaults in its raw request`() = runTest {
    val backend = FakeBackend()
    val gateway = BackendCoachModelGateway(backend, FakeSessions(), SettingsRepository(FakeStore()))
    val tool =
        AiApiTool(
            function =
                AiApiToolFunction(
                    name = "get_workout_state",
                    description = "state",
                    parameters = buildJsonObject {},
                ),
        )
    val assistant =
        AiApiMessage(
            role = "assistant",
            toolCalls =
                listOf(
                    AiApiToolCall(
                        id = "call-1",
                        function = AiApiToolCallFunction("get_workout_state", "{}"),
                    ),
                ),
        )

    gateway.complete(
        "owner",
        7,
        listOf(AiApiMessage.text("user", "Привет"), assistant),
        listOf(tool),
    )

    val raw = backend.turnRaw.jsonObject
    assertEquals(
        "function",
        raw["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content,
    )
    val toolCall =
        raw["messages"]!!.jsonArray[1].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject
    assertEquals("function", toolCall["type"]!!.jsonPrimitive.content)
  }

  private class FakeSessions : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("owner", "o@example.com", "access", "refresh")
        )
    override val sessionEpoch: Long = 7L

    override fun save(tokens: BackendTokens?) {
      session.value = tokens
    }
  }

  private class FakeBackend(
      private val returnedModel: String? = null,
      private val responseRequestId: String? = null,
  ) : BackendTransport {
    override val json = Json { explicitNulls = false }
    val paths = mutableListOf<String>()
    val owners = mutableListOf<String?>()
    val epochs = mutableListOf<Long?>()
    lateinit var turn: Request
    lateinit var turnRaw: JsonElement

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
        error("unused")

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
        error("unused")

    override suspend fun authorizedRawResponse(
        method: String,
        path: String,
        rawBody: ByteArray,
        headers: Map<String, String>,
        expectedOwner: String?,
        expectedSessionEpoch: Long?,
        retryOnUnauthorized: Boolean,
        maxResponseBytes: Int?,
    ): BackendResponse {
      paths += path
      owners += expectedOwner
      epochs += expectedSessionEpoch
      val response =
          if (path == "/ai/coach-models") {
            """{"availability":"AVAILABLE","defaultModel":"model-a","models":["model-a","model-b"]}"""
          } else {
            turnRaw = json.parseToJsonElement(rawBody.decodeToString())
            turn = json.decodeFromString(Request.serializer(), rawBody.decodeToString())
            json.encodeToString(
                Response.serializer(),
                Response(
                    responseRequestId ?: turn.requestId,
                    returnedModel ?: turn.model,
                    AiApiChatResponse(
                        choices =
                            listOf(
                                AiApiChoice(AiApiResponseMessage(content = JsonPrimitive("Ответ")))
                            )
                    ),
                ),
            )
          }
      return BackendResponse(
          body = json.parseToJsonElement(response),
          rawBody = response.encodeToByteArray(),
          acceptedCapabilities = emptySet(),
          owner = "owner",
          sessionEpoch = 7,
      )
    }
  }

  @kotlinx.serialization.Serializable
  private data class Request(
      val requestId: String,
      val model: String,
      val messages: List<AiApiMessage>,
      val tools: List<AiApiTool>,
  )

  @kotlinx.serialization.Serializable
  private data class Response(
      val requestId: String,
      val model: String?,
      val completion: AiApiChatResponse,
  )

  private class FakeStore(initial: Preferences = mutablePreferencesOf()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
  }
}
