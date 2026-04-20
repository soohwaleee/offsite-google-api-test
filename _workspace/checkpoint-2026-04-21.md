# 체크포인트 — 2026-04-21

**상태**: Merchant Center 링크 대기로 **홀드 중**
**재개 트리거**: 영석님 DM 답변 + MC 링크 승인 완료

---

## 어디까지 했나

### ✅ 완료
1. **하네스 설계·구축** — 4 에이전트 + 4 스킬 + 오케스트레이터 (`test-gads-api`)
2. **체크리스트 작성** — T0~T11 매트릭스 (`_workspace/google-ads-api-checklist.md`)
3. **공식 문서 리서치** — API v23 · Retail PMax · AssetGroup Asset 요구사항
4. **Kotlin + Spring Boot 스캐폴딩** — google-ads-java 42.2.0, JDK 21
5. **T0 사전점검 실행**:
   - T0-1 OAuth + developer-token → ✅ PASS
   - T0-2 test_account == true → ✅ PASS
   - T0-3 Merchant Center product_link → ❌ FAIL (링크 없음)
6. **v23 스키마 보정** — `merchant_center_link`(deprecated) → `product_link` 마이그레이션 반영 (테스트 코드)

### ⏳ 대기 중 (외부 블로커)
- 영석님께 DM 전송: 무신사 Merchant Center(ID 138871704) 링크 요청 확인
- Google Ads UI에서 test manager 하위 계정으로 Link Request 전송 (또는 영석님 가이드 후)
- Merchant Center 측 승인

### ❌ 아직 안 한 것 (링크 승인 후 할 것)
- **`gads-gaql-query` 스킬 템플릿 업데이트** — merchant_center_link 부분을 product_link로 교체
- **`GadsResourceTracker` 인프라** — T1~T7 실행 전 자동 cleanup 유틸 필요
- **T1~T11 테스트 코드** — 시나리오별 JUnit 구현
- **고아 리소스 청소 태스크** — `./gradlew cleanupOrphanedTestResources`

---

## 재개 시 첫 3가지 액션

1. **T0 재실행 확인**:
   ```bash
   set -a; . ./.env; set +a
   ./gradlew test --tests "*T0PrecheckTest"
   ```
   → T0-3까지 PASS 되면 링크 정상 확인

2. **Tracker 인프라 구현** (T1 전 필수):
   - `src/test/kotlin/com/musinsa/gads/support/GadsResourceTracker.kt`
   - `GadsApiTestBase`에 `@AfterEach`로 tracker.cleanupAll() 연결
   - 독립 Gradle task: `cleanupOrphanedTestResources`

3. **T1 설계·실행**:
   - `T1MinimalCampaignCreateTest.kt` — Budget → Campaign → AssetGroup → ListingGroup(UNIT) 4단계
   - 첫 실행에서 B6 (Brand Guidelines 기본값) / 기타 숨은 블로커 드러남

---

## 열린 이슈 메모

| 이슈 | 메모 |
|---|---|
| B6 — Brand Guidelines enabled/disabled 기본값 | T1 실행 시 어떤 Asset 요구사항이 실제로 enforce되는지 자연히 드러날 예정 |
| B7 — MC에 초기 상품 Feed 등록 상태 | 링크 완료 후 Merchant Center 쪽에 상품 피드 존재하는지 별도 확인 필요 |
| `ProductLink.status` 필드 위치 | v23 reference 직접 확인 시 WebFetch가 내용 못 가져옴. 실제 쿼리 성공 후 응답 구조 보고 업데이트 예정 |
| v23 자동 업그레이드 전략 | 현재 v23.0 기준 코드. v23.1/v23.2로 올릴지 여부는 링크 문제 해소 후 결정 |

---

## 참조

- 최신 리포트: `_workspace/gads-test-run-2026-04-20-2327.md`
- 체크리스트: `_workspace/google-ads-api-checklist.md`
- 전체 계획: `_workspace/google-ads-api-test-plan.md`
- 하네스 오케스트레이터: `.claude/skills/test-gads-api/SKILL.md`
- Slack 스레드: https://musinsa.slack.com/archives/C0AQWFY3ML7/p1776667435710159
