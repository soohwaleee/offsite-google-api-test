package com.musinsa.gads.scenario

import com.google.ads.googleads.v23.resources.ListingGroupFilterDimension
import com.google.ads.googleads.v23.enums.ListingGroupFilterListingSourceEnum.ListingGroupFilterListingSource
import com.google.ads.googleads.v23.enums.ListingGroupFilterTypeEnum.ListingGroupFilterType
import com.google.ads.googleads.v23.resources.AssetGroupListingGroupFilter
import com.google.ads.googleads.v23.services.AssetGroupListingGroupFilterOperation
import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import com.musinsa.gads.support.ResourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * T3: 수동으로 선택한 100개 상품으로 ListingGroupFilter 트리 대량 생성.
 *
 * 시나리오:
 *   1. shopping_product 에서 처음 100개 item_id 가져오기
 *   2. 캠페인 + 애셋그룹 생성 (filter 는 직접 만들어야 하므로 createWithoutFilter 사용)
 *   3. 102개 노드 트리를 단일 mutate 로 생성:
 *      - 루트 SUBDIVISION × 1
 *      - UNIT_INCLUDED (특정 상품 100개) × 100
 *      - UNIT_EXCLUDED (OTHER, empty product_item_id) × 1
 *   4. 소요 시간 측정 + rate limit / 크기 제한 체크
 *
 * 목적:
 *   - 단일 mutate 로 100개 수동 선택 가능한지 확인
 *   - PRD v3 "광고주가 특정 상품만 선택해서 광고" 플로우의 실무 상한 탐색
 */
class T3ManualHundredProductsTest : GadsApiTestBase() {

    @Test
    fun `T3 create listing filter tree for 100 selected products`() {
        // --- Step 1: 100 product item_ids ---
        val items = fetch100ProductItemIds()
        println("Fetched ${items.size} product item_ids from Merchant Center")
        assertThat(items).hasSize(100)

        // --- Step 2: campaign + asset group (no filter yet) ---
        val fixture = MinimalPmaxFixture(googleAdsClient, customerId)
        val base = fixture.createWithoutFilter()
        trackResource(base.budgetResourceName, ResourceType.CAMPAIGN_BUDGET, testId = "T3")
        trackResource(base.campaignResourceName, ResourceType.CAMPAIGN, testId = "T3")
        trackResource(base.assetGroupResourceName, ResourceType.ASSET_GROUP, testId = "T3")

        // --- Step 3: 102-node tree in single mutate ---
        val assetGroupId = base.assetGroupResourceName.substringAfterLast("/")
        val pathBase = "customers/$customerId/assetGroupListingGroupFilters/$assetGroupId"
        val rootTemp = "$pathBase~-1"
        val otherTemp = "$pathBase~-${items.size + 2}" // -102

        val ops = buildList {
            // (a) Root: SUBDIVISION
            add(
                AssetGroupListingGroupFilterOperation.newBuilder().apply {
                    create = AssetGroupListingGroupFilter.newBuilder()
                        .setResourceName(rootTemp)
                        .setAssetGroup(base.assetGroupResourceName)
                        .setType(ListingGroupFilterType.SUBDIVISION)
                        .setListingSource(ListingGroupFilterListingSource.SHOPPING)
                        .build()
                }.build()
            )

            // (b) 100 × UNIT_INCLUDED
            items.forEachIndexed { idx, itemId ->
                val tempName = "$pathBase~-${idx + 2}" // -2 .. -101
                add(
                    AssetGroupListingGroupFilterOperation.newBuilder().apply {
                        create = AssetGroupListingGroupFilter.newBuilder()
                            .setResourceName(tempName)
                            .setAssetGroup(base.assetGroupResourceName)
                            .setParentListingGroupFilter(rootTemp)
                            .setType(ListingGroupFilterType.UNIT_INCLUDED)
                            .setListingSource(ListingGroupFilterListingSource.SHOPPING)
                            .setCaseValue(
                                ListingGroupFilterDimension.newBuilder()
                                    .setProductItemId(
                                        ListingGroupFilterDimension.ProductItemId.newBuilder()
                                            .setValue(itemId)
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    }.build()
                )
            }

            // (c) OTHER: UNIT_EXCLUDED with empty product_item_id
            add(
                AssetGroupListingGroupFilterOperation.newBuilder().apply {
                    create = AssetGroupListingGroupFilter.newBuilder()
                        .setResourceName(otherTemp)
                        .setAssetGroup(base.assetGroupResourceName)
                        .setParentListingGroupFilter(rootTemp)
                        .setType(ListingGroupFilterType.UNIT_EXCLUDED)
                        .setListingSource(ListingGroupFilterListingSource.SHOPPING)
                        .setCaseValue(
                            ListingGroupFilterDimension.newBuilder()
                                .setProductItemId(
                                    ListingGroupFilterDimension.ProductItemId.newBuilder()
                                        // value 생략 = empty = OTHER
                                        .build()
                                )
                                .build()
                        )
                        .build()
                }.build()
            )
        }

        println("Total ops: ${ops.size} (root 1 + items ${items.size} + OTHER 1)")

        // --- Step 4: Execute and measure ---
        val start = System.currentTimeMillis()
        val results = googleAdsClient.latestVersion
            .createAssetGroupListingGroupFilterServiceClient().use {
                it.mutateAssetGroupListingGroupFilters(customerId, ops).resultsList
            }
        val elapsed = System.currentTimeMillis() - start

        println()
        println("=".repeat(80))
        println("T3 Result")
        println("=".repeat(80))
        println("Created filters:        ${results.size}")
        println("Mutate elapsed:         ${elapsed} ms")
        println("Avg per operation:      ${"%.1f".format(elapsed.toDouble() / ops.size)} ms")
        println("=".repeat(80))

        // Track all created filters
        results.forEach { r ->
            trackResource(r.resourceName, ResourceType.ASSET_GROUP_LISTING_GROUP_FILTER, testId = "T3")
        }

        // --- Step 5: Verify via GAQL ---
        val verifyQuery = """
            SELECT
              asset_group_listing_group_filter.type,
              asset_group_listing_group_filter.case_value.product_item_id.value
            FROM asset_group_listing_group_filter
            WHERE asset_group_listing_group_filter.asset_group = '${base.assetGroupResourceName}'
        """.trimIndent()

        val rows = googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { svc ->
            svc.searchStreamCallable().call(
                SearchGoogleAdsStreamRequest.newBuilder()
                    .setCustomerId(customerId)
                    .setQuery(verifyQuery)
                    .build()
            ).flatMap { it.resultsList }
        }

        val typeCount = rows.groupingBy { it.assetGroupListingGroupFilter.type }.eachCount()
        println()
        println("Verification via GAQL: ${rows.size} filters")
        typeCount.forEach { (type, count) -> println("  $type: $count") }

        // --- Assertions ---
        assertThat(results).hasSize(102)
        assertThat(rows).hasSize(102)
        assertThat(typeCount[ListingGroupFilterType.SUBDIVISION]).isEqualTo(1)
        assertThat(typeCount[ListingGroupFilterType.UNIT_INCLUDED]).isEqualTo(100)
        assertThat(typeCount[ListingGroupFilterType.UNIT_EXCLUDED]).isEqualTo(1)
    }

    private fun fetch100ProductItemIds(): List<String> {
        val query = """
            SELECT shopping_product.item_id
            FROM shopping_product
            LIMIT 100
        """.trimIndent()

        val request = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(customerId)
            .setQuery(query)
            .build()

        return googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { svc ->
            svc.searchStreamCallable().call(request)
                .flatMap { it.resultsList }
                .map { it.shoppingProduct.itemId }
        }
    }
}
