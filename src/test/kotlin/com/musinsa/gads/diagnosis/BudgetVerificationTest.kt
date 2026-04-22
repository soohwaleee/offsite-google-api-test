package com.musinsa.gads.diagnosis

import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import org.junit.jupiter.api.Test

/**
 * T4/T5/T6 런에서 만든 3개 캠페인 + 예산의 실제 API 값 조회.
 *
 * UI 가 stale 할 수 있어 실제 서버 상태를 GAQL 로 직접 확인.
 *
 * 예상:
 *   - T4 campaign 23780947631 → budget ₩10,000 (변경 없음)
 *   - T5 campaign 23785564267 → budget ₩30,000 (T5 에서 변경됨)
 *   - T6 campaign 23780948603 → budget ₩10,000 (변경 없음)
 */
class BudgetVerificationTest : GadsApiTestBase() {

    @Test
    fun `verify budget amounts of T4 T5 T6 campaigns`() {
        val query = """
            SELECT
              campaign.id,
              campaign.name,
              campaign.status,
              campaign_budget.id,
              campaign_budget.amount_micros
            FROM campaign
            WHERE campaign.id IN (23780947631, 23785564267, 23780948603)
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
        println("Budget Verification — T4/T5/T6 run 2026-04-23-014940-22755")
        println("=".repeat(80))
        println("%-14s %-38s %-10s %-14s %-10s".format("Campaign ID", "Name", "Status", "Budget micros", "Budget KRW"))
        println("-".repeat(80))

        rows.forEach { row ->
            val micros = row.campaignBudget.amountMicros
            val krw = micros / 1_000_000L
            val label = when (row.campaign.id) {
                23780947631L -> "T4"
                23785564267L -> "T5 ← 변경됨?"
                23780948603L -> "T6"
                else -> "??"
            }
            println(
                "%-14d %-38s %-10s %-14d %,d KRW  [%s]".format(
                    row.campaign.id,
                    row.campaign.name.take(36),
                    row.campaign.status.name,
                    micros,
                    krw,
                    label,
                ),
            )
        }
        println("=".repeat(80))
    }
}
