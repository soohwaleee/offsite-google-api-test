# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## 하네스: Google Ads API 테스트 자동화

**목표:** 무신사 오프사이트 구글 연동 검증용. 체크리스트 T0~T11 테스트 매트릭스를 JUnit 통합 테스트로 자동 실행·진단·리포트한다.

**트리거:** Google Ads API 테스트, PMax 캠페인 검증, 오프사이트 구글 연동 검증, T0~T11 재실행, 블로커 해소 후 재검증 요청 시 `test-gads-api` 스킬을 사용하라. 단순 API 스펙 질문은 직접 응답 가능.

**근거 문서:** `_workspace/google-ads-api-checklist.md`, `_workspace/google-ads-api-test-plan.md`

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-04-20 | 초기 구성 (4인 팀 + 오케스트레이터 + 스킬 4개) | 전체 | ad-center에서 분리하여 신규 repo 구축 |

## Tech Stack

- Kotlin 1.9.25, JDK 21
- Spring Boot 3.3.5 (테스트 DI용, 웹 서버는 비활성)
- google-ads Java client 36.x (API v23 대응 — 릴리스 노트 확인 후 필요 시 버전 업데이트)
- JUnit 5, AssertJ, MockK

## Build and Test

```bash
./gradlew build              # 전체 빌드
./gradlew test               # 전체 테스트
./gradlew test --tests "*T0PrecheckTest"  # T0만
```

## 자격증명

환경변수 기반 (`.env` → `source .env`). `.env.example` 참고.
실제 값은 시크릿 매니저 또는 개인 `.env`에서만. 소스 코드에 하드코딩 금지.

## 테스트 작성 원칙

- **모든 테스트는 `GadsApiTestBase` 상속** — Spring context + 자격증명 assume 처리
- **PAUSED 상태로 리소스 생성** — 실수로 실제 집행되지 않도록
- **tearDown에서 역순 삭제** — Budget → Campaign → AssetGroup → ListingGroup
- **micros 단위 주의** — 금액은 `₩ × 1_000_000`
- **UUID로 name 유니크** — 반복 실행 가능하게

## Google Ads API 버전 관리

- PRD: v23 기준으로 작성
- google-ads-java client는 패키지명에 버전 포함 (예: `com.google.ads.googleads.v17.services.*`) — 릴리스마다 변경
- 클라이언트 버전 업그레이드 시:
  1. [Release Notes](https://developers.google.com/google-ads/api/docs/release-notes) 확인
  2. `build.gradle.kts`의 `com.google.api-ads:google-ads:{version}` 수정
  3. `import com.google.ads.googleads.v{N}.*` 일괄 치환

## 참고

- Google Ads API 공식: https://developers.google.com/google-ads/api
- 지식창고 런북: `~/Documents/지식창고/google-ads-api-*.md`
