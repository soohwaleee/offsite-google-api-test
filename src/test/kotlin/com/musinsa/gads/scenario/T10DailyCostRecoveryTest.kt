package com.musinsa.gads.scenario

import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * T10: 일별 비용 집계 쿼리 (정산 회수용, 스키마 검증).
 *
 * 확인 항목:
 *   - customer 수준에서 일별 cost 집계 가능
 *   - 과거 30일 범위 조회 가능
 *   - 같은 쿼리를 campaign 수준으로도 깨서 확인
 *
 * PRD v3 "일별 비용 회수/정산" 배치에서 매일 전일 비용을 끌어와
 * 광고주에게 청구하는 용도.
 *
 * 주의: 실제 프로덕션에서는 timezone 이슈 있음. Google Ads 는
 * 계정 timezone 기준으로 segments.date 를 잘라서 반환하므로
 * 무신사 서비스 timezone (Asia/Seoul) 과 계정 timezone 이 일치해야
 * 일자 경계에서 비용 누락이 없다.
 */
class T10DailyCostRecoveryTest : GadsApiTestBase() {

    @Test
    fun `T10 query daily cost at customer level`() {
        val end = LocalDate.now()
        val start = end.minusDays(30)

        // customer_client 또는 customer 리소스로 계정 전체 일별 비용 집계
        val query = """
            SELECT
              customer.id,
              customer.currency_code,
              customer.time_zone,
              segments.date,
              metrics.cost_micros,
              metrics.impressions,
              metrics.clicks
            FROM customer
            WHERE segments.date BETWEEN '$start' AND '$end'
            ORDER BY segments.date DESC
        """.trimIndent()

        val request = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(customerId)
            .setQuery(query)
            .build()

        val rows = googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { svc ->
            svc.searchStreamCallable().call(request)
                .flatMap { it.resultsList }
        }

        println("=".repeat(80))
        println("T10 Daily Cost Recovery (customer level) — range $start ~ $end")
        println("=".repeat(80))
        println("row count: ${rows.size}")
        val firstRow = rows.firstOrNull()
        if (firstRow != null) {
            println("account tz: ${firstRow.customer.timeZone}, currency: ${firstRow.customer.currencyCode}")
        }
        println("-".repeat(80))
        if (rows.isNotEmpty()) {
            println("%-12s %15s %12s %10s".format("Date", "Cost micros", "Impressions", "Clicks"))
            rows.forEach { row ->
                println("%-12s %15d %12d %10d".format(
                    row.segments.date,
                    row.metrics.costMicros,
                    row.metrics.impressions,
                    row.metrics.clicks,
                ))
            }
        } else {
            println("(no rows — no spend yet on test account)")
        }
        println("=".repeat(80))

        assertThat(rows).isNotNull
    }

    @Test
    fun `T10 query daily cost broken down by campaign`() {
        val end = LocalDate.now()
        val start = end.minusDays(30)

        val query = """
            SELECT
              campaign.id,
              campaign.name,
              segments.date,
              metrics.cost_micros,
              metrics.impressions,
              metrics.clicks,
              metrics.conversions_value
            FROM campaign
            WHERE segments.date BETWEEN '$start' AND '$end'
              AND campaign.advertising_channel_type = 'PERFORMANCE_MAX'
            ORDER BY segments.date DESC, campaign.id
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
        println("T10b Daily Cost Recovery (campaign level) — range $start ~ $end")
        println("=".repeat(88))
        println("row count: ${rows.size}")
        println("(이 값을 일별로 GROUP BY campaign.id 해서 프로덕션 정산 테이블에 적재)")
        println("=".repeat(88))

        assertThat(rows).isNotNull
    }
}
