package com.valerochka1337.valerochkagym.data.ai

/**
 * One bounded model exchange. The concrete transport owns model selection and credentials.
 * Production calls bind expectedOwner/session epoch at dispatch and reject stale responses. This
 * interface never performs workout operations or retries a provider POST on its own.
 */
interface CoachModelGateway {
  suspend fun complete(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
      messages: List<AiApiMessage>,
      tools: List<AiApiTool>,
  ): AiApiChatResponse
}
