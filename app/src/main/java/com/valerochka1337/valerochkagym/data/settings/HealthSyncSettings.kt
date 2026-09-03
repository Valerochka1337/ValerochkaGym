package com.valerochka1337.valerochkagym.data.settings

/** Independently consented primary-data categories for Google Sheets. */
enum class HealthSyncCategory {
    WORKOUTS_AND_CONFIGURATION,
    MEASUREMENTS,
    HEALTH_REPORTS_AND_OBSERVATIONS,
    HEALTH_RESTRICTIONS,
}

data class HealthSyncSettings(
    val enabled: Boolean,
    val categories: Set<HealthSyncCategory>,
) {
    fun isEnabled(category: HealthSyncCategory): Boolean = enabled && category in categories

    companion object {
        val Disabled = HealthSyncSettings(false, emptySet())
    }
}
