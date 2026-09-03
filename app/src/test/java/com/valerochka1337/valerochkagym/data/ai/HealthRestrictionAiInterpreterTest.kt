package com.valerochka1337.valerochkagym.data.ai
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class HealthRestrictionAiInterpreterTest {
 @Test fun `valid response is active even when model suggests lifted`()=runTest{val api=Api("{\"proposals\":[{\"limitedActivity\":\"Бег\",\"source\":\"USER\",\"status\":\"LIFTED\"}]}");val r=AiHealthRestrictionAiInterpreter(api,Config("https://x.test/v1/"),Json{}).interpret("боль");assertTrue(r is RestrictionInterpretation.Success);assertEquals(com.valerochka1337.valerochkagym.domain.health.HealthRestrictionState.ACTIVE,(r as RestrictionInterpretation.Success).proposals.single().state)}
 @Test fun `public HTTP and unconfirmed loopback make zero API calls`()=runTest{val api=Api("{}");val public=AiHealthRestrictionAiInterpreter(api,Config("http://x.test/v1/"),Json{});assertTrue(public.interpret("x") is RestrictionInterpretation.Failure);val local=AiHealthRestrictionAiInterpreter(api,Config("http://127.0.0.1:1/v1/"),Json{});assertTrue(local.interpret("x") is RestrictionInterpretation.Failure);assertEquals(0,api.calls)}
 @Test fun `cancellation propagates instead of producing a manual fallback`()=runTest{val api=Api("{}",throwCancellation=true);val interpreter=AiHealthRestrictionAiInterpreter(api,Config("https://x.test/v1/"),Json{});var cancelled=false;try{interpreter.interpret("исходный текст")}catch(_:CancellationException){cancelled=true};assertTrue(cancelled);assertEquals(1,api.calls)}
 private class Config(val url:String):AiApiConfigurationProvider{override val isConfigured: Flow<Boolean> = flowOf(true);override suspend fun connection()=AiApiConnection(url,"k");override suspend fun requestConfiguration()=AiApiRequestConfiguration(AiApiConnection(url,"k"),"m")}
 private class Api(val text:String,private val throwCancellation:Boolean=false):AiApi{var calls=0;override suspend fun createCompletion(endpoint:String,authorization:String,request:AiApiChatRequest):AiApiChatResponse{calls++;if(throwCancellation)throw CancellationException("cancelled");return AiApiChatResponse(listOf(AiApiChoice(AiApiResponseMessage(content=JsonPrimitive(text)))))};override suspend fun getModels(endpoint:String,authorization:String)=AiModelsResponse()}
}
