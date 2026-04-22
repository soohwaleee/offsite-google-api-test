package com.musinsa.gads.support

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.musinsa.gads.config.GoogleAdsProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 생성된 Google Ads 리소스를 JSON 파일에 기록한다.
 *
 * 원칙:
 *  - **자동 삭제하지 않는다.** 테스트가 끝나도 리소스는 Google Ads에 그대로 남는다.
 *  - 사용자가 UI 에서 확인 후, Gradle cleanup 태스크로 명시적으로 삭제한다.
 *  - 파일 한 개 = 한 번의 Gradle test 실행(JVM)의 runId.
 *
 * 사용:
 *  ```kotlin
 *  val budget = createBudget()
 *  tracker.track(budget.resourceName, ResourceType.CAMPAIGN_BUDGET, testId = "T1")
 *  ```
 */
@Component
class GadsResourceTracker(
    properties: GoogleAdsProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    val runId: String = generateRunId()
    private val customerId: String = properties.customerId
    private val resources: MutableList<TrackedResource> = mutableListOf()

    val file: Path = resourcesDir().resolve("$runId.json").also {
        Files.createDirectories(it.parent)
    }

    init {
        log.info("GadsResourceTracker runId={} file={}", runId, file)
        persist()
    }

    /**
     * 리소스를 기록한다. 파일에 즉시 atomic 하게 반영(실패/크래시에도 기록 보존).
     */
    fun track(resourceName: String, type: ResourceType, testId: String) {
        val entry = TrackedResource(
            resourceName = resourceName,
            type = type,
            testId = testId,
            createdAt = Instant.now().toString(),
        )
        synchronized(resources) {
            resources.add(entry)
            persist()
        }
        log.info("tracked {} type={} testId={}", resourceName, type, testId)
    }

    /** 현재까지 기록된 리소스 snapshot. 읽기 전용. */
    fun snapshot(): List<TrackedResource> = synchronized(resources) { resources.toList() }

    private fun persist() {
        val runFile = RunFile(
            runId = runId,
            customerId = customerId,
            startedAt = Instant.now().toString(),
            resources = resources.toList(),
        )
        val tmp = Files.createTempFile(file.parent, "tracker-", ".tmp")
        try {
            Files.writeString(tmp, mapper.writeValueAsString(runFile))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    companion object {
        private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

        private fun generateRunId(): String {
            val ts = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(TS_FMT)
            val pid = ProcessHandle.current().pid()
            return "$ts-$pid"
        }

        /**
         * repo 루트/_workspace/gads-resources 경로.
         * 테스트 실행 위치와 무관하게 repo 루트 기준 고정.
         */
        fun resourcesDir(): Path {
            // user.dir은 Gradle 실행 시 프로젝트 루트
            val root = Paths.get(System.getProperty("user.dir"))
            return root.resolve("_workspace/gads-resources")
        }

        /** 지정 runId 파일 로드. 존재하지 않으면 null. */
        fun loadRun(runId: String): RunFile? {
            val file = resourcesDir().resolve("$runId.json")
            if (!Files.exists(file)) return null
            val mapper = jacksonObjectMapper()
            return mapper.readValue<RunFile>(Files.readString(file))
        }

        /** 모든 run 파일. 삭제 대상 선별 시 사용. */
        fun listRuns(): List<RunFile> {
            val dir = resourcesDir()
            if (!Files.exists(dir)) return emptyList()
            val mapper = jacksonObjectMapper()
            return Files.list(dir).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".json") }
                    .map { mapper.readValue<RunFile>(Files.readString(it)) }
                    .toList()
            }
        }
    }
}

/**
 * Google Ads 리소스 유형.
 * `deletionOrder` 가 작을수록 먼저 삭제해야 한다 (하위 → 상위).
 *
 * 삭제 순서 (작은 값부터):
 *   1. AssetGroupListingGroupFilter (가장 하위)
 *   2. AssetGroup
 *   3. Campaign
 *   4. CampaignBudget (상위, Campaign 삭제된 후여야 참조 해제)
 */
enum class ResourceType(val deletionOrder: Int) {
    ASSET_GROUP_LISTING_GROUP_FILTER(1),
    ASSET_GROUP(2),
    CAMPAIGN(3),
    CAMPAIGN_BUDGET(4),
}

data class TrackedResource(
    val resourceName: String,
    val type: ResourceType,
    val testId: String,
    val createdAt: String,
)

data class RunFile(
    val runId: String,
    val customerId: String,
    val startedAt: String,
    val resources: List<TrackedResource>,
)
