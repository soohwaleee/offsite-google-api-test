# offsite-google-api-test

무신사 오프사이트 연동 프로젝트의 **Google Ads API 검증용 샌드박스**.
실제 운영 코드(`ad-center`)와 분리되어, API 스펙 검증·권한 점검·성능 측정을 빠르게 실험한다.

> 관련 문서: [_workspace/google-ads-api-test-plan.md](./_workspace/google-ads-api-test-plan.md) · [_workspace/google-ads-api-checklist.md](./_workspace/google-ads-api-checklist.md)

## 빠른 시작

### 1. JDK 21 확인
```bash
java -version  # 21 이상 필요
```

### 2. 환경변수 세팅
```bash
cp .env.example .env
# .env 편집. 값 출처는 지식창고의 google-ads-api-인증발급-런북.md 참고
source .env
```

또는 각 값을 shell profile(`.zshrc` 등)에 `export`.

### 3. 테스트 실행
```bash
# T0 사전점검만
./gradlew test --tests "com.musinsa.gads.scenario.T0PrecheckTest"

# 전체
./gradlew test
```

## 프로젝트 구조

```
.
├── .claude/                    # Claude Code 하네스 (에이전트·스킬)
│   ├── agents/                 # 4인 팀: designer/runner/analyzer/reporter
│   └── skills/                 # gads-api-call, gads-gaql-query, gads-test-scenario,
│                               # test-result-format, test-gads-api (오케스트레이터)
├── _workspace/                 # 계획/체크리스트/런 리포트
├── src/
│   ├── main/kotlin/com/musinsa/gads/
│   │   ├── OffsiteGadsTestApplication.kt
│   │   └── config/             # GoogleAdsProperties, GoogleAdsClientConfig
│   └── test/kotlin/com/musinsa/gads/
│       ├── GadsApiTestBase.kt  # 모든 테스트 베이스 (Spring context + 자격증명 체크)
│       └── scenario/           # T0~T11 시나리오
└── build.gradle.kts
```

## 하네스 사용

이 repo는 Claude Code 하네스(`test-gads-api`)로 테스트를 자동 실행할 수 있다.
Claude Code에서:

```
Google Ads API 테스트 실행해줘
```

오케스트레이터가 설계·실행·진단·리포트를 자동 조율하고 결과를 `_workspace/gads-test-run-{timestamp}.md`에 기록한다.

상세: [.claude/skills/test-gads-api/SKILL.md](./.claude/skills/test-gads-api/SKILL.md)

## 테스트 매트릭스

| # | 시나리오 | 우선순위 |
|---|---|---|
| T0 | 사전점검 (OAuth / Test Account / Merchant Center 링크) | P0 |
| T1 | 캠페인 최소 생성 (스마트 선택) — Budget→Campaign→AssetGroup→ListingGroup | P0 |
| T2 | 캠페인 생성 (수동 선택 1개 상품) | P0 |
| T3 | 캠페인 생성 (수동 선택 100개 상품) | P1 |
| T4 | 이름/종료일 수정 | P1 |
| T5 | 예산 수정 | P1 |
| T6 | ON/OFF 토글 | P1 |
| T7 | 상품 필터 재생성 | P2 |
| T8 | 캠페인 성과 조회 | P2 |
| T9 | 상품 성과 조회 (shopping_performance_view) | P2 |
| T10 | 일별 cost 회수 쿼리 | P2 |
| T11 | 경계값 실패 테스트 | P3 |

상세 스펙: [.claude/skills/gads-test-scenario/SKILL.md](./.claude/skills/gads-test-scenario/SKILL.md)

## 관련 Slack

[오프사이트 구글 API 테스트 환경 진행 스레드](https://musinsa.slack.com/archives/C0AQWFY3ML7/p1776667435710159)
