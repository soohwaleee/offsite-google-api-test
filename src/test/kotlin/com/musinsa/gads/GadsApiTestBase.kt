package com.musinsa.gads

import com.google.ads.googleads.lib.GoogleAdsClient
import com.musinsa.gads.config.GoogleAdsProperties
import com.musinsa.gads.support.GadsResourceTracker
import com.musinsa.gads.support.ResourceType
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
 *  - GadsResourceTracker 주입 + 편의 메서드 제공
 *
 * 자동 cleanup 은 **하지 않는다**. 생성된 리소스는 그대로 유지되며,
 * `./gradlew cleanupRun -PrunId=xxx` 명령으로 명시적으로 삭제한다.
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

    @Autowired
    protected lateinit var tracker: GadsResourceTracker

    protected val customerId: String get() = properties.customerId

    @BeforeEach
    fun assumeCredentials() {
        Assumptions.assumeTrue(
            properties.isComplete(),
            "Google Ads credentials missing: ${properties.missingFields()}. " +
                "See .env.example",
        )
    }

    /**
     * 생성한 Google Ads 리소스를 tracker 에 기록하고 resourceName 그대로 반환.
     * 체이닝하기 편하게 제네릭으로 두었다.
     *
     *   val budget = trackResource(
     *       createdResourceName,
     *       ResourceType.CAMPAIGN_BUDGET,
     *       testId = "T1",
     *   )
     */
    protected fun trackResource(
        resourceName: String,
        type: ResourceType,
        testId: String,
    ): String {
        tracker.track(resourceName, type, testId)
        return resourceName
    }
}
