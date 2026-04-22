package com.musinsa.gads.scenario

import com.google.ads.googleads.v23.enums.CampaignStatusEnum.CampaignStatus
import com.google.ads.googleads.v23.resources.Campaign
import com.google.ads.googleads.v23.services.CampaignOperation
import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.google.ads.googleads.lib.utils.FieldMasks
import com.musinsa.gads.GadsApiTestBase
import com.musinsa.gads.support.ResourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * T6: Campaign status 토글 (PAUSED ↔ ENABLED).
 *
 * 시나리오:
 *   1. T1 방식으로 PAUSED 상태 캠페인 생성
 *   2. FieldMask 로 status 만 ENABLED 로 전환
 *   3. GAQL 재조회 → ENABLED 확인
 *   4. 다시 PAUSED 로 토글
 *   5. GAQL 재조회 → PAUSED 확인
 *
 * 목적:
 *   - PRD v3 "광고주가 캠페인 일시정지/재개" 플로우 검증
 *   - 테스트 계정에서도 상태 전이가 실제처럼 동작하는지 확인
 *   - 정책 검토 미완료 상태에서도 상태 전환이 가능한지 확인
 */
class T6ToggleCampaignTest : GadsApiTestBase() {

    @Test
    fun `T6 toggle campaign status PAUSED to ENABLED and back`() {
        // --- Setup: 초기 상태 PAUSED ---
        val fixture = MinimalPmaxFixture(googleAdsClient, customerId).create()
        trackResource(fixture.budgetResourceName, ResourceType.CAMPAIGN_BUDGET, testId = "T6")
        trackResource(fixture.campaignResourceName, ResourceType.CAMPAIGN, testId = "T6")
        trackResource(fixture.assetGroupResourceName, ResourceType.ASSET_GROUP, testId = "T6")
        trackResource(fixture.filterResourceName, ResourceType.ASSET_GROUP_LISTING_GROUP_FILTER, testId = "T6")

        assertThat(fetchStatus(fixture.campaignResourceName)).isEqualTo(CampaignStatus.PAUSED)

        // --- Step A: PAUSED → ENABLED ---
        updateStatus(fixture.campaignResourceName, CampaignStatus.ENABLED)
        assertThat(fetchStatus(fixture.campaignResourceName)).isEqualTo(CampaignStatus.ENABLED)

        // --- Step B: ENABLED → PAUSED ---
        updateStatus(fixture.campaignResourceName, CampaignStatus.PAUSED)
        assertThat(fetchStatus(fixture.campaignResourceName)).isEqualTo(CampaignStatus.PAUSED)
    }

    private fun updateStatus(campaignResourceName: String, status: CampaignStatus) {
        val updated = Campaign.newBuilder()
            .setResourceName(campaignResourceName)
            .setStatus(status)
            .build()

        val op = CampaignOperation.newBuilder().apply {
            update = updated
            updateMask = FieldMasks.allSetFieldsOf(updated)
        }.build()

        googleAdsClient.latestVersion.createCampaignServiceClient().use {
            it.mutateCampaigns(customerId, listOf(op))
        }
    }

    private fun fetchStatus(campaignResourceName: String): CampaignStatus {
        val query = """
            SELECT campaign.status
            FROM campaign
            WHERE campaign.resource_name = '$campaignResourceName'
        """.trimIndent()

        val request = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(customerId)
            .setQuery(query)
            .build()

        return googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { svc ->
            svc.searchStreamCallable().call(request)
                .flatMap { it.resultsList }
                .map { it.campaign.status }
                .firstOrNull()
        } ?: error("Campaign not found: $campaignResourceName")
    }
}
