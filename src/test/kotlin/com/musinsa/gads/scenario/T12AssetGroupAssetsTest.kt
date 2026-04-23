package com.musinsa.gads.scenario

import com.google.ads.googleads.v23.common.ImageAsset
import com.google.ads.googleads.v23.common.TextAsset
import com.google.ads.googleads.v23.enums.AssetFieldTypeEnum.AssetFieldType
import com.google.ads.googleads.v23.enums.AssetTypeEnum.AssetType
import com.google.ads.googleads.v23.resources.Asset
import com.google.ads.googleads.v23.resources.AssetGroupAsset
import com.google.ads.googleads.v23.resources.CampaignAsset
import com.google.ads.googleads.v23.services.AssetGroupAssetOperation
import com.google.ads.googleads.v23.services.AssetOperation
import com.google.ads.googleads.v23.services.CampaignAssetOperation
import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.google.protobuf.ByteString
import com.musinsa.gads.GadsApiTestBase
import com.musinsa.gads.support.ResourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * T12: Campaign + AssetGroup 에 Asset 추가 (리뷰 가능한 완성 구조).
 *
 * 시나리오:
 *   1. 새 캠페인 + AssetGroup 생성 (MinimalPmaxFixture 재사용)
 *   2. Text Asset 7개 생성: headlines(3) + long_headline(1) + descriptions(2) + business_name(1)
 *   3. Image Asset 3개 생성: marketing(1.91:1) + square(1:1) + logo(1:1)
 *   4. 연결:
 *      - **CampaignAsset**: BUSINESS_NAME, LOGO  ← v23 Brand Guidelines 기본 활성화로 필수
 *      - **AssetGroupAsset**: HEADLINE, LONG_HEADLINE, DESCRIPTION, MARKETING_IMAGE, SQUARE_MARKETING_IMAGE
 *   5. GAQL 로 양쪽 전수 조회해서 연결 확인
 *
 * v23 Brand Guidelines (2024 말 기본 ON):
 *   - BUSINESS_NAME, LOGO 는 Campaign level 에서 required
 *   - 기존 AssetGroupAsset 에만 링크하면 REQUIRED_BUSINESS_NAME_ASSET_NOT_LINKED /
 *     REQUIRED_LOGO_ASSET_NOT_LINKED 에러 발생
 *
 * 이 상태가 "프로덕션 계정에 이식하면 실제 송출 가능한 구조" — 광고 사업팀 리뷰용.
 */
class T12AssetGroupAssetsTest : GadsApiTestBase() {

    @Test
    fun `T12 attach text and image assets to asset group`() {
        // --- Setup: fresh campaign + asset group ---
        val fixture = MinimalPmaxFixture(googleAdsClient, customerId).create()
        trackResource(fixture.budgetResourceName, ResourceType.CAMPAIGN_BUDGET, testId = "T12")
        trackResource(fixture.campaignResourceName, ResourceType.CAMPAIGN, testId = "T12")
        trackResource(fixture.assetGroupResourceName, ResourceType.ASSET_GROUP, testId = "T12")
        trackResource(fixture.filterResourceName, ResourceType.ASSET_GROUP_LISTING_GROUP_FILTER, testId = "T12")

        // --- Step 1: Create text assets ---
        val textAssetsSpec = listOf(
            "무신사 특가" to AssetFieldType.HEADLINE,
            "지금 무신사에서" to AssetFieldType.HEADLINE,
            "무신사 베스트" to AssetFieldType.HEADLINE,
            "무신사에서 만나보는 특별한 상품들과 혜택" to AssetFieldType.LONG_HEADLINE,
            "다양한 브랜드의 신상품을 만나보세요." to AssetFieldType.DESCRIPTION,
            "빠른 배송과 안전한 구매 경험을 제공합니다." to AssetFieldType.DESCRIPTION,
            "무신사" to AssetFieldType.BUSINESS_NAME,
        )

        val textAssetResourceNames = createTextAssets(textAssetsSpec.map { it.first })

        // --- Step 2: Create image assets ---
        val marketingImgBytes = generatePlaceholderPng(1200, 628, "MUSINSA", Color(255, 107, 107))
        val squareImgBytes = generatePlaceholderPng(1200, 1200, "MUSINSA", Color(76, 175, 80))
        val logoImgBytes = generatePlaceholderPng(512, 512, "M", Color(33, 33, 33))

        val imageAssetSpec = listOf(
            Triple(marketingImgBytes, "test-marketing-${fixture.runSuffix}", AssetFieldType.MARKETING_IMAGE),
            Triple(squareImgBytes, "test-square-${fixture.runSuffix}", AssetFieldType.SQUARE_MARKETING_IMAGE),
            Triple(logoImgBytes, "test-logo-${fixture.runSuffix}", AssetFieldType.LOGO),
        )

        val imageAssetResourceNames = createImageAssets(imageAssetSpec.map { it.first to it.second })

        // --- Step 3a: CampaignAsset 링크 (BUSINESS_NAME + LOGO) ---
        // Brand Guidelines 활성화 PMax 에서 필수 — Campaign level 에 링크해야 함.
        val businessNameIdx = textAssetsSpec.indexOfFirst { it.second == AssetFieldType.BUSINESS_NAME }
        val logoIdx = imageAssetSpec.indexOfFirst { it.third == AssetFieldType.LOGO }

        val campaignLinks = listOf(
            textAssetResourceNames[businessNameIdx] to AssetFieldType.BUSINESS_NAME,
            imageAssetResourceNames[logoIdx] to AssetFieldType.LOGO,
        )

        val campaignLinkOps = campaignLinks.map { (assetRn, fieldType) ->
            val link = CampaignAsset.newBuilder()
                .setCampaign(fixture.campaignResourceName)
                .setAsset(assetRn)
                .setFieldType(fieldType)
                .build()
            CampaignAssetOperation.newBuilder().apply { create = link }.build()
        }

        val campaignLinkResults = googleAdsClient.latestVersion
            .createCampaignAssetServiceClient().use {
                it.mutateCampaignAssets(customerId, campaignLinkOps).resultsList.map { r -> r.resourceName }
            }

        // --- Step 3b: AssetGroupAsset 링크 (나머지 — headlines/descriptions/marketing images) ---
        val assetGroupLinks = textAssetResourceNames.mapIndexedNotNull { idx, rn ->
            val ft = textAssetsSpec[idx].second
            if (ft == AssetFieldType.BUSINESS_NAME) null else rn to ft
        } + imageAssetResourceNames.mapIndexedNotNull { idx, rn ->
            val ft = imageAssetSpec[idx].third
            if (ft == AssetFieldType.LOGO) null else rn to ft
        }

        val assetGroupLinkOps = assetGroupLinks.map { (assetRn, fieldType) ->
            val link = AssetGroupAsset.newBuilder()
                .setAssetGroup(fixture.assetGroupResourceName)
                .setAsset(assetRn)
                .setFieldType(fieldType)
                .build()
            AssetGroupAssetOperation.newBuilder().apply { create = link }.build()
        }

        val assetGroupLinkResults = googleAdsClient.latestVersion
            .createAssetGroupAssetServiceClient().use {
                it.mutateAssetGroupAssets(customerId, assetGroupLinkOps).resultsList.map { r -> r.resourceName }
            }

        // --- Step 4: Verify via GAQL ---
        val assetGroupAssetQuery = """
            SELECT
              asset_group_asset.resource_name,
              asset_group_asset.field_type,
              asset_group_asset.status,
              asset.id,
              asset.type,
              asset.text_asset.text
            FROM asset_group_asset
            WHERE asset_group_asset.asset_group = '${fixture.assetGroupResourceName}'
            ORDER BY asset_group_asset.field_type
        """.trimIndent()

        val campaignAssetQuery = """
            SELECT
              campaign_asset.resource_name,
              campaign_asset.field_type,
              campaign_asset.status,
              asset.id,
              asset.type,
              asset.text_asset.text
            FROM campaign_asset
            WHERE campaign_asset.campaign = '${fixture.campaignResourceName}'
        """.trimIndent()

        val agaRows = runGaql(assetGroupAssetQuery)
        val caRows = runGaql(campaignAssetQuery)

        println("=".repeat(100))
        println("T12 Campaign ${fixture.campaignResourceName.substringAfterLast("/")} — linked assets")
        println("=".repeat(100))
        println("CampaignAsset (BUSINESS_NAME + LOGO, required by Brand Guidelines): ${caRows.size}")
        println("-".repeat(100))
        caRows.forEach { row ->
            val text = row.asset.textAsset.text.ifBlank { "(image)" }
            println("  %-18s %-8s id=%-12d %s".format(
                row.campaignAsset.fieldType.name, row.asset.type.name, row.asset.id, text.take(40)))
        }
        println()
        println("AssetGroupAsset (headlines / descriptions / marketing images): ${agaRows.size}")
        println("-".repeat(100))
        agaRows.forEach { row ->
            val text = row.asset.textAsset.text.ifBlank { "(image)" }
            println("  %-28s %-8s id=%-12d %s".format(
                row.assetGroupAsset.fieldType.name, row.asset.type.name, row.asset.id, text.take(40)))
        }
        println("=".repeat(100))

        // --- Assertions ---
        assertThat(campaignLinkResults).hasSize(2) // BUSINESS_NAME + LOGO
        assertThat(assetGroupLinkResults).hasSize(8) // 3 headlines + 1 long + 2 desc + 1 marketing + 1 square

        val caFieldTypes = caRows.map { it.campaignAsset.fieldType }.toSet()
        assertThat(caFieldTypes).contains(AssetFieldType.BUSINESS_NAME, AssetFieldType.LOGO)

        val agaFieldTypes = agaRows.map { it.assetGroupAsset.fieldType }.toSet()
        assertThat(agaFieldTypes).contains(
            AssetFieldType.HEADLINE,
            AssetFieldType.LONG_HEADLINE,
            AssetFieldType.DESCRIPTION,
            AssetFieldType.MARKETING_IMAGE,
            AssetFieldType.SQUARE_MARKETING_IMAGE,
        )
    }

    private fun runGaql(query: String) = googleAdsClient.latestVersion
        .createGoogleAdsServiceClient()
        .use { svc ->
            svc.searchStreamCallable().call(
                SearchGoogleAdsStreamRequest.newBuilder()
                    .setCustomerId(customerId)
                    .setQuery(query)
                    .build()
            ).flatMap { it.resultsList }
        }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun createTextAssets(texts: List<String>): List<String> {
        val ops = texts.map { text ->
            val asset = Asset.newBuilder()
                .setType(AssetType.TEXT)
                .setTextAsset(TextAsset.newBuilder().setText(text).build())
                .build()
            AssetOperation.newBuilder().apply { create = asset }.build()
        }

        return googleAdsClient.latestVersion.createAssetServiceClient().use {
            it.mutateAssets(customerId, ops).resultsList.map { r ->
                trackResource(r.resourceName, ResourceType.ASSET_GROUP, testId = "T12-asset")
                r.resourceName
            }
        }
    }

    private fun createImageAssets(imagesWithNames: List<Pair<ByteArray, String>>): List<String> {
        val ops = imagesWithNames.map { (bytes, name) ->
            val asset = Asset.newBuilder()
                .setType(AssetType.IMAGE)
                .setName(name)
                .setImageAsset(
                    ImageAsset.newBuilder()
                        .setData(ByteString.copyFrom(bytes))
                        .build()
                )
                .build()
            AssetOperation.newBuilder().apply { create = asset }.build()
        }

        return googleAdsClient.latestVersion.createAssetServiceClient().use {
            it.mutateAssets(customerId, ops).resultsList.map { r ->
                trackResource(r.resourceName, ResourceType.ASSET_GROUP, testId = "T12-asset")
                r.resourceName
            }
        }
    }

    /**
     * 단색 배경 + 중앙 텍스트 PNG 생성.
     * 테스트 계정용 플레이스홀더 — 실제 프로덕션에선 무신사 공식 자산으로 교체.
     */
    private fun generatePlaceholderPng(
        width: Int,
        height: Int,
        text: String,
        bgColor: Color,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = bgColor
        g.fillRect(0, 0, width, height)

        g.color = Color.WHITE
        val fontSize = minOf(width, height) / 6
        g.font = Font("SansSerif", Font.BOLD, fontSize)
        val metrics = g.fontMetrics
        val x = (width - metrics.stringWidth(text)) / 2
        val y = (height - metrics.height) / 2 + metrics.ascent
        g.drawString(text, x, y)
        g.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        return baos.toByteArray()
    }
}
