package com.musinsa.gads.scenario

import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * T8: 캠페인 성과 조회 GAQL (스키마 검증).
 *
 * 테스트 계정이라 실제 metrics 는 모두 0 일 수 있지만,
 * GAQL 쿼리 자체가 **파싱되고 실행되는지** 가 핵심 검증 포인트.
 *
 * 확인 항목:
 *   - 모든 필드 경로가 v23 스키마에 존재
 *   - segments.date 로 일별 분해 가능
 *   - PMax 캠페인 type 필터링 (advertising_channel_type = 'PERFORMANCE_MAX')
 *
 * PRD v3 "광고주 대시보드 성과 리포트" 설계에 이 쿼리 스키마를 그대로 사용.
 */
class T8CampaignPerformanceTest : GadsApiTestBase() {

    @Test
    fun `T8 query campaign performance (schema only)`() {
        // 최근 7일 범위 (테스트 계정이라 metrics 는 없어도 OK)
        val end = LocalDate.now()
        val start = end.minusDays(7)

        val query = """
            SELECT
              campaign.id,
              campaign.name,
              campaign.status,
              campaign.advertising_channel_type,
              segments.date,
              metrics.impressions,
              metrics.clicks,
              metrics.cost_micros,
              metrics.conversions,
              metrics.conversions_value,
              metrics.ctr,
              metrics.average_cpc
            FROM campaign
            WHERE campaign.advertising_channel_type = 'PERFORMANCE_MAX'
              AND segments.date BETWEEN '$start' AND '$end'
            ORDER BY segments.date DESC, campaign.id
            LIMIT 100
        """.trimIndent()

        val request = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(customerId)
            .setQuery(query)
            .build()

        val rows = googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { svc ->
            svc.searchStreamCallable().call(request)
                .flatMap { it.resultsList }
        }

        println("=".repeat(88))
        println("T8 Campaign Performance — range $start ~ $end")
        println("=".repeat(88))
        println("row count: ${rows.size}")
        if (rows.isNotEmpty()) {
            println("%-12s %-32s %-10s %10s %8s %12s %10s"
                .format("Campaign", "Name", "Date", "Impr", "Clicks", "Cost μ", "Conv"))
            println("-".repeat(88))
            rows.take(20).forEach { row ->
                println("%-12d %-32s %-10s %10d %8d %12d %10.2f".format(
                    row.campaign.id,
                    row.campaign.name.take(30),
                    row.segments.date,
                    row.metrics.impressions,
                    row.metrics.clicks,
                    row.metrics.costMicros,
                    row.metrics.conversions,
                ))
            }
        } else {
            println("(no rows — expected for fresh test account)")
        }
        println("=".repeat(88))

        // 스키마만 검증하므로 exception 없으면 PASS.
        // rows.size >= 0 은 항상 참이지만 assertion 있어야 테스트 완료 마킹됨.
        assertThat(rows).isNotNull
    }
}
