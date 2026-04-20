package com.musinsa.gads.scenario

import com.google.ads.googleads.v17.services.GoogleAdsRow
import com.google.ads.googleads.v17.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * T0: 사전점검 (precheck).
 *
 * 목적: API 호출 자체가 가능한지를 최소 비용으로 검증.
 * 실패 시 T1~T11 전부 차단되므로 개별 테스트로 분리.
 *
 * ⚠️ google-ads-java 의 v17 패키지는 플레이스홀더. 실제 릴리스된 client 버전의
 *   최신 패키지 경로(예: v23)로 교체해야 한다. 빌드 시 import 경로 오류가
 *   나면 해당 버전의 실제 sub-package 이름을 확인하고 교체.
 */
class T0PrecheckTest : GadsApiTestBase() {

    /**
     * T0-1. customer 리소스가 조회되면 OAuth + developer-token 모두 유효.
     */
    @Test
    fun `T0-1 OAuth and developer token are valid`() {
        val query = """
            SELECT customer.id, customer.descriptive_name, customer.currency_code,
                   customer.test_account, customer.manager
            FROM customer
        """.trimIndent()

        val rows = runSearch(query)

        assertThat(rows).isNotEmpty
        val id = rows.first().customer.id
        assertThat(id.toString()).isEqualTo(customerId)
    }

    /**
     * T0-2. 현재 사용 중인 계정이 Test Account 여야 한다 (Test Access 레벨).
     */
    @Test
    fun `T0-2 customer is a test account`() {
        val query = "SELECT customer.test_account FROM customer"
        val rows = runSearch(query)

        assertThat(rows.first().customer.testAccount)
            .`as`("현재 Developer Token이 Test Access면 test_account=true 여야 함")
            .isTrue()
    }

    /**
     * T0-3. Merchant Center 링크가 ENABLED 상태여야 Retail PMax 캠페인 생성 가능.
     */
    @Test
    fun `T0-3 merchant center link is enabled`() {
        val query = """
            SELECT merchant_center_link.id,
                   merchant_center_link.merchant_center_id,
                   merchant_center_link.status
            FROM merchant_center_link
        """.trimIndent()

        val rows = runSearch(query)

        assertThat(rows)
            .`as`("Retail PMax 캠페인 생성 전 Merchant Center 링크 필수 (PRD v3)")
            .isNotEmpty
        assertThat(rows.any { it.merchantCenterLink.status.name == "ENABLED" })
            .`as`("ENABLED 상태의 Merchant Center 링크가 최소 1개 필요")
            .isTrue()
    }

    private fun runSearch(query: String): List<GoogleAdsRow> {
        val serviceClient = googleAdsClient
            .latestVersion
            .createGoogleAdsServiceClient()

        val request = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(customerId)
            .setQuery(query)
            .build()

        serviceClient.use { client ->
            return client.searchStreamCallable()
                .call(request)
                .flatMap { it.resultsList }
                .toList()
        }
    }
}
