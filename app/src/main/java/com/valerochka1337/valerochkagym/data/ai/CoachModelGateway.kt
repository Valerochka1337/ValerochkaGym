package com.valerochka1337.valerochkagym.data.ai

/**
 * One bounded model exchange. The concrete transport owns model selection and credentials.
 * Production calls bind expectedOwner/session epoch at dispatch and reject stale responses. This
 * interface never performs workout operations or retries a provider POST on its own.
 */
interface CoachModelGateway {
  suspend fun systemPrompt(expectedOwner: String, expectedSessionEpoch: Long?): String

  fun stream(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
      messages: List<AiApiMessage>,
      tools: List<AiApiTool>,
  ): kotlinx.coroutines.flow.Flow<CoachModelEvent>
}

sealed interface CoachModelEvent {
  data class TextDelta(val delta: String) : CoachModelEvent

  data class Completed(val completion: AiApiChatResponse) : CoachModelEvent
}
