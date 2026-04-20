package com.musinsa.gads.config

import com.google.ads.googleads.lib.GoogleAdsClient
import com.google.auth.oauth2.UserCredentials
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class GoogleAdsClientConfig(
    private val properties: GoogleAdsProperties,
) {

    @Bean
    fun googleAdsClient(): GoogleAdsClient {
        require(properties.isComplete()) {
            "Google Ads credentials missing: ${properties.missingFields()}. " +
                "Copy .env.example to .env and fill in values, or export environment variables."
        }

        val credentials = UserCredentials.newBuilder()
            .setClientId(properties.clientId)
            .setClientSecret(properties.clientSecret)
            .setRefreshToken(properties.refreshToken)
            .build()

        return GoogleAdsClient.newBuilder()
            .setCredentials(credentials)
            .setDeveloperToken(properties.developerToken)
            .setLoginCustomerId(properties.loginCustomerId.toLong())
            .build()
    }
}
