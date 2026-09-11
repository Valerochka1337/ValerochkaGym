package com.valerochka1337.valerochkagym.data.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CoachModelProbeTest {
  @Test
  fun `probe uses captured account session while workout stays synthetic`() = runTest {
    val gateway =
        object : CoachModelGateway {
          override suspend fun complete(
              expectedOwner: String,
              expectedSessionEpoch: Long?,
              messages: List<AiApiMessage>,
              tools: List<AiApiTool>,
          ): AiApiChatResponse {
            assertEquals("account-a", expectedOwner)
            assertEquals(7L, expectedSessionEpoch)
            assertTrue(
                messages.any {
                  (it.content as? JsonPrimitive)
                      ?.content
                      ?.contains("10000000-0000-4000-8000-000000000001") == true
                }
            )
            return AiApiChatResponse(
                choices =
                    listOf(
                        AiApiChoice(
                            message = AiApiResponseMessage(content = JsonPrimitive("Только текст"))
                        )
                    )
            )
          }
        }
    assertFalse(CoachModelProbe(CoachAgent(gateway)).verify("account-a", 7L).success)
  }

  @Test
  fun `probe verifies read and mutation tools on synthetic identity`() = runTest {
    val result =
        probe(
                tool("read", "get_workout_state", "{}"),
                tool(
                    "write",
                    "submit_workout_changes",
                    """{"base_revision":0,"operations":[{"action":"add_set","section_id":"10000000-0000-4000-8000-000000000002"}]}""",
                ),
            )
            .verify()
    assertTrue(result.success)
  }

  @Test
  fun `text only model fails compatibility probe`() = runTest {
    val result = probe(AiApiResponseMessage(content = JsonPrimitive("Готово"))).verify()
    assertFalse(result.success)
  }

  @Test
  fun `foreign section cannot pass synthetic mutation check`() = runTest {
    val result =
        probe(
                tool("read", "get_workout_state", "{}"),
                tool(
                    "write",
                    "submit_workout_changes",
                    """{"base_revision":0,"operations":[{"action":"add_set","section_id":"20000000-0000-4000-8000-000000000002"}]}""",
                ),
            )
            .verify()
    assertFalse(result.success)
  }

  private fun tool(id: String, name: String, args: String) =
      AiApiResponseMessage(
          toolCalls = listOf(AiApiToolCall(id, function = AiApiToolCallFunction(name, args)))
      )

  private fun probe(vararg responses: AiApiResponseMessage): CoachModelProbe {
    val queue = ArrayDeque(responses.toList())
    val api =
        object : CoachModelGateway {
          override suspend fun complete(
              expectedOwner: String,
              expectedSessionEpoch: Long?,
              messages: List<AiApiMessage>,
              tools: List<AiApiTool>,
          ): AiApiChatResponse {
            return AiApiChatResponse(
                choices =
                    listOf(
                        AiApiChoice(
                            message =
                                queue.removeFirstOrNull()
                                    ?: AiApiResponseMessage(content = JsonPrimitive("Завершено"))
                        )
                    )
            )
          }
        }
    return CoachModelProbe(CoachAgent(api))
  }
}
