package com.musinsa.gads.scenario

import com.google.ads.googleads.v23.common.MaximizeConversionValue
import com.google.ads.googleads.v23.enums.AdvertisingChannelTypeEnum.AdvertisingChannelType
import com.google.ads.googleads.v23.enums.AssetFieldTypeEnum.AssetFieldType
import com.google.ads.googleads.v23.enums.AssetTypeEnum.AssetType
import com.google.ads.googleads.v23.enums.BudgetDeliveryMethodEnum.BudgetDeliveryMethod
import com.google.ads.googleads.v23.enums.CampaignStatusEnum.CampaignStatus
import com.google.ads.googleads.v23.enums.EuPoliticalAdvertisingStatusEnum.EuPoliticalAdvertisingStatus
import com.google.ads.googleads.v23.enums.ListingGroupFilterListingSourceEnum.ListingGroupFilterListingSource
import com.google.ads.googleads.v23.enums.ListingGroupFilterTypeEnum.ListingGroupFilterType
import com.google.ads.googleads.v23.errors.GoogleAdsException
import com.google.ads.googleads.v23.common.TextAsset
import com.google.ads.googleads.v23.resources.Asset
import com.google.ads.googleads.v23.resources.AssetGroupListingGroupFilter
import com.google.ads.googleads.v23.resources.AssetGroupAsset
import com.google.ads.googleads.v23.resources.Campaign
import com.google.ads.googleads.v23.resources.CampaignBudget
import com.google.ads.googleads.v23.resources.ListingGroupFilterDimension
import com.google.ads.googleads.v23.services.AssetGroupAssetOperation
import com.google.ads.googleads.v23.services.AssetGroupListingGroupFilterOperation
import com.google.ads.googleads.v23.services.AssetOperation
import com.google.ads.googleads.v23.services.CampaignBudgetOperation
import com.google.ads.googleads.v23.services.CampaignOperation
import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import com.musinsa.gads.support.ResourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * T11: 경계값 실패 케이스 에러 코드 수집.
 *
 * 광고주 UI 에서 발생할 수 있는 **잘못된 입력** 에 대해 Google Ads API 가
 * 어떤 에러 코드로 응답하는지 카탈로그화.
 *
 * 목적:
 *   - 프로덕션에서 에러 → UX 메시지 매핑 테이블 설계 근거
 *   - 각 에러가 create 시점에 막히는지, 아니면 다른 시점에 튀어나오는지 확인
 *
 * 수집 케이스 (6개):
 *   1. 예산 0원
 *   2. 예산 음수
 *   3. 종료일이 시작일보다 이전
 *   4. 종료일이 과거
 *   5. 같은 Asset 을 같은 field_type 으로 AssetGroup 에 중복 링크
 *   6. 존재하지 않는 product_item_id 로 ListingGroupFilter 생성
 *
 * 결과는 `_workspace/diagnosis/error-catalog-{timestamp}.md` 에 저장.
 */
class T11BoundaryFailureTest : GadsApiTestBase() {

    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private data class ErrorCase(
        val name: String,
        val description: String,
        val errorCode: String,
        val message: String,
    )

    @Test
    fun `T11 collect boundary failure error codes`() {
        val cases = mutableListOf<ErrorCase>()

        // 캠페인/자산 존재가 필요한 케이스 용 base 설정
        val fixture = MinimalPmaxFixture(googleAdsClient, customerId).create()
        trackResource(fixture.budgetResourceName, ResourceType.CAMPAIGN_BUDGET, testId = "T11")
        trackResource(fixture.campaignResourceName, ResourceType.CAMPAIGN, testId = "T11")
        trackResource(fixture.assetGroupResourceName, ResourceType.ASSET_GROUP, testId = "T11")
        trackResource(fixture.filterResourceName, ResourceType.ASSET_GROUP_LISTING_GROUP_FILTER, testId = "T11")

        val merchantId = fetchLinkedMerchantId()

        // ── Case 1: 예산 0원 ──
        cases.add(tryCase(
            name = "예산 0원 생성",
            description = "CampaignBudget.amount_micros = 0"
        ) {
            createBudget("zero-budget", 0L)
        })

        // ── Case 2: 예산 음수 ──
        cases.add(tryCase(
            name = "예산 음수",
            description = "CampaignBudget.amount_micros = -1,000,000"
        ) {
            createBudget("negative-budget", -1_000_000L)
        })

        // ── Case 3: 종료일 < 시작일 ──
        cases.add(tryCase(
            name = "종료일이 시작일보다 이전",
            description = "start = +7d, end = +1d (inverted)"
        ) {
            val today = LocalDate.now().atStartOfDay()
            createCampaign(
                budget = fixture.budgetResourceName,
                merchantId = merchantId,
                start = today.plusDays(7),
                end = today.plusDays(1).plusHours(23).plusMinutes(59).plusSeconds(59),
            )
        })

        // ── Case 4: 종료일 과거 ──
        cases.add(tryCase(
            name = "종료일이 과거",
            description = "end_date_time = yesterday"
        ) {
            val today = LocalDate.now().atStartOfDay()
            createCampaign(
                budget = fixture.budgetResourceName,
                merchantId = merchantId,
                start = today.plusDays(1),
                end = today.minusDays(1).plusHours(23).plusMinutes(59).plusSeconds(59),
            )
        })

        // ── Case 5: 같은 Asset 동일 field_type 중복 링크 ──
        cases.add(tryCase(
            name = "같은 Asset 동일 field_type 중복 링크",
            description = "동일 HEADLINE Asset 을 같은 AssetGroup 에 2번 링크"
        ) {
            val assetResourceName = createHeadlineAsset("중복 테스트 헤드라인")
            val op1 = AssetGroupAssetOperation.newBuilder().apply {
                create = AssetGroupAsset.newBuilder()
                    .setAssetGroup(fixture.assetGroupResourceName)
                    .setAsset(assetResourceName)
                    .setFieldType(AssetFieldType.HEADLINE)
                    .build()
            }.build()
            val op2 = AssetGroupAssetOperation.newBuilder().apply {
                create = AssetGroupAsset.newBuilder()
                    .setAssetGroup(fixture.assetGroupResourceName)
                    .setAsset(assetResourceName)
                    .setFieldType(AssetFieldType.HEADLINE)
                    .build()
            }.build()
            googleAdsClient.latestVersion.createAssetGroupAssetServiceClient().use {
                it.mutateAssetGroupAssets(customerId, listOf(op1, op2))
            }
        })

        // ── Case 6: 존재하지 않는 product_item_id ──
        cases.add(tryCase(
            name = "존재하지 않는 product_item_id",
            description = "ListingGroupFilter.case_value 에 fake item_id 지정"
        ) {
            // 기존 UNIT_INCLUDED 루트가 있어서 SUBDIVISION 트리 직접 추가 불가 → 새 AssetGroup 필요
            // 대신 현재 fixture 의 asset group 에 새 필터 추가 시도 (기존과 충돌 예상)
            val assetGroupId = fixture.assetGroupResourceName.substringAfterLast("/")
            val tempName = "customers/$customerId/assetGroupListingGroupFilters/$assetGroupId~-99"

            val filter = AssetGroupListingGroupFilter.newBuilder()
                .setResourceName(tempName)
                .setAssetGroup(fixture.assetGroupResourceName)
                .setType(ListingGroupFilterType.UNIT_INCLUDED)
                .setListingSource(ListingGroupFilterListingSource.SHOPPING)
                .setCaseValue(
                    ListingGroupFilterDimension.newBuilder()
                        .setProductItemId(
                            ListingGroupFilterDimension.ProductItemId.newBuilder()
                                .setValue("THIS_ITEM_DOES_NOT_EXIST_999999999")
                                .build()
                        )
                        .build()
                )
                .build()
            val op = AssetGroupListingGroupFilterOperation.newBuilder().apply {
                create = filter
            }.build()
            googleAdsClient.latestVersion.createAssetGroupListingGroupFilterServiceClient().use {
                it.mutateAssetGroupListingGroupFilters(customerId, listOf(op))
            }
        })

        // ── Report ──
        printCatalog(cases)
        writeCatalogFile(cases)

        // 어떤 케이스든 에러를 실제로 발생시켰는지만 확인 (안 나면 이상함)
        // "(success)" 케이스도 정보 가치가 있으므로 fail 은 아님
        assertThat(cases).hasSize(6)
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private fun tryCase(name: String, description: String, block: () -> Unit): ErrorCase {
        return try {
            block()
            ErrorCase(name, description, "(no error)", "⚠️ 예상과 달리 성공함")
        } catch (e: GoogleAdsException) {
            val errs = e.googleAdsFailure.errorsList
            val codes = errs.joinToString(" + ") { err ->
                err.errorCode.toString()
                    .substringBefore("\n")
                    .trim()
                    .removePrefix("error_code:")
                    .trim()
            }
            val msg = errs.joinToString(" | ") { it.message }
            ErrorCase(name, description, codes, msg)
        } catch (e: Exception) {
            ErrorCase(name, description, "(non-API error)", "${e::class.simpleName}: ${e.message}")
        }
    }

    private fun fetchLinkedMerchantId(): Long {
        val query = """
            SELECT product_link.merchant_center.merchant_center_id
            FROM product_link
            WHERE product_link.type = 'MERCHANT_CENTER'
            LIMIT 1
        """.trimIndent()
        val req = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(customerId)
            .setQuery(query)
            .build()
        return googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { svc ->
            svc.searchStreamCallable().call(req).flatMap { it.resultsList }
                .first().productLink.merchantCenter.merchantCenterId
        }
    }

    private fun createBudget(name: String, amountMicros: Long): String {
        val budget = CampaignBudget.newBuilder()
            .setName("$name-${UUID.randomUUID().toString().take(6)}")
            .setAmountMicros(amountMicros)
            .setDeliveryMethod(BudgetDeliveryMethod.STANDARD)
            .setExplicitlyShared(false)
            .build()
        val op = CampaignBudgetOperation.newBuilder().apply { create = budget }.build()
        return googleAdsClient.latestVersion.createCampaignBudgetServiceClient().use {
            it.mutateCampaignBudgets(customerId, listOf(op)).resultsList.first().resourceName
        }
    }

    private fun createCampaign(
        budget: String,
        merchantId: Long,
        start: LocalDateTime,
        end: LocalDateTime,
    ): String {
        val shopping = Campaign.ShoppingSetting.newBuilder()
            .setMerchantId(merchantId)
            .setFeedLabel("KR")
            .build()

        val campaign = Campaign.newBuilder()
            .setName("test-invalid-${UUID.randomUUID().toString().take(6)}")
            .setAdvertisingChannelType(AdvertisingChannelType.PERFORMANCE_MAX)
            .setStatus(CampaignStatus.PAUSED)
            .setCampaignBudget(budget)
            .setStartDateTime(start.format(dateTimeFormat))
            .setEndDateTime(end.format(dateTimeFormat))
            .setShoppingSetting(shopping)
            .setMaximizeConversionValue(MaximizeConversionValue.newBuilder().build())
            .setContainsEuPoliticalAdvertising(
                EuPoliticalAdvertisingStatus.DOES_NOT_CONTAIN_EU_POLITICAL_ADVERTISING,
            )
            .build()

        val op = CampaignOperation.newBuilder().apply { create = campaign }.build()
        return googleAdsClient.latestVersion.createCampaignServiceClient().use {
            it.mutateCampaigns(customerId, listOf(op)).resultsList.first().resourceName
        }
    }

    private fun createHeadlineAsset(text: String): String {
        val asset = Asset.newBuilder()
            .setType(AssetType.TEXT)
            .setTextAsset(TextAsset.newBuilder().setText(text).build())
            .build()
        val op = AssetOperation.newBuilder().apply { create = asset }.build()
        return googleAdsClient.latestVersion.createAssetServiceClient().use {
            it.mutateAssets(customerId, listOf(op)).resultsList.first().resourceName
        }
    }

    private fun printCatalog(cases: List<ErrorCase>) {
        println()
        println("=".repeat(110))
        println("T11 Boundary Failure Catalog")
        println("=".repeat(110))
        cases.forEachIndexed { idx, c ->
            println()
            println("Case ${idx + 1}: ${c.name}")
            println("  입력:      ${c.description}")
            println("  에러코드:  ${c.errorCode}")
            println("  메시지:    ${c.message.take(200)}")
        }
        println()
        println("=".repeat(110))
    }

    private fun writeCatalogFile(cases: List<ErrorCase>) {
        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(LocalDateTime.now())
        val path = Paths.get("_workspace/diagnosis/error-catalog-$ts.md")
        Files.createDirectories(path.parent)

        val sb = StringBuilder()
        sb.append("# Google Ads API Error Catalog — $ts\n\n")
        sb.append("T11 Boundary Failure Test 결과. 광고주 UI 에 띄울 에러 메시지 매핑 테이블 기반 자료.\n\n")
        sb.append("## 수집 결과\n\n")
        sb.append("| # | 케이스 | 입력 | 에러 코드 | 메시지 |\n")
        sb.append("|---|--------|------|-----------|--------|\n")
        cases.forEachIndexed { idx, c ->
            sb.append("| ${idx + 1} | ${c.name} | ${c.description} | `${c.errorCode}` | ${c.message.take(300).replace("|", "\\|")} |\n")
        }
        sb.append("\n---\n\n")
        sb.append("## 프로덕션 매핑 제안 (초안)\n\n")
        sb.append("각 에러 → 광고주 UI 에 어떻게 표시할지는 PM/광고 사업팀과 협의 필요:\n\n")
        cases.forEach { c ->
            sb.append("- **${c.name}**: \"______\" 같은 사용자 친화 메시지 제안\n")
        }
        Files.writeString(path, sb.toString())
        println("Catalog written to: $path")
    }
}
