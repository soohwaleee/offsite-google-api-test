package com.musinsa.gads.scenario

import com.google.ads.googleads.v23.resources.CampaignBudget
import com.google.ads.googleads.v23.services.CampaignBudgetOperation
import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.google.ads.googleads.lib.utils.FieldMasks
import com.musinsa.gads.GadsApiTestBase
import com.musinsa.gads.support.ResourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * T5: CampaignBudget amount 수정.
 *
 * 시나리오:
 *   1. T1 방식으로 신선한 캠페인 생성 (budget ₩10,000)
 *   2. FieldMask 로 amount_micros 만 ₩30,000 으로 수정
 *   3. GAQL 로 재조회하여 반영 확인
 *
 * 목적:
 *   - 실행 중인 캠페인의 예산 변경이 API 로 가능한지 확인
 *   - amount_micros 단위 계산 검증 (1 KRW = 1,000,000 micros)
 */
class T5UpdateBudgetTest : GadsApiTestBase() {

    @Test
    fun `T5 update campaign budget amount`() {
        // --- Setup ---
        val fixture = MinimalPmaxFixture(googleAdsClient, customerId).create()
        trackResource(fixture.budgetResourceName, ResourceType.CAMPAIGN_BUDGET, testId = "T5")
        trackResource(fixture.campaignResourceName, ResourceType.CAMPAIGN, testId = "T5")
        trackResource(fixture.assetGroupResourceName, ResourceType.ASSET_GROUP, testId = "T5")
        trackResource(fixture.filterResourceName, ResourceType.ASSET_GROUP_LISTING_GROUP_FILTER, testId = "T5")

        // --- Execute: ₩10,000 → ₩30,000 ---
        val newAmountMicros = 30_000_000_000L
        val updated = CampaignBudget.newBuilder()
            .setResourceName(fixture.budgetResourceName)
            .setAmountMicros(newAmountMicros)
            .build()

        val op = CampaignBudgetOperation.newBuilder().apply {
            update = updated
            updateMask = FieldMasks.allSetFieldsOf(updated)
        }.build()

        val updatedResourceName = googleAdsClient.latestVersion.createCampaignBudgetServiceClient().use {
            it.mutateCampaignBudgets(customerId, listOf(op)).resultsList.first().resourceName
        }

        // --- Verify ---
        val query = """
            SELECT
              campaign_budget.id,
              campaign_budget.amount_micros
            FROM campaign_budget
            WHERE campaign_budget.resource_name = '${fixture.budgetResourceName}'
        """.trimIndent()

        val request = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(customerId)
            .setQuery(query)
            .build()

        val row = googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { svc ->
            svc.searchStreamCallable().call(request)
                .flatMap { it.resultsList }
                .firstOrNull()
        } ?: error("CampaignBudget not found after update")

        // --- Assertions ---
        assertThat(updatedResourceName).isEqualTo(fixture.budgetResourceName)
        assertThat(row.campaignBudget.amountMicros).isEqualTo(newAmountMicros)
    }
}
