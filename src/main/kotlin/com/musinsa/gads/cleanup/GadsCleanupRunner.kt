package com.musinsa.gads.cleanup

import com.google.ads.googleads.lib.GoogleAdsClient
import com.google.ads.googleads.v23.services.AssetGroupListingGroupFilterOperation
import com.google.ads.googleads.v23.services.AssetGroupOperation
import com.google.ads.googleads.v23.services.CampaignBudgetOperation
import com.google.ads.googleads.v23.services.CampaignOperation
import com.musinsa.gads.config.GoogleAdsProperties
import com.musinsa.gads.support.GadsResourceTracker
import com.musinsa.gads.support.ResourceType
import com.musinsa.gads.support.RunFile
import com.musinsa.gads.support.TrackedResource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Google Ads 테스트 리소스를 명시적으로 삭제하는 독립 실행 앱.
 *
 * Gradle 태스크에서 호출:
 *   ./gradlew cleanupRun        -Pmode=run       -PrunId=...
 *   ./gradlew cleanupOrphaned   -Pmode=orphaned  -PolderThanHours=24
 *   ./gradlew cleanupAll        -Pmode=all       -Pconfirm=true
 */
@SpringBootApplication(scanBasePackages = ["com.musinsa.gads"])
@ConfigurationPropertiesScan("com.musinsa.gads")
class GadsCleanupRunnerApp

fun main(args: Array<String>) {
    System.setProperty("spring.main.web-application-type", "none")
    System.setProperty("spring.main.banner-mode", "off")
    runApplication<GadsCleanupRunnerApp>(*args)
}

/**
 * Spring 컨텍스트가 뜨면 자동 실행되는 runner.
 * 시스템 프로퍼티 `cleanup.mode` 로 동작 모드 결정.
 *
 * 테스트 컨텍스트에는 `cleanup.mode` 가 없으므로 빈 자체가 생성되지 않음.
 */
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = ["cleanup.mode"])
class CleanupCommand(
    private val client: GoogleAdsClient,
    private val properties: GoogleAdsProperties,
) : CommandLineRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    @Value("\${cleanup.mode:}")
    private lateinit var mode: String

    @Value("\${cleanup.runId:}")
    private lateinit var runId: String

    @Value("\${cleanup.olderThanHours:24}")
    private var olderThanHours: Long = 24

    @Value("\${cleanup.confirm:false}")
    private var confirm: Boolean = false

    override fun run(vararg args: String?) {
        val targetRuns: List<RunFile> = when (mode) {
            "run" -> resolveRunMode()
            "orphaned" -> resolveOrphanedMode()
            "all" -> resolveAllMode()
            else -> {
                log.error("Unknown cleanup.mode='{}'. Expected: run | orphaned | all", mode)
                exitProcess(2)
            }
        }

        if (targetRuns.isEmpty()) {
            log.info("No runs to clean up. Exiting.")
            return
        }

        val summary = targetRuns.sumOf { it.resources.size }
        log.info("Cleaning up {} runs, {} total resources", targetRuns.size, summary)

        targetRuns.forEach { run -> cleanupRun(run) }
        log.info("Cleanup complete.")
    }

    private fun resolveRunMode(): List<RunFile> {
        require(runId.isNotBlank()) {
            "-PrunId is required for mode=run. e.g., -PrunId=2026-04-22-1530-12345"
        }
        val run = GadsResourceTracker.loadRun(runId)
            ?: error("Run file not found: $runId")
        return listOf(run)
    }

    private fun resolveOrphanedMode(): List<RunFile> {
        val threshold = Instant.now().minus(Duration.ofHours(olderThanHours))
        val all = GadsResourceTracker.listRuns()
        val orphaned = all.filter { Instant.parse(it.startedAt).isBefore(threshold) }
        log.info(
            "orphaned scan: {} runs older than {}h (of {} total)",
            orphaned.size, olderThanHours, all.size,
        )
        return orphaned
    }

    private fun resolveAllMode(): List<RunFile> {
        require(confirm) {
            "cleanup.mode=all requires -Pconfirm=true. This removes ALL tracked resources."
        }
        return GadsResourceTracker.listRuns()
    }

    private fun cleanupRun(run: RunFile) {
        log.info("→ run {} ({} resources)", run.runId, run.resources.size)
        // deletion order: 작은 값부터 (하위 리소스 → 상위 리소스)
        val ordered = run.resources.sortedBy { it.type.deletionOrder }
        ordered.forEach { resource ->
            try {
                removeResource(run.customerId, resource)
            } catch (e: Exception) {
                // best-effort: 이미 콘솔에서 수동 삭제됐거나 의존성 순서 이슈 → 경고로 남기고 계속
                log.warn("failed to remove {} — {}", resource.resourceName, e.message)
            }
        }
        // 파일은 archive 로 이동 (삭제 시도 이력 보존)
        archiveRunFile(run.runId)
    }

    private fun removeResource(customerId: String, r: TrackedResource) {
        when (r.type) {
            ResourceType.ASSET_GROUP_LISTING_GROUP_FILTER -> {
                val op = AssetGroupListingGroupFilterOperation.newBuilder()
                    .setRemove(r.resourceName)
                    .build()
                client.latestVersion.createAssetGroupListingGroupFilterServiceClient().use {
                    it.mutateAssetGroupListingGroupFilters(customerId, listOf(op))
                }
            }
            ResourceType.ASSET_GROUP -> {
                val op = AssetGroupOperation.newBuilder()
                    .setRemove(r.resourceName)
                    .build()
                client.latestVersion.createAssetGroupServiceClient().use {
                    it.mutateAssetGroups(customerId, listOf(op))
                }
            }
            ResourceType.CAMPAIGN -> {
                val op = CampaignOperation.newBuilder()
                    .setRemove(r.resourceName)
                    .build()
                client.latestVersion.createCampaignServiceClient().use {
                    it.mutateCampaigns(customerId, listOf(op))
                }
            }
            ResourceType.CAMPAIGN_BUDGET -> {
                val op = CampaignBudgetOperation.newBuilder()
                    .setRemove(r.resourceName)
                    .build()
                client.latestVersion.createCampaignBudgetServiceClient().use {
                    it.mutateCampaignBudgets(customerId, listOf(op))
                }
            }
        }
        log.info("  removed {} ({})", r.resourceName, r.type)
    }

    private fun archiveRunFile(runId: String) {
        val src = GadsResourceTracker.resourcesDir().resolve("$runId.json")
        val archiveDir = GadsResourceTracker.resourcesDir().resolve("archive")
        Files.createDirectories(archiveDir)
        val dst = archiveDir.resolve("$runId.json")
        Files.move(src, dst)
        log.info("  archived run file → {}", dst)
    }
}
