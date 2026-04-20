# Google Ads API 테스트 실행 리포트 — 2026-04-20 (사전점검)

**하네스**: test-gads-api
**실행자**: 오케스트레이터 (초기 사전점검 모드)
**환경**: ad-center repo 로컬

---

## 📊 요약

| 지표 | 값 |
|---|---|
| 실행 시나리오 | 0 (사전점검 단계에서 중단) |
| 사전점검 통과 | 0 / 3 |
| 신규 블로커 | 3 |

**T0 시나리오 실행 전 환경 사전점검에서 3건의 블로커가 확인되어 테스트 실행을 중단함.**

---

## 🚧 블로커

### [CONFIG-1] Google Ads Client 의존성 없음
- **증거**: `build.gradle.kts`, `domain/build.gradle.kts`에서 `google-ads` / `googleads` 문자열 grep 결과 0건
- **영향 범위**: T0~T11 전체 (API 호출 자체 불가)
- **다음 액션 (자동화 가능)**:
  - `build-logic` 또는 `domain/build.gradle.kts`에 Google Ads Java Client 추가:
    ```kotlin
    implementation("com.google.api-ads:google-ads:37.0.0")  // v23 호환 버전 확인 필요
    ```

### [CONFIG-2] 환경변수 미세팅
- **증거**: `env | grep GOOGLE_ADS` 결과 없음
- **필요 변수**: `GOOGLE_ADS_CLIENT_ID`, `GOOGLE_ADS_CLIENT_SECRET`, `GOOGLE_ADS_DEVELOPER_TOKEN`, `GOOGLE_ADS_REFRESH_TOKEN`, `GOOGLE_ADS_LOGIN_CUSTOMER_ID`, `GOOGLE_ADS_CUSTOMER_ID`
- **영향 범위**: T0 즉시 AUTH 실패
- **다음 액션 (사용자 조치 필요)**:
  - 지식창고 `google-ads-api-인증발급-런북.md` 참고
  - Slack 스레드에서 언급한 **test manager 하위 계정 customer_id** 확보
  - 로컬 `.env` 또는 shell profile에 export

### [CONFIG-3] IntegrationTest 베이스 클래스 없음
- **증거**: `abstract class IntegrationTest` grep 결과 0건. CLAUDE.md는 언급했지만 실제 파일은 존재하지 않음
- **영향 범위**: 기존 패턴 재사용 불가 → 새로 설계 필요
- **다음 액션 (자동화 가능)**:
  - 옵션 A: 새 모듈 (`ad-test-gads`) 신설, 독립 Gradle 프로젝트
  - 옵션 B: `domain/src/integrationTest/kotlin` sourceSet 추가하고 거기에 구성

---

## 📋 체크리스트 업데이트 제안

- **신규 블로커 추가**:
  - B8: Google Ads SDK 의존성 미추가 (CONFIG-1)
  - B9: 환경변수 미세팅 (CONFIG-2) — 기존 B4(Basic Access)와 별개, B4는 운영 전 / B9는 테스트 시작 전
  - B10: 통합 테스트 베이스 부재 (CONFIG-3)

---

## 🎯 다음 실행 제안 (우선순위)

1. **사용자 결정 필요**: 테스트 코드 위치 — 별도 모듈(`ad-test-gads`) vs 기존 모듈(`domain` 등)의 integrationTest sourceSet
2. **사용자 조치**: 환경변수 세팅 (지식창고 런북 참고)
3. **자동화 가능**: 결정 후 오케스트레이터가:
   - Gradle 의존성 추가
   - 테스트 모듈/소스셋 세팅
   - `GadsApiTestBase` 베이스 클래스 생성 (OAuth 토큰 자동 갱신 포함)
   - T0 시나리오부터 순차 실행

---

## 📎 참고

- 근거 문서: `_workspace/google-ads-api-checklist.md`, `_workspace/google-ads-api-test-plan.md`
- 하네스 진입점: `/test-gads-api` 스킬
- 환경 세팅 후 재실행: `/test-gads-api 재실행`
