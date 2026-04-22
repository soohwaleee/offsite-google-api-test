package com.musinsa.gads.scenario

import com.google.ads.googleads.lib.GoogleAdsClient
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * T1 의 캠페인 최소 생성 체인을 추출한 fixture.
 *
 * T4/T5/T6 에서 수정 대상이 될 신선한 캠페인을 만들기 위해 재사용한다.
 * T1 본체는 건드리지 않는다 (회귀 방지).
 */
class MinimalPmaxFixture(
    private val client: GoogleAdsClient,
    private val customerId: String,
) {

    // v23: Campaign.start_date/end_date → start_date_time/end_date_time.
    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    data class Result(
        val runSuffix: String,
        val budgetResourceName: String,
        val campaignResourceName: String,
        val assetGroupResourceName: String,
        val filterResourceName: String,
    )

    fun create(): Result {
        val runSuffix = UUID.randomUUID().toString().substring(0, 8)
        val merchantId = fetchLinkedMerchantId()

        val budget = createBudget(runSuffix)
        val campaign = createCampaign(runSuffix, budget, merchantId)
        val assetGroup = createAssetGroup(runSuffix, campaign)
        val filter = createListingGroupFilter(assetGroup)

        return Result(
            runSuffix = runSuffix,
            budgetResourceName = budget,
            campaignResourceName = campaign,
            assetGroupResourceName = assetGroup,
            filterResourceName = filter,
        )
    }

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

        return client.latestVersion.createGoogleAdsServiceClient().use { svc ->
            svc.searchStreamCallable().call(request)
                .flatMap { it.resultsList }
                .map { it.productLink.merchantCenter.merchantCenterId }
                .firstOrNull()
        } ?: error("No MERCHANT_CENTER product_link found — did T0-3 pass?")
    }

    private fun createBudget(runSuffix: String): String {
        val budget = CampaignBudget.newBuilder()
            .setName("test-budget-$runSuffix")
            .setAmountMicros(10_000_000_000L) // ₩10,000
            .setDeliveryMethod(BudgetDeliveryMethod.STANDARD)
            .setExplicitlyShared(false)
            .build()

        val op = CampaignBudgetOperation.newBuilder().apply { create = budget }.build()

        return client.latestVersion.createCampaignBudgetServiceClient().use {
            it.mutateCampaignBudgets(customerId, listOf(op)).resultsList.first().resourceName
        }
    }

    private fun createCampaign(
        runSuffix: String,
        budgetResourceName: String,
        merchantId: Long,
    ): String {
        val today = LocalDate.now().atStartOfDay()
        val startAt = today.plusDays(1)
        val endAt = today.plusDays(7).plusHours(23).plusMinutes(59).plusSeconds(59)

        val shopping = Campaign.ShoppingSetting.newBuilder()
            .setMerchantId(merchantId)
            .setFeedLabel("KR")
            .build()

        val campaign = Campaign.newBuilder()
            .setName("test-pmax-$runSuffix")
            .setAdvertisingChannelType(AdvertisingChannelType.PERFORMANCE_MAX)
            .setStatus(CampaignStatus.PAUSED)
            .setCampaignBudget(budgetResourceName)
            .setStartDateTime(startAt.format(dateTimeFormat))
            .setEndDateTime(endAt.format(dateTimeFormat))
            .setShoppingSetting(shopping)
            .setMaximizeConversionValue(MaximizeConversionValue.newBuilder().build())
            .setContainsEuPoliticalAdvertising(
                EuPoliticalAdvertisingStatus.DOES_NOT_CONTAIN_EU_POLITICAL_ADVERTISING,
            )
            .build()

        val op = CampaignOperation.newBuilder().apply { create = campaign }.build()

        return client.latestVersion.createCampaignServiceClient().use {
            it.mutateCampaigns(customerId, listOf(op)).resultsList.first().resourceName
        }
    }

    private fun createAssetGroup(runSuffix: String, campaignResourceName: String): String {
        val assetGroup = AssetGroup.newBuilder()
            .setName("test-asset-group-$runSuffix")
            .setCampaign(campaignResourceName)
            .addFinalUrls("https://www.musinsa.com/")
            .setStatus(AssetGroupStatus.PAUSED)
            .build()

        val op = AssetGroupOperation.newBuilder().apply { create = assetGroup }.build()

        return client.latestVersion.createAssetGroupServiceClient().use {
            it.mutateAssetGroups(customerId, listOf(op)).resultsList.first().resourceName
        }
    }

    private fun createListingGroupFilter(assetGroupResourceName: String): String {
        val filter = AssetGroupListingGroupFilter.newBuilder()
            .setAssetGroup(assetGroupResourceName)
            .setType(ListingGroupFilterType.UNIT_INCLUDED)
            .setListingSource(ListingGroupFilterListingSource.SHOPPING)
            .build()

        val op = AssetGroupListingGroupFilterOperation.newBuilder().apply { create = filter }.build()

        return client.latestVersion.createAssetGroupListingGroupFilterServiceClient().use {
            it.mutateAssetGroupListingGroupFilters(customerId, listOf(op))
                .resultsList.first().resourceName
        }
    }
}
