package com.musinsa.gads.scenario

import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * T9: 상품별 성과 조회 GAQL — shopping_performance_view (스키마 검증).
 *
 * 확인 항목:
 *   - segments.product_* 필드 경로가 v23 에서 유효
 *   - item_id / brand / category / custom_attribute 분해 가능
 *   - PMax 캠페인과 조인 가능
 *
 * PRD v3 "상품별 리포트" 화면에 사용.
 * 이번 진단(product-feed-inspection)에서 custom_label 이 비어있음을 확인했으므로,
 * custom_attribute 값은 모두 empty 로 나올 예정.
 */
class T9ShoppingPerformanceTest : GadsApiTestBase() {

    @Test
    fun `T9 query shopping_performance_view (schema only)`() {
        val end = LocalDate.now()
        val start = end.minusDays(7)

        val query = """
            SELECT
              segments.date,
              segments.product_item_id,
              segments.product_title,
              segments.product_brand,
              segments.product_custom_attribute0,
              segments.product_custom_attribute1,
              segments.product_category_level1,
              segments.product_type_l1,
              campaign.id,
              campaign.name,
              metrics.impressions,
              metrics.clicks,
              metrics.cost_micros,
              metrics.conversions
            FROM shopping_performance_view
            WHERE segments.date BETWEEN '$start' AND '$end'
            ORDER BY metrics.cost_micros DESC
            LIMIT 50
        """.trimIndent()

        val request = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(customerId)
            .setQuery(query)
            .build()

        val rows = googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { svc ->
            svc.searchStreamCallable().call(request)
                .flatMap { it.resultsList }
        }

        println("=".repeat(100))
        println("T9 Shopping Performance — range $start ~ $end")
        println("=".repeat(100))
        println("row count: ${rows.size}")
        if (rows.isNotEmpty()) {
            println("%-12s %-18s %-28s %-14s %-12s %8s %8s %10s".format(
                "Date", "Item ID", "Title", "Brand", "Campaign", "Impr", "Clicks", "Cost μ"))
            println("-".repeat(100))
            rows.take(20).forEach { row ->
                println("%-12s %-18s %-28s %-14s %-12d %8d %8d %10d".format(
                    row.segments.date,
                    row.segments.productItemId.take(16),
                    row.segments.productTitle.take(26),
                    row.segments.productBrand.take(12),
                    row.campaign.id,
                    row.metrics.impressions,
                    row.metrics.clicks,
                    row.metrics.costMicros,
                ))
            }
        } else {
            println("(no rows — no impressions yet on test account)")
        }
        println("=".repeat(100))

        assertThat(rows).isNotNull
    }
}
