package com.musinsa.gads.diagnosis

import com.google.ads.googleads.v23.services.SearchGoogleAdsStreamRequest
import com.musinsa.gads.GadsApiTestBase
import org.junit.jupiter.api.Test

/**
 * 현재 credential 로 접근 가능한 모든 Google Ads 계정 목록 조회.
 *
 * MCC(login_customer_id) 기준으로 하위 계정들을 나열하여
 * 프로덕션 계정 존재 여부 확인.
 */
class AccessibleAccountsTest : GadsApiTestBase() {

    @Test
    fun `list all accounts accessible from MCC`() {
        // MCC 계정에 쿼리 (login_customer_id 자체를 customerId 로 사용)
        val mccId = properties.loginCustomerId

        // 필터 없이 모든 상태의 계정 조회 (숨김, 취소, 일시정지 포함)
        val query = """
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

        val request = SearchGoogleAdsStreamRequest.newBuilder()
            .setCustomerId(mccId)
            .setQuery(query)
            .build()

        val rows = googleAdsClient.latestVersion.createGoogleAdsServiceClient().use { svc ->
            svc.searchStreamCallable().call(request)
                .flatMap { it.resultsList }
        }

        println("=".repeat(110))
        println("Accessible accounts under MCC $mccId")
        println("=".repeat(110))
        println("Total: ${rows.size} accounts")
        println()
        println("%-3s %-13s %-32s %-6s %-7s %-10s %-5s %-10s %-15s".format(
            "Lvl", "Customer ID", "Name", "Test", "Manager", "Status", "Hidn", "Currency", "Timezone"))
        println("-".repeat(110))

        rows.forEach { row ->
            val c = row.customerClient
            val testFlag = if (c.testAccount) "TEST" else "PROD"
            val managerFlag = if (c.manager) "MCC" else "child"
            val hiddenFlag = if (c.hidden) "hide" else ""
            println("%-3d %-13d %-32s %-6s %-7s %-10s %-5s %-10s %-15s".format(
                c.level,
                c.id,
                c.descriptiveName.take(30),
                testFlag,
                managerFlag,
                c.status.name,
                hiddenFlag,
                c.currencyCode,
                c.timeZone,
            ))
        }
        println("=".repeat(110))

        val productionAccounts = rows.filter {
            !it.customerClient.testAccount && !it.customerClient.manager
        }
        println()
        println("PRODUCTION (non-test, non-manager) accounts: ${productionAccounts.size}")
        productionAccounts.forEach {
            println("  → ${it.customerClient.id} : ${it.customerClient.descriptiveName}")
        }
    }
}
