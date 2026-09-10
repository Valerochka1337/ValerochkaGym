package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.backend.BackendApi
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackendApiTest {
  private class Store : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("owner-a", "owner@example.com", "access", "refresh"),
        )
    private var epoch = 0L
    override val sessionEpoch: Long
      get() = epoch

    override fun save(tokens: BackendTokens?) {
      epoch++
      session.value = tokens
    }
  }

  @Test
  fun `ordinary sync advertises health capability without sending health payload`() = runTest {
    var advertised: String? = null
    var bodyPresent = true
    val client =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
              advertised = chain.request().header("X-Gym-Capabilities")
              bodyPresent = chain.request().body != null
              Response.Builder()
                  .request(chain.request())
                  .protocol(Protocol.HTTP_1_1)
                  .code(200)
                  .message("OK")
                  .body("{}".toResponseBody())
                  .build()
            }
            .build()
    BackendApi(Store(), client, "https://test.invalid/").authorizedResponse("GET", "/sync", null)
    assertTrue("health-ledger-v1" in advertised.orEmpty().split(','))
    assertEquals(false, bodyPresent)
  }

  @Test
  fun `AI draft POSTs do not refresh or replay after unauthorized`() = runTest {
    val paths = mutableListOf<String>()
    val client =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                  paths += chain.request().url.encodedPath
                  Response.Builder()
                      .request(chain.request())
                      .protocol(Protocol.HTTP_1_1)
                      .code(401)
                      .message("Unauthorized")
                      .body("{\"code\":\"unauthorized\"}".toResponseBody())
                      .build()
                }
            )
            .build()
    val api = BackendApi(Store(), client, "https://test.invalid/")

    listOf("/ai/exercise-drafts", "/ai/inbody-drafts").forEach { path ->
      try {
        api.authorizedResponse(
            method = "POST",
            path = path,
            body = buildJsonObject {},
            retryOnUnauthorized = false,
        )
        fail("Expected an unauthorized response")
      } catch (expected: BackendException) {
        assertEquals(401, expected.status)
      }
    }

    assertEquals(listOf("/v1/ai/exercise-drafts", "/v1/ai/inbody-drafts"), paths)
  }

  @Test
  fun `raw request rejects a stale session epoch before dispatch`() = runTest {
    var calls = 0
    val client =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                  calls++
                  Response.Builder()
                      .request(chain.request())
                      .protocol(Protocol.HTTP_1_1)
                      .code(200)
                      .message("OK")
                      .body("{}".toResponseBody())
                      .build()
                }
            )
            .build()
    val api = BackendApi(Store(), client, "https://test.invalid/")

    try {
      api.authorizedRawResponse(
          method = "POST",
          path = "/health-ai-disclosure",
          rawBody = "{\"literal\":true}".encodeToByteArray(),
          expectedOwner = "owner-a",
          expectedSessionEpoch = 1L,
      )
      fail("Expected owner guard to reject")
    } catch (expected: BackendException) {
      assertEquals("owner_changed", expected.code)
    }
    assertEquals(0, calls)
  }

  @Test
  fun `raw request preserves journal bytes and binds the dispatch session`() = runTest {
    var requestBytes = byteArrayOf()
    val client =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                  val buffer = Buffer()
                  chain.request().body!!.writeTo(buffer)
                  requestBytes = buffer.readByteArray()
                  Response.Builder()
                      .request(chain.request())
                      .protocol(Protocol.HTTP_1_1)
                      .code(200)
                      .message("OK")
                      .body("{}".toResponseBody())
                      .build()
                }
            )
            .build()
    val store = Store()
    val api = BackendApi(store, client, "https://test.invalid/")
    val bytes = "{\"operationId\":\"fixed\", \"enabled\":true}".encodeToByteArray()

    val response =
        api.authorizedRawResponse(
            method = "POST",
            path = "/health-ai-disclosure",
            rawBody = bytes,
            expectedOwner = "owner-a",
            expectedSessionEpoch = store.sessionEpoch,
        )

    assertTrue(requestBytes contentEquals bytes)
    assertEquals("owner-a", response.owner)
    assertEquals(store.sessionEpoch, response.sessionEpoch)
  }
}
