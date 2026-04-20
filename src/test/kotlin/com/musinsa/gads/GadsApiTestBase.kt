package com.musinsa.gads

import com.google.ads.googleads.lib.GoogleAdsClient
import com.musinsa.gads.config.GoogleAdsProperties
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * 모든 Google Ads API 통합 테스트의 베이스.
 *
 * 책임:
 *  - Spring context 부팅 (테스트 프로파일)
 *  - GoogleAdsClient 주입
 *  - 환경변수 누락 시 전체 테스트 skip (flaky와 구분)
 *  - 테스트 대상 customerId 제공
 */
@SpringBootTest(
    classes = [OffsiteGadsTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ActiveProfiles("test")
abstract class GadsApiTestBase {

    @Autowired
    protected lateinit var googleAdsClient: GoogleAdsClient

    @Autowired
    protected lateinit var properties: GoogleAdsProperties

    protected val customerId: String get() = properties.customerId

    @BeforeEach
    fun assumeCredentials() {
        Assumptions.assumeTrue(
            properties.isComplete(),
            "Google Ads credentials missing: ${properties.missingFields()}. " +
                "See .env.example",
        )
    }
}
