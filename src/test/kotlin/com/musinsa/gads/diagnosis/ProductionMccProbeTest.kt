package com.musinsa.gads.diagnosis

import com.google.ads.googleads.lib.GoogleAdsClient
import com.google.ads.googleads.v23.errors.GoogleAdsException
import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.google.auth.oauth2.UserCredentials
import com.musinsa.gads.GadsApiTestBase
import org.junit.jupiter.api.Test

/**
 * 추정 프로덕션 MCC `881-807-9939` (8818079939) 에 접근 가능한지 확인.
 *
 * 현재 credential (refresh token) 이 이 MCC 에 접근 권한이 있는지 확인하는 게 핵심.
 * - 접근 가능하면: 하위 계정(프로덕션 광고 계정) 목록 수집
 * - 접근 불가면: 권한 부여 필요
 */
class ProductionMccProbeTest : GadsApiTestBase() {

    @Test
    fun `probe production MCC 8818079939`() {
        val targetMcc = "8818079939"

        // login-customer-id 를 target MCC 로 바꾼 별도 클라이언트 생성
        val probeClient = GoogleAdsClient.newBuilder()
            .setDeveloperToken(properties.developerToken)
            .setCredentials(
                UserCredentials.newBuilder()
                    .setClientId(properties.clientId)
                    .setClientSecret(properties.clientSecret)
                    .setRefreshToken(properties.refreshToken)
                    .build()
            )
            .setLoginCustomerId(targetMcc.toLong())
            .build()

        // 1. 먼저 이 ID 가 MCC 인지 leaf 인지 확인 (자기 자신 정보 조회)
        runCatching {
            val selfQuery = """
                SELECT
                  customer.id,
                  customer.descriptive_name,
                  customer.test_account,
                  customer.manager,
                  customer.currency_code,
                  customer.time_zone,
                  customer.status
                FROM customer
            """.trimIndent()

            val req = SearchGoogleAdsStreamRequest.newBuilder()
                .setCustomerId(targetMcc)
                .setQuery(selfQuery)
                .build()

            val rows = probeClient.latestVersion.createGoogleAdsServiceClient().use { svc ->
                svc.searchStreamCallable().call(req).flatMap { it.resultsList }
            }

            println("=".repeat(80))
            println("Self-check on $targetMcc:")
            println("=".repeat(80))
            rows.firstOrNull()?.customer?.let { c ->
                println("  ID:              ${c.id}")
                println("  Name:            ${c.descriptiveName}")
                println("  test_account:    ${c.testAccount}  ← ${if (c.testAccount) "⚠️ TEST" else "✅ PRODUCTION"}")
                println("  manager(is MCC): ${c.manager}")
                println("  currency_code:   ${c.currencyCode}")
                println("  time_zone:       ${c.timeZone}")
                println("  status:          ${c.status}")
            } ?: println("  (no row returned)")
            println()
        }.onFailure { e ->
            println("❌ Self-check FAILED: ${errorSummary(e)}")
            return
        }

        // 2. MCC 라면 하위 계정 목록 조회
        runCatching {
            val childQuery = """
                SELECT
                  customer_client.id,
                  customer_client.descriptive_name,
                  customer_client.test_account,
                  customer_client.manager,
                  customer_client.status,
                  customer_client.currency_code,
                  customer_client.time_zone,
                  customer_client.level,
                  customer_client.hidden
                FROM customer_client
                ORDER BY customer_client.manager DESC, customer_client.level, customer_client.id
            """.trimIndent()

            val req = SearchGoogleAdsStreamRequest.newBuilder()
                .setCustomerId(targetMcc)
                .setQuery(childQuery)
                .build()

            val rows = probeClient.latestVersion.createGoogleAdsServiceClient().use { svc ->
                svc.searchStreamCallable().call(req).flatMap { it.resultsList }
            }

            println("=".repeat(110))
            println("Child accounts under $targetMcc: total ${rows.size}")
            println("=".repeat(110))
            println("%-3s %-13s %-32s %-6s %-7s %-10s %-5s %-10s %-15s".format(
                "Lvl", "Customer ID", "Name", "Test", "Manager", "Status", "Hidn", "Currency", "Timezone"))
            println("-".repeat(110))
            rows.forEach { row ->
                val c = row.customerClient
                println("%-3d %-13d %-32s %-6s %-7s %-10s %-5s %-10s %-15s".format(
                    c.level,
                    c.id,
                    c.descriptiveName.take(30),
                    if (c.testAccount) "TEST" else "PROD",
                    if (c.manager) "MCC" else "child",
                    c.status.name,
                    if (c.hidden) "hide" else "",
                    c.currencyCode,
                    c.timeZone,
                ))
            }
            println("=".repeat(110))

            val prodChildren = rows.filter {
                !it.customerClient.testAccount && !it.customerClient.manager
            }
            println()
            println("PRODUCTION child accounts: ${prodChildren.size}")
            prodChildren.forEach {
                println("  → ${it.customerClient.id} : ${it.customerClient.descriptiveName}")
            }
        }.onFailure { e ->
            println("❌ Child list FAILED: ${errorSummary(e)}")
        }
    }

    private fun errorSummary(e: Throwable): String {
        val gaEx = (e as? GoogleAdsException)
            ?: e.cause as? GoogleAdsException
        return if (gaEx != null) {
            gaEx.googleAdsFailure.errorsList.joinToString("; ") { err ->
                "${err.errorCode}: ${err.message}"
            }
        } else {
            "${e::class.simpleName}: ${e.message}"
        }
    }
}
