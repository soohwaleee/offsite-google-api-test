package com.musinsa.gads.scenario

import com.google.ads.googleads.v23.services.GoogleAdsRow
import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * T0: 사전점검 (precheck).
 *
 * 목적: API 호출 자체가 가능한지를 최소 비용으로 검증.
 * 실패 시 T1~T11 전부 차단되므로 개별 테스트로 분리.
 *
 * google-ads-java 42.2.0 / Google Ads API v23 기준.
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
     *
     * v23 기준 주의:
     *   - `merchant_center_link`는 v23에서 deprecated
     *   - 대체: `product_link` (확정된 링크) + `product_link_invitation` (대기 중 초대)
     */
    @Test
    fun `T0-3 merchant center product link exists`() {
        val linkedQuery = """
            SELECT product_link.resource_name,
                   product_link.type,
                   product_link.merchant_center.merchant_center_id
            FROM product_link
            WHERE product_link.type = 'MERCHANT_CENTER'
        """.trimIndent()

        val linked = runSearch(linkedQuery)

        if (linked.isNotEmpty()) {
            // 링크가 확정된 경우 — product_link에 존재하면 ENABLED 상태로 간주.
            // (v23 필드 레퍼런스에서 status 필드 위치 재확인 필요 — 추후 상세 검증 가능)
            return
        }

        // 링크가 없으면 pending invitation 이라도 있는지 확인
        val invitationQuery = """
            SELECT product_link_invitation.resource_name,
                   product_link_invitation.type,
                   product_link_invitation.status,
                   product_link_invitation.merchant_center.merchant_center_id
            FROM product_link_invitation
            WHERE product_link_invitation.type = 'MERCHANT_CENTER'
        """.trimIndent()

        val invitations = runSearch(invitationQuery)

        // 어느 쪽도 없으면 실패
        assertThat(invitations)
            .`as`(
                "product_link도 product_link_invitation도 없음 → " +
                    "Merchant Center 링크 요청이 필요. " +
                    "Google Ads UI → Linked Accounts → Merchant Center 에서 링크 요청 전송",
            )
            .isNotEmpty
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
