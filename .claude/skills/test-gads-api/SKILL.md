---
name: test-gads-api
description: Google Ads API 테스트 자동화 하네스 오케스트레이터. 체크리스트의 T0~T11 시나리오를 설계→실행→진단→리포트 파이프라인으로 자동화한다. Google Ads API 테스트, PMax 캠페인 검증, 테스트 재실행, 테스트 결과 업데이트, 블로커 해소 후 재검증, 오프사이트 연동 검증 작업이 언급되면 반드시 이 스킬을 사용할 것.
---

# test-gads-api (오케스트레이터)

Google Ads API 테스트 자동화 하네스의 진입점. 4인 에이전트 팀을 구성·조율한다.

## 목표

- 체크리스트(`_workspace/google-ads-api-checklist.md`)의 T0~T11 매트릭스를 JUnit 통합 테스트로 실행
- 실패 원인을 4유형(AUTH/PERMISSION/CONFIG/VERSION/BUG)으로 자동 분류
- 마크다운 리포트 생성 + 체크리스트 업데이트 제안

## 팀 구성

| 에이전트 | 역할 | 주요 스킬 |
|---|---|---|
| gads-test-designer | 시나리오 → 테스트 코드 설계 | gads-test-scenario, gads-api-call, gads-gaql-query |
| gads-test-runner | 테스트 실행 / 로그 수집 | gads-api-call, gads-gaql-query |
| gads-blocker-analyzer | 실패 원인 분류 / 다음 액션 | gads-api-call |
| gads-result-reporter | 리포트 생성 | test-result-format |

## Phase 0: 컨텍스트 확인

시작 시 다음을 확인해 실행 모드 결정:

| 조건 | 실행 모드 |
|---|---|
| `_workspace/gads-test-run-*.md` 없음 | **초기 실행** — T0부터 순차 |
| 최근 리포트 존재 + 사용자가 "재실행" 요청 | **새 실행** — 이전 `_workspace/gads-test-logs/`를 `_workspace/gads-test-logs/archive/{timestamp}/`로 이동 |
| 사용자가 "T5만 다시" 등 부분 요청 | **부분 재실행** — 해당 시나리오만 runner에게 |
| 블로커 해소 보고 받음 ("MC 링크 완료") | **블로커 후속 재실행** — 차단됐던 시나리오만 재실행 |

필수 환경변수 세팅 확인:
- `GOOGLE_ADS_CLIENT_ID`, `GOOGLE_ADS_CLIENT_SECRET`
- `GOOGLE_ADS_DEVELOPER_TOKEN`, `GOOGLE_ADS_REFRESH_TOKEN`
- `GOOGLE_ADS_LOGIN_CUSTOMER_ID`, `GOOGLE_ADS_CUSTOMER_ID`

환경변수 누락 시 → T0 이전에 즉시 리포트로 "AUTH blocker" 반환, 팀 구성 생략.

## Phase 1: 팀 생성 및 작업 할당

`TeamCreate` + `TaskCreate` 기반 팀 모드로 운영.

```
Team: gads-test-team
Members:
  - gads-test-designer
  - gads-test-runner
  - gads-blocker-analyzer
  - gads-result-reporter
```

초기 TaskCreate:
1. designer에게 "T0 설계" (blockedBy 없음)
2. runner에게 "T0 실행" (blockedBy: 1)
3. blocker-analyzer에게 "T0 실패 분석" (blockedBy: 2, T0에 FAIL 있을 때만 활성)
4. reporter에게 "T0 리포트" (blockedBy: 2 or 3)

T0 결과에 따라:
- **T0 전부 PASS** → T1~T11 설계·실행·분석·리포트 작업을 추가로 할당
- **T0 일부 FAIL** → 블로커 제거 전 T1~T11 진행 금지, 리포트로 사용자에게 중단 알림

## Phase 2: 파이프라인 (시나리오별 루프)

각 시나리오 Tn마다:
```
designer.design(Tn) → runner.run(Tn) → [if FAIL] analyzer.classify(Tn) → reporter.append(Tn)
```

- runner가 3회 재시도 후에도 flaky → analyzer로 전달
- analyzer가 `BUG` 분류 → designer에게 재설계 요청 (1회 한정, 재설계 후에도 FAIL이면 bug로 확정)

## Phase 3: 통합 리포트

모든 시나리오 완료(또는 블로커로 중단) 후 reporter가 `_workspace/gads-test-run-{timestamp}.md` 단일 파일 생성.

오케스트레이터가 사용자에게:
1. 리포트 파일 경로
2. PASS/FAIL 요약
3. 제안 다음 액션 Top 3

## 데이터 전달 프로토콜

- **태스크 기반**: 작업 상태(pending/in_progress/completed/blocked)
- **파일 기반**: 산출물
  - 설계: `_workspace/gads-test-designs/{test-id}.md` + `src/*.kt`
  - 로그: `_workspace/gads-test-logs/{test-id}/{stdout.log, response.json, result.json}`
  - 블로커: `_workspace/gads-blockers/{run-id}.md`
  - 리포트: `_workspace/gads-test-run-{timestamp}.md`
- **메시지 기반**: 팀원 간 피드백 (재설계 요청, 블로커 공유)

## 에러 핸들링

| 상황 | 대응 |
|---|---|
| 환경변수 누락 | T0 시작 전 중단, "AUTH blocker" 리포트 |
| Gradle 빌드 실패 | designer에게 재컴파일 요청 1회, 실패 시 `BUG` 리포트 |
| API 500/503 연속 3회 | 해당 시나리오 SKIP, 리포트에 "API_UNAVAILABLE" |
| 예상치 못한 에러 | analyzer가 `UNCATEGORIZED`로 분류, 사용자 판단 요청 |

## 테스트 시나리오 (드라이런용)

**정상 흐름**:
1. 사용자: "Google Ads API 테스트 실행해줘"
2. 오케스트레이터: Phase 0 컨텍스트 확인 → 초기 실행 모드
3. Phase 1: 팀 생성, T0 작업 할당
4. T0 모두 PASS → T1~T11 할당
5. 각 시나리오 설계→실행→(실패 시)분석→리포트 append
6. Phase 3: 통합 리포트 출력

**에러 흐름 (Merchant Center 미링크)**:
1. T0-1, T0-2 PASS
2. T0-3 FAIL (`results: []`)
3. blocker-analyzer가 `CONFIG - Merchant Center link missing` 분류
4. 오케스트레이터가 T1~T7, T9 SKIP 처리
5. reporter가 블로커 섹션 + 다음 액션 포함 리포트 생성
6. 사용자에게 "B2 해소 후 `/test-gads-api 재실행`" 안내

## 트리거 검증

### Should-trigger
- "Google Ads API 테스트 실행해줘"
- "PMax 캠페인 생성 테스트 돌려줘"
- "T1부터 T3까지 다시 돌려봐"
- "Merchant Center 연결 완료했어, 재검증해줘"
- "오프사이트 구글 연동 검증 자동화"
- "체크리스트 T0 돌려봐"

### Should-NOT-trigger
- "Meta 광고 API 테스트" → (향후 별도 하네스)
- "Moloco 캠페인 테스트" → (ad-center 내 별도)
- "Google Ads 계정 생성 방법" → 단순 질문, 하네스 불필요
- "캠페인 생성 코드 보여줘" → 단일 코드 요청, gads-test-scenario 스킬만 사용

## 참고

- 근거 문서:
  - `_workspace/google-ads-api-checklist.md`
  - `_workspace/google-ads-api-test-plan.md`
- 에이전트 정의: `.claude/agents/gads-*.md`
- 하위 스킬: `gads-api-call`, `gads-gaql-query`, `gads-test-scenario`, `test-result-format`
