package com.valerochka1337.valerochkagym.di

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class NetworkModuleTest {

    @Test
    fun `ai client waits for long completions without changing the shared client`() {
        val sharedClient = NetworkModule.provideOkHttpClient()

        val aiClient = NetworkModule.provideAiOkHttpClient(sharedClient)

        assertEquals(10_000, sharedClient.connectTimeoutMillis)
        assertEquals(10_000, sharedClient.writeTimeoutMillis)
        assertEquals(10_000, sharedClient.readTimeoutMillis)
        assertEquals(0, sharedClient.callTimeoutMillis)
        assertEquals(
            TimeUnit.SECONDS.toMillis(NetworkModule.AI_CONNECT_TIMEOUT_SECONDS).toInt(),
            aiClient.connectTimeoutMillis,
        )
        assertEquals(
            TimeUnit.SECONDS.toMillis(NetworkModule.AI_WRITE_TIMEOUT_SECONDS).toInt(),
            aiClient.writeTimeoutMillis,
        )
        assertEquals(
            TimeUnit.SECONDS.toMillis(NetworkModule.AI_READ_TIMEOUT_SECONDS).toInt(),
            aiClient.readTimeoutMillis,
        )
        assertEquals(
            TimeUnit.SECONDS.toMillis(NetworkModule.AI_CALL_TIMEOUT_SECONDS).toInt(),
            aiClient.callTimeoutMillis,
        )
    }
}
