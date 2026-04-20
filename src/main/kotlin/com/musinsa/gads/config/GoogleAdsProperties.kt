package com.musinsa.gads.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "google-ads")
data class GoogleAdsProperties(
    val clientId: String,
    val clientSecret: String,
    val developerToken: String,
    val refreshToken: String,
    val loginCustomerId: String,
    val customerId: String,
) {
    fun isComplete(): Boolean = listOf(
        clientId, clientSecret, developerToken, refreshToken, loginCustomerId, customerId
    ).all { it.isNotBlank() }

    fun missingFields(): List<String> = buildList {
        if (clientId.isBlank()) add("clientId")
        if (clientSecret.isBlank()) add("clientSecret")
        if (developerToken.isBlank()) add("developerToken")
        if (refreshToken.isBlank()) add("refreshToken")
        if (loginCustomerId.isBlank()) add("loginCustomerId")
        if (customerId.isBlank()) add("customerId")
    }
}
