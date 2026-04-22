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
import com.google.ads.googleads.v23.resources.ListingGroupFilterDimension
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
 * T2: 캠페인 생성 (수동 상품 선택 1개).
 *
 * T1 과 동일한 체인이지만 ListingGroupFilter 만 **트리 구조**:
 *   Root (SUBDIVISION, no case_value)
 *     ├─ UNIT_INCLUDED  (case_value.product_item_id = 선택된 상품)
 *     └─ UNIT_EXCLUDED  (case_value.product_item_id = OTHER — value 비움)
 *
 * 3개 ListingGroupFilter 를 한 번의 mutate 에 묶어 temp resource_name 으로 연결한다.
 *
 * 사전 검증:
 *   - Merchant Center Feed 에 실제 상품이 1개 이상 존재해야 함 (B7 블로커)
 *   - shopping_product 리소스로 첫 아이템 조회 → 실패 시 명시적 assertion
 */
class T2ManualProductCampaignCreateTest : GadsApiTestBase() {

    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Test
    fun `T2 Retail PMax campaign with manual product selection (1 item)`() {
        val runSuffix = UUID.randomUUID().toString().substring(0, 8)

        // 0. Merchant Center 에 상품이 있는지 확인 (B7)
        val itemId = fetchFirstShoppingProductItemId()
        val merchantId = fetchLinkedMerchantId()

        // 1~3 단계는 T1 과 동일
        val budgetResourceName = createBudget(runSuffix).also {
            trackResource(it, ResourceType.CAMPAIGN_BUDGET, testId = "T2")
        }
        val campaignResourceName = createCampaign(runSuffix, budgetResourceName, merchantId).also {
            trackResource(it, ResourceType.CAMPAIGN, testId = "T2")
        }
        val assetGroupResourceName = createAssetGroup(runSuffix, campaignResourceName).also {
            trackResource(it, ResourceType.ASSET_GROUP, testId = "T2")
        }

        // 4. 수동 선택 트리 (3 노드 묶음 mutate)
        val listingFilterResourceNames = createManualProductTree(
            assetGroupResourceName = assetGroupResourceName,
            itemId = itemId,
        )
        listingFilterResourceNames.forEach {
            trackResource(it, ResourceType.ASSET_GROUP_LISTING_GROUP_FILTER, testId = "T2")
        }

        // Assertions
        assertThat(budgetResourceName).matches("""customers/$customerId/campaignBudgets/\d+""")
        assertThat(campaignResourceName).matches("""customers/$customerId/campaigns/\d+""")
        assertThat(assetGroupResourceName).matches("""customers/$customerId/assetGroups/\d+""")
        assertThat(listingFilterResourceNames).hasSize(3)
        listingFilterResourceNames.forEach { resourceName ->
            assertThat(resourceName).matches(
                """customers/$customerId/assetGroupListingGroupFilters/\d+~\d+""",
            )
        }
    }

    // ----------------------------------------------------------------
    // Merchant Center / linking queries
    // ----------------------------------------------------------------

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

    /**
     * Merchant Center Feed 의 첫 번째 상품 item_id.
     * 0건이면 AssertionError — B7 블로커 (MC에 상품이 없음).
     */
    private fun fetchFirstShoppingProductItemId(): String {
        val query = """
            SELECT shopping_product.item_id
            FROM shopping_product
            LIMIT 1
        """.trimIndent()

        val request = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(customerId)
            .setQuery(query)
            .build()

        val itemId = googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { client ->
            client.searchStreamCallable().call(request)
                .flatMap { it.resultsList }
                .map { it.shoppingProduct.itemId }
                .firstOrNull()
        }
        return requireNotNull(itemId) {
            "B7 BLOCKER: Merchant Center Feed has 0 shopping_product rows. " +
                "Upload at least one product to the linked Merchant Center account."
        }
    }

    // ----------------------------------------------------------------
    // Budget / Campaign / AssetGroup (T1 과 동일)
    // ----------------------------------------------------------------

    private fun createBudget(runSuffix: String): String {
        val budget: CampaignBudget = CampaignBudget.newBuilder()
            .setName("test-budget-$runSuffix")
            .setAmountMicros(10_000_000_000L)
            .setDeliveryMethod(BudgetDeliveryMethod.STANDARD)
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
        val startAt = today.plusDays(1)
        val endAt = today.plusDays(7).plusHours(23).plusMinutes(59).plusSeconds(59)

        val shopping = Campaign.ShoppingSetting.newBuilder()
            .setMerchantId(merchantId)
            .setFeedLabel("KR")
            .build()

        val campaign: Campaign = Campaign.newBuilder()
            .setName("test-pmax-manual-$runSuffix")
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

        return googleAdsClient.latestVersion.createCampaignServiceClient().use {
            it.mutateCampaigns(customerId, listOf(op))
                .resultsList
                .first()
                .resourceName
        }
    }

    private fun createAssetGroup(runSuffix: String, campaignResourceName: String): String {
        val assetGroup: AssetGroup = AssetGroup.newBuilder()
            .setName("test-asset-group-manual-$runSuffix")
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

    // ----------------------------------------------------------------
    // 수동 선택 트리 (3 노드 — temp resource_name 연결)
    // ----------------------------------------------------------------

    private fun createManualProductTree(
        assetGroupResourceName: String,
        itemId: String,
    ): List<String> {
        // temp resource name 형식:
        //   customers/{customer_id}/assetGroupListingGroupFilters/{asset_group_id}~{tempId}
        // tempId 는 음수 (같은 mutate 내에서 parent 참조 시 사용).
        val assetGroupId = assetGroupResourceName.substringAfterLast("/")
        val base = "customers/$customerId/assetGroupListingGroupFilters/$assetGroupId"
        val rootTempName = "$base~-1"
        val specificTempName = "$base~-2"
        val otherTempName = "$base~-3"

        // Root: SUBDIVISION, no case_value
        val root = AssetGroupListingGroupFilter.newBuilder()
            .setResourceName(rootTempName)
            .setAssetGroup(assetGroupResourceName)
            .setType(ListingGroupFilterType.SUBDIVISION)
            .setListingSource(ListingGroupFilterListingSource.SHOPPING)
            .build()

        // 포함 노드: UNIT_INCLUDED + case_value(product_item_id=itemId)
        val specific = AssetGroupListingGroupFilter.newBuilder()
            .setResourceName(specificTempName)
            .setAssetGroup(assetGroupResourceName)
            .setType(ListingGroupFilterType.UNIT_INCLUDED)
            .setListingSource(ListingGroupFilterListingSource.SHOPPING)
            .setParentListingGroupFilter(rootTempName)
            .setCaseValue(
                ListingGroupFilterDimension.newBuilder()
                    .setProductItemId(
                        ListingGroupFilterDimension.ProductItemId.newBuilder()
                            .setValue(itemId)
                            .build(),
                    )
                    .build(),
            )
            .build()

        // OTHER 노드: UNIT_EXCLUDED + 빈 product_item_id (= 그 외 모든 상품 제외)
        val other = AssetGroupListingGroupFilter.newBuilder()
            .setResourceName(otherTempName)
            .setAssetGroup(assetGroupResourceName)
            .setType(ListingGroupFilterType.UNIT_EXCLUDED)
            .setListingSource(ListingGroupFilterListingSource.SHOPPING)
            .setParentListingGroupFilter(rootTempName)
            .setCaseValue(
                ListingGroupFilterDimension.newBuilder()
                    .setProductItemId(
                        ListingGroupFilterDimension.ProductItemId.newBuilder().build(),
                    )
                    .build(),
            )
            .build()

        val operations = listOf(root, specific, other).map {
            AssetGroupListingGroupFilterOperation.newBuilder().apply { create = it }.build()
        }

        return googleAdsClient.latestVersion.createAssetGroupListingGroupFilterServiceClient().use {
            it.mutateAssetGroupListingGroupFilters(customerId, operations)
                .resultsList
                .map { result -> result.resourceName }
        }
    }
}
