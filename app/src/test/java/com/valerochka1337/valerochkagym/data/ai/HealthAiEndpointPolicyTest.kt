package com.valerochka1337.valerochkagym.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthAiEndpointPolicyTest {
    @Test
    fun `public HTTP is rejected while HTTPS is accepted`() {
        assertEquals(
            HealthAiEndpointDecision.PublicHttpRejected,
            healthAiEndpointDecision("http://medical.example/v1/", loopbackHttpConsent = true),
        )
        assertEquals(
            HealthAiEndpointDecision.Allowed,
            healthAiEndpointDecision("https://medical.example/v1/", loopbackHttpConsent = false),
        )
    }

    @Test
    fun `loopback HTTP requires a fresh confirmation`() {
        assertEquals(
            HealthAiEndpointDecision.LoopbackConsentRequired,
            healthAiEndpointDecision("http://127.0.0.1:11434/v1/", loopbackHttpConsent = false),
        )
        assertEquals(
            HealthAiEndpointDecision.Allowed,
            healthAiEndpointDecision("http://localhost:8080/v1/", loopbackHttpConsent = true),
        )
    }

    @Test
    fun `report policy temporarily accepts valid public HTTP while shared policy remains strict`() {
        listOf("http://medical.example/v1/", "http://192.168.1.15:8080/v1/").forEach { baseUrl ->
            assertEquals(
                HealthAiEndpointDecision.PublicHttpRejected,
                healthAiEndpointDecision(baseUrl, loopbackHttpConsent = false),
            )
            assertEquals(
                HealthAiEndpointDecision.Allowed,
                healthReportAiEndpointDecision(baseUrl, loopbackHttpConsent = false),
            )
        }
    }

    @Test
    fun `report policy preserves HTTPS invalid and loopback decisions`() {
        assertEquals(
            HealthAiEndpointDecision.Allowed,
            healthReportAiEndpointDecision("https://medical.example/v1/", loopbackHttpConsent = false),
        )
        assertEquals(
            HealthAiEndpointDecision.Invalid,
            healthReportAiEndpointDecision("medical.example", loopbackHttpConsent = false),
        )
        assertEquals(
            HealthAiEndpointDecision.LoopbackConsentRequired,
            healthReportAiEndpointDecision("http://localhost:11434/v1/", loopbackHttpConsent = false),
        )
    }
}
