package com.musinsa.gads.scenario

import com.google.ads.googleads.v23.enums.ListingGroupFilterListingSourceEnum.ListingGroupFilterListingSource
import com.google.ads.googleads.v23.enums.ListingGroupFilterTypeEnum.ListingGroupFilterType
import com.google.ads.googleads.v23.resources.AssetGroupListingGroupFilter
import com.google.ads.googleads.v23.resources.ListingGroupFilterDimension
import com.google.ads.googleads.v23.services.AssetGroupListingGroupFilterOperation
import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import com.musinsa.gads.support.ResourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * T7: ListingGroupFilter 재생성 — 상품 선택 변경 시나리오.
 *
 * 시나리오:
 *   1. 기본 "전체 상품" UNIT_INCLUDED 필터로 캠페인 생성 (MinimalPmaxFixture.create)
 *   2. 광고주가 "특정 3개 상품만" 으로 선택 변경
 *   3. 단일 mutate 로:
 *      - 기존 UNIT_INCLUDED 루트 필터 제거
 *      - 새 SUBDIVISION 트리 생성 (root + 3 specifics + OTHER)
 *   4. GAQL 재조회로 전체 교체 확인
 *
 * 목적:
 *   - PRD v3 "광고주가 이미 만든 캠페인의 상품 선택을 바꾸는" 플로우 검증
 *   - Remove + Create 가 단일 mutate 에서 atomic 하게 동작하는지 확인
 *     (중간 상태 없음 = 광고주가 "변경 중" 에 filter 0개 상태 보지 않음)
 */
class T7ListingFilterRegenerationTest : GadsApiTestBase() {

    @Test
    fun `T7 remove existing filter and create new SUBDIVISION tree atomically`() {
        // --- Setup: 기본 UNIT_INCLUDED 루트 필터가 붙은 캠페인 ---
        val fixture = MinimalPmaxFixture(googleAdsClient, customerId)
        val result = fixture.create()
        trackResource(result.budgetResourceName, ResourceType.CAMPAIGN_BUDGET, testId = "T7")
        trackResource(result.campaignResourceName, ResourceType.CAMPAIGN, testId = "T7")
        trackResource(result.assetGroupResourceName, ResourceType.ASSET_GROUP, testId = "T7")
        trackResource(result.filterResourceName, ResourceType.ASSET_GROUP_LISTING_GROUP_FILTER, testId = "T7")

        // 시작 상태 확인: UNIT_INCLUDED 1개만 있어야 함
        val beforeRows = queryFilters(result.assetGroupResourceName)
        assertThat(beforeRows).hasSize(1)
        assertThat(beforeRows[0].assetGroupListingGroupFilter.type)
            .isEqualTo(ListingGroupFilterType.UNIT_INCLUDED)

        // --- Step 1: 새로 선택할 3개 상품 ID 조회 ---
        val newSelection = fetchProducts(3)
        println("New selection: ${newSelection.joinToString()}")

        // --- Step 2: Remove + Create 를 단일 mutate 로 ---
        val assetGroupId = result.assetGroupResourceName.substringAfterLast("/")
        val pathBase = "customers/$customerId/assetGroupListingGroupFilters/$assetGroupId"
        val rootTemp = "$pathBase~-1"
        val otherTemp = "$pathBase~-${newSelection.size + 2}"

        val ops = buildList {
            // (a) 기존 UNIT_INCLUDED 루트 제거
            add(
                AssetGroupListingGroupFilterOperation.newBuilder()
                    .setRemove(result.filterResourceName)
                    .build()
            )

            // (b) 새 SUBDIVISION 루트
            add(
                AssetGroupListingGroupFilterOperation.newBuilder().apply {
                    create = AssetGroupListingGroupFilter.newBuilder()
                        .setResourceName(rootTemp)
                        .setAssetGroup(result.assetGroupResourceName)
                        .setType(ListingGroupFilterType.SUBDIVISION)
                        .setListingSource(ListingGroupFilterListingSource.SHOPPING)
                        .build()
                }.build()
            )

            // (c) 3 × UNIT_INCLUDED
            newSelection.forEachIndexed { idx, itemId ->
                val tempName = "$pathBase~-${idx + 2}"
                add(
                    AssetGroupListingGroupFilterOperation.newBuilder().apply {
                        create = AssetGroupListingGroupFilter.newBuilder()
                            .setResourceName(tempName)
                            .setAssetGroup(result.assetGroupResourceName)
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

            // (d) OTHER UNIT_EXCLUDED
            add(
                AssetGroupListingGroupFilterOperation.newBuilder().apply {
                    create = AssetGroupListingGroupFilter.newBuilder()
                        .setResourceName(otherTemp)
                        .setAssetGroup(result.assetGroupResourceName)
                        .setParentListingGroupFilter(rootTemp)
                        .setType(ListingGroupFilterType.UNIT_EXCLUDED)
                        .setListingSource(ListingGroupFilterListingSource.SHOPPING)
                        .setCaseValue(
                            ListingGroupFilterDimension.newBuilder()
                                .setProductItemId(
                                    ListingGroupFilterDimension.ProductItemId.newBuilder()
                                        .build()
                                )
                                .build()
                        )
                        .build()
                }.build()
            )
        }

        println("Total ops: ${ops.size} (1 remove + 1 root + ${newSelection.size} specifics + 1 OTHER)")

        val start = System.currentTimeMillis()
        val results = googleAdsClient.latestVersion
            .createAssetGroupListingGroupFilterServiceClient().use {
                it.mutateAssetGroupListingGroupFilters(customerId, ops).resultsList
            }
        val elapsed = System.currentTimeMillis() - start

        println("Mutate elapsed: $elapsed ms, results: ${results.size}")

        results.forEach { r ->
            if (r.resourceName.isNotBlank()) {
                trackResource(r.resourceName, ResourceType.ASSET_GROUP_LISTING_GROUP_FILTER, testId = "T7")
            }
        }

        // --- Step 3: GAQL 로 재조회 ---
        val afterRows = queryFilters(result.assetGroupResourceName)
        val typeCount = afterRows.groupingBy { it.assetGroupListingGroupFilter.type }.eachCount()

        println()
        println("=".repeat(80))
        println("T7 After regeneration — filters: ${afterRows.size}")
        println("=".repeat(80))
        typeCount.forEach { (type, count) -> println("  $type: $count") }
        println("=".repeat(80))

        // --- Assertions ---
        // 기존 UNIT_INCLUDED 루트는 사라지고, SUBDIVISION + 3 UNIT_INCLUDED + 1 UNIT_EXCLUDED 가 남아야 함
        assertThat(afterRows).hasSize(1 + newSelection.size + 1) // 5
        assertThat(typeCount[ListingGroupFilterType.SUBDIVISION]).isEqualTo(1)
        assertThat(typeCount[ListingGroupFilterType.UNIT_INCLUDED]).isEqualTo(newSelection.size)
        assertThat(typeCount[ListingGroupFilterType.UNIT_EXCLUDED]).isEqualTo(1)

        // 기존 filter resource_name 은 더 이상 조회되지 않아야 함
        val afterResourceNames = afterRows.map { it.assetGroupListingGroupFilter.resourceName }.toSet()
        assertThat(afterResourceNames).doesNotContain(result.filterResourceName)
    }

    private fun fetchProducts(limit: Int): List<String> {
        val query = """
            SELECT shopping_product.item_id
            FROM shopping_product
            LIMIT $limit
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

    private fun queryFilters(assetGroupResourceName: String) = googleAdsClient.latestVersion
        .createGoogleAdsServiceClient().use { svc ->
            svc.searchStreamCallable().call(
                SearchGoogleAdsStreamRequest.newBuilder()
                    .setCustomerId(customerId)
                    .setQuery("""
                        SELECT
                          asset_group_listing_group_filter.resource_name,
                          asset_group_listing_group_filter.type,
                          asset_group_listing_group_filter.case_value.product_item_id.value
                        FROM asset_group_listing_group_filter
                        WHERE asset_group_listing_group_filter.asset_group = '$assetGroupResourceName'
                    """.trimIndent())
                    .build()
            ).flatMap { it.resultsList }
        }
}
