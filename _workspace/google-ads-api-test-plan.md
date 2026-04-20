# Google Ads API 테스트 자동화 계획

**작성일**: 2026-04-20
**작성자**: 이수화 (Soohwa)
**배경**: [Slack 스레드 - Google Ads API 테스트 환경 구축 진행 현황](https://musinsa.slack.com/archives/C0AQWFY3ML7/p1776667435710159)

---

## 목표

현아(PM)님이 요청한 3가지 사항을 충족하기 위한 자동화 체계 구축.

1. **실제 API 호출 테스트**: Playground가 아닌 실제 토큰으로 Google Ads API를 호출해 필요한 기능이 모두 되는지 검증
2. **연동 이슈/제약 파악**: 권한·토큰·API 제약, 구글↔메타 교차 검증 포인트(업체ID·브랜드ID 등) 확인
3. **진행상황 공유 자동화**: 슬랙 + 오프사이트 스크럼용 리포트

---

## 권장 순서

### 1단계: 테스트 대상 API 리스트업 (하네스 없이 먼저)

하네스 설계 전에 **무엇을 검증해야 하는지 명세**가 먼저 있어야 한다.

- 현아님 요구사항 → Google Ads API 엔드포인트 매핑
  - 캠페인 CRUD (`CampaignService`)
  - 예산 (`CampaignBudgetService` - 총예산/일예산)
  - 상품 선택 (`AssetGroup` / `ListingGroup` for PMax, 또는 Shopping)
  - 성과 리포트 (`GoogleAdsService.searchStream`)
  - 메타 교차점: 업체ID/브랜드ID가 구글에서 어떻게 매핑되는지
- **산출물**: `_workspace/google-ads-api-checklist.md`
  - API명
  - 필요 권한 (test / basic / standard)
  - 성공 조건
  - 메타 대응 필드

### 2단계: 하네스 설계 (`/harness` 스킬 사용)

1단계 산출물을 근거로 다음을 정의한다.

- **에이전트**:
  - `google-ads-api-tester` — 구글 광고 API 전담
  - `meta-ads-api-tester` — 메타 광고 API 전담
  - `api-cross-validator` — 구글↔메타 제약 비교
  - `scrum-reporter` — 진행상황 자동 공유

- **각 에이전트가 쓸 스킬**:
  - `gads-api-call` — 인증/호출 공통 스킬
  - `gads-test-runner` — 테스트 케이스 실행/검증
  - `api-diff-report` — 구글↔메타 제약 비교 리포트
  - `slack-progress-share` — 진행상황 자동 공유

### 3단계: 최소 스킬부터 구현 & 검증 루프

한 번에 다 만들지 말고 **가장 위험한 것 1개**부터 시작한다.

- **추천**: `gads-api-call` + 캠페인 생성 1건만 먼저 구현
  - 여기서 권한·토큰 이슈 대부분이 드러남
- 성공 → 나머지 API로 확장

### 4단계: 테스트 자동화 코드

- 언어/프레임워크: **Kotlin + Spring Boot + JUnit** (ad-center 기존 컨벤션)
  - 통합 테스트 형태로 구현 (별도 모듈 or 기존 모듈 내 `src/integrationTest`)
  - ad-center CLAUDE.md의 `IntegrationTest` 베이스 클래스 활용
- 테스트 케이스 구조:
  1. 요청 페이로드 구성 (Faker 또는 Fixture)
  2. API 호출
  3. 응답 스키마·필드 검증
  4. PASS/FAIL 리포트 출력

### 5단계: 결과 문서화 & 공유 자동화

- 테스트 결과 → Confluence 페이지 자동 업데이트
- 주요 이슈 → 슬랙 해당 스레드에 자동 코멘트
- 오프사이트 스크럼용 요약 리포트 자동 생성

---

## 열린 질문

1. **테스트 코드 위치**
   - 별도 모듈 신설 (예: `ad-test-gads`)
   - 기존 모듈(`domain` 또는 `ad-po-api`) 내 integrationTest 소스셋
2. **인증 정보 관리**
   - `application-local.yml` or 환경변수
   - 실수로 commit되지 않도록 `.gitignore` 확인 필수
3. **우선순위**
   - 1단계(API 체크리스트)부터 바로 착수할지 결정

---

## 관련 문서

- ad-center CLAUDE.md (프로젝트 전반 가이드)
- `_workspace/01_architecture.md` — 아키텍처 분석
- `_workspace/02_api_catalog.md` — API 카탈로그
- `_workspace/03_ad_domain.md` — 광고 도메인
- 지식창고: `/Users/soohwa.lee/Documents/지식창고/google-ads-api-인증발급-런북.md`
- 지식창고: `/Users/soohwa.lee/Documents/지식창고/google-ads-api-팀원-가이드.md`
- 지식창고: `/Users/soohwa.lee/Documents/지식창고/meta-pso-api-runbook.md`
