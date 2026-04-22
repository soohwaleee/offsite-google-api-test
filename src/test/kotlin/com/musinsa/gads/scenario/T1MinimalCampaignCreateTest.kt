package com.musinsa.gads.scenario

import com.google.ads.googleads.v23.common.MaximizeConversionValue
import com.google.ads.googleads.v23.enums.AdvertisingChannelTypeEnum.AdvertisingChannelType
import com.google.ads.googleads.v23.enums.AssetGroupStatusEnum.AssetGroupStatus
import com.google.ads.googleads.v23.enums.BudgetDeliveryMethodEnum.BudgetDeliveryMethod
import com.google.ads.googleads.v23.enums.CampaignStatusEnum.CampaignStatus
import com.google.ads.googleads.v23.enums.EuPoliticalAdvertisingStatusEnum.EuPoliticalAdvertisingStatus
import com.google.ads.googleads.v23.enums.ListingGroupFilterListingSourceEnum.ListingGroupFilterListingSource
import com.google.ads.googleads.v23.enums.ListingGroupFilterTypeEnum.ListingGroupFilterType
import com.google.ads.googleads.v23.resources.AssetGroup
import com.google.ads.googleads.v23.resources.AssetGroupListingGroupFilter
import com.google.ads.googleads.v23.resources.Campaign
import com.google.ads.googleads.v23.resources.CampaignBudget
import com.google.ads.googleads.v23.services.AssetGroupListingGroupFilterOperation
import com.google.ads.googleads.v23.services.AssetGroupOperation
import com.google.ads.googleads.v23.services.CampaignBudgetOperation
import com.google.ads.googleads.v23.services.CampaignOperation
import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import com.musinsa.gads.support.ResourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * T1: 캠페인 최소 생성 (스마트 선택).
 *
 * 4단계 체인 모두 성공해야 PASS:
 *   1. CampaignBudget (일예산 ₩10,000)
 *   2. Campaign (PMax Retail, PAUSED)
 *   3. AssetGroup (Asset 없이 — Retail + MC 링크 특례)
 *   4. AssetGroupListingGroupFilter (UNIT_INCLUDED, 스마트 선택)
 *
 * 리소스는 자동 삭제되지 않음. 확인 후 `./gradlew cleanupRun -PrunId=...` 로 삭제.
 */
class T1MinimalCampaignCreateTest : GadsApiTestBase() {

    // v23: Campaign.start_date/end_date → start_date_time/end_date_time (format: "yyyy-MM-dd HH:mm:ss").
    // end_date_time 은 반드시 23:59:59 로 끝나야 함 (하루의 끝).
    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Test
    fun `T1 minimal Retail PMax campaign creation`() {
        val runSuffix = UUID.randomUUID().toString().substring(0, 8)
        val merchantId = fetchLinkedMerchantId()

        // --- Step 1: CampaignBudget ---
        val budgetResourceName = createBudget(runSuffix)
        trackResource(budgetResourceName, ResourceType.CAMPAIGN_BUDGET, testId = "T1")

        // --- Step 2: Campaign (PMax Retail) ---
        val campaignResourceName = createCampaign(
            runSuffix = runSuffix,
            budgetResourceName = budgetResourceName,
            merchantId = merchantId,
        )
        trackResource(campaignResourceName, ResourceType.CAMPAIGN, testId = "T1")

        // --- Step 3: AssetGroup (Asset 없이) ---
        val assetGroupResourceName = createAssetGroup(runSuffix, campaignResourceName)
        trackResource(assetGroupResourceName, ResourceType.ASSET_GROUP, testId = "T1")

        // --- Step 4: ListingGroupFilter (UNIT_INCLUDED, 스마트 선택) ---
        val filterResourceName = createListingGroupFilter(assetGroupResourceName)
        trackResource(filterResourceName, ResourceType.ASSET_GROUP_LISTING_GROUP_FILTER, testId = "T1")

        // --- Assertions ---
        assertThat(budgetResourceName).matches("""customers/$customerId/campaignBudgets/\d+""")
        assertThat(campaignResourceName).matches("""customers/$customerId/campaigns/\d+""")
        assertThat(assetGroupResourceName).matches("""customers/$customerId/assetGroups/\d+""")
        assertThat(filterResourceName).matches(
            """customers/$customerId/assetGroupListingGroupFilters/\d+~\d+""",
        )
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /**
     * MC 링크의 merchant_center_id 조회.
     * T0-3 에서 검증된 product_link 가 존재한다는 가정 하에 첫 번째 링크 사용.
     */
    private fun fetchLinkedMerchantId(): Long {
        val query = """
            SELECT product_link.merchant_center.merchant_center_id
            FROM product_link
            WHERE product_link.type = 'MERCHANT_CENTER'
            LIMIT 1
        """.trimIndent()

        val request = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(customerId)
            .setQuery(query)
            .build()

        val merchantId = googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { client ->
            client.searchStreamCallable().call(request)
                .flatMap { it.resultsList }
                .map { it.productLink.merchantCenter.merchantCenterId }
                .firstOrNull()
        }

        return requireNotNull(merchantId) {
            "No MERCHANT_CENTER product_link found — did T0-3 pass?"
        }
    }

    private fun createBudget(runSuffix: String): String {
        val budget: CampaignBudget = CampaignBudget.newBuilder()
            .setName("test-budget-$runSuffix")
            .setAmountMicros(10_000_000_000L) // ₩10,000
            .setDeliveryMethod(BudgetDeliveryMethod.STANDARD)
            // 공유 예산 X — 단일 캠페인 전용
            // 누락 시 CAMPAIGN_CANNOT_USE_SHARED_BUDGET 에러 발생 가능
            .setExplicitlyShared(false)
            .build()

        val op = CampaignBudgetOperation.newBuilder().apply { create = budget }.build()

        return googleAdsClient.latestVersion.createCampaignBudgetServiceClient().use {
            it.mutateCampaignBudgets(customerId, listOf(op))
                .resultsList
                .first()
                .resourceName
        }
    }

    private fun createCampaign(
        runSuffix: String,
        budgetResourceName: String,
        merchantId: Long,
    ): String {
        val today = LocalDate.now().atStartOfDay()

        val shopping = Campaign.ShoppingSetting.newBuilder()
            .setMerchantId(merchantId)
            .setFeedLabel("KR")
            .build()

        // end_date_time 은 반드시 23:59:59 로 끝나야 한다 (DATE_RANGE_ERROR_END_TIME_MUST_BE_THE_END_OF_A_DAY)
        val startAt = today.plusDays(1)
        val endAt = today.plusDays(7).plusHours(23).plusMinutes(59).plusSeconds(59)

        val campaign: Campaign = Campaign.newBuilder()
            .setName("test-pmax-$runSuffix")
            .setAdvertisingChannelType(AdvertisingChannelType.PERFORMANCE_MAX)
            .setStatus(CampaignStatus.PAUSED)
            .setCampaignBudget(budgetResourceName)
            // v23: start_date → start_date_time, end_date → end_date_time
            .setStartDateTime(startAt.format(dateTimeFormat))
            .setEndDateTime(endAt.format(dateTimeFormat))
            .setShoppingSetting(shopping)
            .setMaximizeConversionValue(MaximizeConversionValue.newBuilder().build())
            // v23 신규 필수 필드 — EU 정치 광고 여부
            .setContainsEuPoliticalAdvertising(
                EuPoliticalAdvertisingStatus.DOES_NOT_CONTAIN_EU_POLITICAL_ADVERTISING,
            )
            .build()

        val op = CampaignOperation.newBuilder().apply { create = campaign }.build()

        return googleAdsClient.latestVersion.createCampaignServiceClient().use {
            it.mutateCampaigns(customerId, listOf(op))
                .resultsList
                .first()
                .resourceName
        }
    }

    private fun createAssetGroup(runSuffix: String, campaignResourceName: String): String {
        val assetGroup: AssetGroup = AssetGroup.newBuilder()
            .setName("test-asset-group-$runSuffix")
            .setCampaign(campaignResourceName)
            .addFinalUrls("https://www.musinsa.com/")
            .setStatus(AssetGroupStatus.PAUSED)
            .build()

        val op = AssetGroupOperation.newBuilder().apply { create = assetGroup }.build()

        return googleAdsClient.latestVersion.createAssetGroupServiceClient().use {
            it.mutateAssetGroups(customerId, listOf(op))
                .resultsList
                .first()
                .resourceName
        }
    }

    private fun createListingGroupFilter(assetGroupResourceName: String): String {
        // 스마트 선택: UNIT_INCLUDED (단일 노드, Feed 전체 포함)
        val filter: AssetGroupListingGroupFilter = AssetGroupListingGroupFilter.newBuilder()
            .setAssetGroup(assetGroupResourceName)
            .setType(ListingGroupFilterType.UNIT_INCLUDED)
            // v23 필수: 어느 피드 소스를 쓸지 명시 (Shopping = Merchant Center Feed)
            .setListingSource(ListingGroupFilterListingSource.SHOPPING)
            .build()

        val op = AssetGroupListingGroupFilterOperation.newBuilder().apply { create = filter }.build()

        return googleAdsClient.latestVersion.createAssetGroupListingGroupFilterServiceClient().use {
            it.mutateAssetGroupListingGroupFilters(customerId, listOf(op))
                .resultsList
                .first()
                .resourceName
        }
    }
}
