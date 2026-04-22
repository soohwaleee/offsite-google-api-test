package com.musinsa.gads.scenario

import com.google.ads.googleads.v23.resources.Campaign
import com.google.ads.googleads.v23.services.CampaignOperation
import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.google.ads.googleads.lib.utils.FieldMasks
import com.musinsa.gads.GadsApiTestBase
import com.musinsa.gads.support.ResourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * T4: 캠페인 수정 (name + end_date_time).
 *
 * 시나리오:
 *   1. T1 과 동일한 방식으로 신선한 캠페인 생성
 *   2. FieldMask 를 써서 name + end_date_time 만 수정
 *   3. GAQL 로 재조회하여 값이 실제 반영됐는지 확인
 *
 * 목적:
 *   - PMax 캠페인의 name/end_date 가 사후 변경 가능한지 확인
 *   - FieldMask 방식이 v23 에서 정상 동작하는지 확인
 *   - PRD v3 "광고주가 캠페인 수정" 플로우 검증
 */
class T4UpdateCampaignTest : GadsApiTestBase() {

    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Test
    fun `T4 update campaign name and end_date`() {
        // --- Setup: fresh campaign ---
        val fixture = MinimalPmaxFixture(googleAdsClient, customerId).create()
        trackResource(fixture.budgetResourceName, ResourceType.CAMPAIGN_BUDGET, testId = "T4")
        trackResource(fixture.campaignResourceName, ResourceType.CAMPAIGN, testId = "T4")
        trackResource(fixture.assetGroupResourceName, ResourceType.ASSET_GROUP, testId = "T4")
        trackResource(fixture.filterResourceName, ResourceType.ASSET_GROUP_LISTING_GROUP_FILTER, testId = "T4")

        // --- Execute: update name + end_date_time ---
        val newName = "test-pmax-${fixture.runSuffix}-renamed"
        // 종료일을 원래(+7일)에서 +14일로 연장
        val newEndAt = LocalDate.now().atStartOfDay()
            .plusDays(14).plusHours(23).plusMinutes(59).plusSeconds(59)
        val newEndDateTime = newEndAt.format(dateTimeFormat)

        val updated: Campaign = Campaign.newBuilder()
            .setResourceName(fixture.campaignResourceName)
            .setName(newName)
            .setEndDateTime(newEndDateTime)
            .build()

        val op = CampaignOperation.newBuilder().apply {
            update = updated
            updateMask = FieldMasks.allSetFieldsOf(updated)
        }.build()

        val updatedResourceName = googleAdsClient.latestVersion.createCampaignServiceClient().use {
            it.mutateCampaigns(customerId, listOf(op)).resultsList.first().resourceName
        }

        // --- Verify: GAQL 재조회 ---
        val query = """
            SELECT
              campaign.id,
              campaign.name,
              campaign.end_date_time,
              campaign.status
            FROM campaign
            WHERE campaign.resource_name = '${fixture.campaignResourceName}'
        """.trimIndent()

        val request = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(customerId)
            .setQuery(query)
            .build()

        val row = googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { svc ->
            svc.searchStreamCallable().call(request)
                .flatMap { it.resultsList }
                .firstOrNull()
        } ?: error("Campaign not found after update")

        // --- Assertions ---
        assertThat(updatedResourceName).isEqualTo(fixture.campaignResourceName)
        assertThat(row.campaign.name).isEqualTo(newName)
        // end_date_time 은 "2026-05-06 23:59:59" 형식으로 반환. 접두부만 비교.
        assertThat(row.campaign.endDateTime).startsWith(newEndAt.toLocalDate().toString())
    }
}
