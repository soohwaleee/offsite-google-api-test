package com.musinsa.gads.diagnosis

import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 진단(조사) 테스트 — Google Merchant Center Feed 에 어떤 필드에 어떤 값이 들어있는지 확인.
 *
 * 목적: PRD v3 "브랜드별 캠페인" 실현을 위해 ListingGroupFilter 로 브랜드 분리하려면,
 *      상품 피드 안에 brand_id / company_id 에 해당하는 값이 어느 필드에 있는지 먼저 알아야 함.
 *      메타쪽은 Product Set 의 custom_label 에 어떤 값이 매핑돼 있는지 이미 조사 완료 (2026-04-17 Slack).
 *      같은 맥락을 구글에서도 확인.
 *
 * 실행: `./gradlew test --tests "*ProductFeedInspectionTest"`
 * 결과: 콘솔 로그 + `_workspace/diagnosis/product-feed-{timestamp}.md` 에 기록
 */
class ProductFeedInspectionTest : GadsApiTestBase() {

    @Test
    fun `inspect merchant center product feed fields`() {
        val query = """
            SELECT
              shopping_product.item_id,
              shopping_product.title,
              shopping_product.brand,
              shopping_product.feed_label,
              shopping_product.custom_attribute0,
              shopping_product.custom_attribute1,
              shopping_product.custom_attribute2,
              shopping_product.custom_attribute3,
              shopping_product.custom_attribute4,
              shopping_product.category_level1,
              shopping_product.category_level2,
              shopping_product.product_type_level1,
              shopping_product.product_type_level2
            FROM shopping_product
            LIMIT 10
        """.trimIndent()

        val request = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(customerId)
            .setQuery(query)
            .build()

        val rows = googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { client ->
            client.searchStreamCallable().call(request)
                .flatMap { it.resultsList }
                .map { it.shoppingProduct }
                .toList()
        }

        // 결과 처리
        val samples = rows.map { p ->
            ProductSample(
                itemId = p.itemId,
                title = p.title,
                brand = p.brand,
                feedLabel = p.feedLabel,
                customAttribute0 = p.customAttribute0,
                customAttribute1 = p.customAttribute1,
                customAttribute2 = p.customAttribute2,
                customAttribute3 = p.customAttribute3,
                customAttribute4 = p.customAttribute4,
                categoryLevel1 = p.categoryLevel1,
                categoryLevel2 = p.categoryLevel2,
                productTypeLevel1 = p.productTypeLevel1,
                productTypeLevel2 = p.productTypeLevel2,
            )
        }

        printToConsole(samples)
        writeReport(samples)

        // 최소 1개는 조회돼야 (T2 에서 이미 확인됨)
        assertThat(samples).isNotEmpty

        // 분석 도우미: 각 필드가 사용 중인지(비어있지 않은 값이 있는지) 요약
        val fieldUsage = summarizeFieldUsage(samples)
        println("\n=== Field usage summary ===")
        fieldUsage.forEach { (field, count) -> println("  $field: $count / ${samples.size}") }
    }

    // ----------------------------------------------------------------

    private data class ProductSample(
        val itemId: String,
        val title: String,
        val brand: String,
        val feedLabel: String,
        val customAttribute0: String,
        val customAttribute1: String,
        val customAttribute2: String,
        val customAttribute3: String,
        val customAttribute4: String,
        val categoryLevel1: String,
        val categoryLevel2: String,
        val productTypeLevel1: String,
        val productTypeLevel2: String,
    )

    private fun printToConsole(samples: List<ProductSample>) {
        println("\n=== Merchant Center Feed samples (${samples.size}) ===")
        samples.forEachIndexed { idx, p ->
            println("\n[$idx] item_id=${p.itemId}")
            println("    title              = ${p.title.takeEllipsis()}")
            println("    brand              = ${p.brand}")
            println("    feed_label         = ${p.feedLabel}")
            println("    custom_attribute0  = ${p.customAttribute0}")
            println("    custom_attribute1  = ${p.customAttribute1}")
            println("    custom_attribute2  = ${p.customAttribute2}")
            println("    custom_attribute3  = ${p.customAttribute3}")
            println("    custom_attribute4  = ${p.customAttribute4}")
            println("    category_level1    = ${p.categoryLevel1}")
            println("    category_level2    = ${p.categoryLevel2}")
            println("    product_type_lv1   = ${p.productTypeLevel1}")
            println("    product_type_lv2   = ${p.productTypeLevel2}")
        }
    }

    private fun summarizeFieldUsage(samples: List<ProductSample>): Map<String, Int> {
        fun count(extractor: (ProductSample) -> String) = samples.count { extractor(it).isNotBlank() }
        return mapOf(
            "brand" to count { it.brand },
            "feed_label" to count { it.feedLabel },
            "custom_attribute0" to count { it.customAttribute0 },
            "custom_attribute1" to count { it.customAttribute1 },
            "custom_attribute2" to count { it.customAttribute2 },
            "custom_attribute3" to count { it.customAttribute3 },
            "custom_attribute4" to count { it.customAttribute4 },
            "category_level1" to count { it.categoryLevel1 },
            "category_level2" to count { it.categoryLevel2 },
            "product_type_level1" to count { it.productTypeLevel1 },
            "product_type_level2" to count { it.productTypeLevel2 },
        )
    }

    private fun writeReport(samples: List<ProductSample>) {
        val ts = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
        val root = Path.of(System.getProperty("user.dir"))
        val dir = root.resolve("_workspace/diagnosis")
        Files.createDirectories(dir)
        val file = dir.resolve("product-feed-$ts.md")

        val usage = summarizeFieldUsage(samples)
        val sb = StringBuilder()
        sb.append("# Merchant Center Product Feed 진단 — $ts KST\n\n")
        sb.append("**샘플 수**: ${samples.size}\n")
        sb.append("**customer_id**: $customerId\n\n")

        sb.append("## 필드 사용률 (비어있지 않은 샘플 수)\n\n")
        sb.append("| 필드 | 사용 샘플 |\n|---|---|\n")
        usage.forEach { (field, count) ->
            sb.append("| `$field` | $count / ${samples.size} |\n")
        }
        sb.append("\n")

        sb.append("## 샘플\n\n")
        samples.forEachIndexed { idx, p ->
            sb.append("### [$idx] `${p.itemId}`\n")
            sb.append("- title: ${p.title.takeEllipsis()}\n")
            sb.append("- brand: `${p.brand}`\n")
            sb.append("- feed_label: `${p.feedLabel}`\n")
            sb.append("- custom_attribute 0~4: `${p.customAttribute0}` / `${p.customAttribute1}` / `${p.customAttribute2}` / `${p.customAttribute3}` / `${p.customAttribute4}`\n")
            sb.append("- category_level 1~2: `${p.categoryLevel1}` / `${p.categoryLevel2}`\n")
            sb.append("- product_type_level 1~2: `${p.productTypeLevel1}` / `${p.productTypeLevel2}`\n\n")
        }

        sb.append("## 해석 가이드\n\n")
        sb.append("- `brand` 값이 **무신사 brand_id** 또는 브랜드 영문 slug 이면 → `ListingGroupFilterDimension.ProductBrand` 로 필터 가능\n")
        sb.append("- `custom_attribute*` 에 **brand_id/company_id** 가 들어있으면 → `ProductCustomAttribute` 로 필터\n")
        sb.append("- 둘 다 없으면 → **피드 스펙 업데이트 요청 필요** (영석님/마케팅팀)\n")

        Files.writeString(file, sb.toString())
        println("\nReport written → $file")
    }

    private fun String.takeEllipsis(max: Int = 50): String =
        if (length <= max) this else substring(0, max) + "..."
}
