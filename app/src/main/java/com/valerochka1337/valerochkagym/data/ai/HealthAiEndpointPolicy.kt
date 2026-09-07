package com.valerochka1337.valerochkagym.data.ai

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Privacy gate evaluated before a document/photo is prepared or any network call is made. */
sealed interface HealthAiEndpointDecision {
    data object Allowed : HealthAiEndpointDecision
    data object LoopbackConsentRequired : HealthAiEndpointDecision
    data object PublicHttpRejected : HealthAiEndpointDecision
    data object Invalid : HealthAiEndpointDecision
}

fun healthAiEndpointDecision(baseUrl: String, loopbackHttpConsent: Boolean): HealthAiEndpointDecision {
    val url = baseUrl.toHttpUrlOrNull() ?: return HealthAiEndpointDecision.Invalid
    if (url.isHttps) return HealthAiEndpointDecision.Allowed
    if (url.scheme != "http") return HealthAiEndpointDecision.Invalid
    val loopback = url.host.equals("localhost", ignoreCase = true) ||
        url.host == "127.0.0.1" || url.host == "::1"
    if (!loopback) return HealthAiEndpointDecision.PublicHttpRejected
    return if (loopbackHttpConsent) HealthAiEndpointDecision.Allowed
    else HealthAiEndpointDecision.LoopbackConsentRequired
}

/**
 * Temporary, report-reader-only HTTP exception for explicitly disclosed document uploads.
 *
 * TODO(health-report-http): remove this wrapper and restore [healthAiEndpointDecision] at the
 * report reader checkpoints when the temporary HTTP study support is rolled back.
 */
fun healthReportAiEndpointDecision(
    baseUrl: String,
    loopbackHttpConsent: Boolean,
): HealthAiEndpointDecision = when (val shared = healthAiEndpointDecision(baseUrl, loopbackHttpConsent)) {
    HealthAiEndpointDecision.PublicHttpRejected -> HealthAiEndpointDecision.Allowed
    else -> shared
}
