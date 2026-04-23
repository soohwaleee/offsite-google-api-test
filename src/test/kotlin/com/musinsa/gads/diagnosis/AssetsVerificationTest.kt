package com.musinsa.gads.diagnosis

import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import org.junit.jupiter.api.Test

/**
 * T12 캠페인(23786986042)에 붙어있는 자산 전수 조회.
 *
 * Campaign 하나에 붙어있는 모든 자산을 2가지 레벨(CampaignAsset, AssetGroupAsset)
 * 에서 조회하고 사람이 읽을 수 있게 출력.
 *
 * 리뷰 문서에 쓴 "붙어있는 광고 자산 10개" 가 실제로 있는지 확인용.
 */
class AssetsVerificationTest : GadsApiTestBase() {

    private val targetCampaignId = 23786986042L
    private val targetCampaignResourceName: String
        get() = "customers/$customerId/campaigns/$targetCampaignId"

    @Test
    fun `list all assets attached to T12 campaign`() {
        println()
        println("╔" + "═".repeat(98) + "╗")
        println("║  Campaign ${targetCampaignId} — 붙어있는 광고 자산 전수 조회" + " ".repeat(42) + "║")
        println("╚" + "═".repeat(98) + "╝")

        // 1. Campaign 기본 정보
        println()
        println("── Campaign 기본 정보 ──")
        val campaignRows = runGaql("""
            SELECT
              campaign.id,
              campaign.name,
              campaign.status,
              campaign.advertising_channel_type,
              campaign.brand_guidelines_enabled,
              campaign_budget.amount_micros
            FROM campaign
            WHERE campaign.resource_name = '$targetCampaignResourceName'
        """)
        campaignRows.firstOrNull()?.let { r ->
            println("  이름:           ${r.campaign.name}")
            println("  상태:           ${r.campaign.status}")
            println("  채널 타입:      ${r.campaign.advertisingChannelType}")
            println("  Brand Guide:    ${r.campaign.brandGuidelinesEnabled}")
            println("  예산:           ${r.campaignBudget.amountMicros / 1_000_000L} KRW/일")
        } ?: println("  (캠페인 없음)")

        // 2. CampaignAsset (Campaign level, Brand Guidelines 필수)
        println()
        println("── CampaignAsset (캠페인 레벨 — BUSINESS_NAME, LOGO) ──")
        val caRows = runGaql("""
            SELECT
              campaign_asset.field_type,
              campaign_asset.status,
              asset.id,
              asset.type,
              asset.name,
              asset.text_asset.text,
              asset.image_asset.full_size.width_pixels,
              asset.image_asset.full_size.height_pixels,
              asset.image_asset.file_size
            FROM campaign_asset
            WHERE campaign_asset.campaign = '$targetCampaignResourceName'
            ORDER BY campaign_asset.field_type
        """)
        if (caRows.isEmpty()) {
            println("  (없음)")
        } else {
            caRows.forEach { r ->
                val a = r.asset
                val detail = when {
                    a.textAsset.text.isNotBlank() -> "text=\"${a.textAsset.text}\""
                    a.imageAsset.fullSize.widthPixels > 0 ->
                        "${a.imageAsset.fullSize.widthPixels}×${a.imageAsset.fullSize.heightPixels}, ${a.imageAsset.fileSize} bytes"
                    else -> ""
                }
                println("  [%-18s] %-6s id=%-14d %s".format(
                    r.campaignAsset.fieldType.name,
                    a.type.name,
                    a.id,
                    detail,
                ))
            }
        }

        // 3. AssetGroupAsset (AssetGroup level)
        println()
        println("── AssetGroupAsset (애셋그룹 레벨 — 나머지 전부) ──")
        val agaRows = runGaql("""
            SELECT
              asset_group.id,
              asset_group.name,
              asset_group_asset.field_type,
              asset_group_asset.status,
              asset.id,
              asset.type,
              asset.text_asset.text,
              asset.image_asset.full_size.width_pixels,
              asset.image_asset.full_size.height_pixels
            FROM asset_group_asset
            WHERE asset_group.campaign = '$targetCampaignResourceName'
            ORDER BY asset_group_asset.field_type, asset.id
        """)
        if (agaRows.isEmpty()) {
            println("  (없음)")
        } else {
            agaRows.forEach { r ->
                val a = r.asset
                val detail = when {
                    a.textAsset.text.isNotBlank() -> "text=\"${a.textAsset.text.take(50)}\""
                    a.imageAsset.fullSize.widthPixels > 0 ->
                        "${a.imageAsset.fullSize.widthPixels}×${a.imageAsset.fullSize.heightPixels}"
                    else -> ""
                }
                println("  [%-24s] %-6s id=%-14d %s".format(
                    r.assetGroupAsset.fieldType.name,
                    a.type.name,
                    a.id,
                    detail,
                ))
            }
        }

        // 4. 합계
        println()
        println("── 합계 ──")
        println("  CampaignAsset:   ${caRows.size} 개")
        println("  AssetGroupAsset: ${agaRows.size} 개")
        println("  전체:            ${caRows.size + agaRows.size} 개")
        println()
    }

    private fun runGaql(query: String) = googleAdsClient.latestVersion
        .createGoogleAdsServiceClient()
        .use { svc ->
            svc.searchStreamCallable().call(
                SearchGoogleAdsStreamRequest.newBuilder()
                    .setCustomerId(customerId)
                    .setQuery(query.trimIndent())
                    .build()
            ).flatMap { it.resultsList }
        }
}
