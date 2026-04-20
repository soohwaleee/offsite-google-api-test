# Google Ads API 테스트 체크리스트

**작성일**: 2026-04-20
**작성자**: 이수화 (Soohwa)
**근거 문서**:
- [PRD v3 (최신)](https://wiki.team.musinsa.com/wiki/spaces/adplatform/pages/367754395/PRD+v3)
- [PRD v3 - UI 내재화 (최신 상세)](https://wiki.team.musinsa.com/wiki/spaces/adplatform/pages/367769419/PRD+v3+-UI)

---

## 0. 사전 정보

### 공식 문서 진입점
- [Google Ads API 전체 시작](https://developers.google.com/google-ads/api/docs/start)
- [v23 RPC Reference](https://developers.google.com/google-ads/api/reference/rpc/v23/overview)
- [Release Notes](https://developers.google.com/google-ads/api/docs/release-notes)
- [Performance Max 가이드](https://developers.google.com/google-ads/api/docs/performance-max/overview)
- [Performance Max Retail 가이드](https://developers.google.com/google-ads/api/docs/performance-max/retail)
- [GAQL (Google Ads Query Language)](https://developers.google.com/google-ads/api/docs/query/overview)
- [필드/리소스 레퍼런스 (v23)](https://developers.google.com/google-ads/api/fields/v23/overview)
- [OAuth 2.0 가이드](https://developers.google.com/google-ads/api/docs/oauth/overview)

| 항목 | 값 |
|---|---|
| API | Google Ads API |
| API 버전 | **v23** (최신 stable, 2026-01-28 릴리스) / v23.2 minor (2026-03-25). PRD의 v18은 outdated → PRD 업데이트 필요 |
| Base URL | `https://googleads.googleapis.com/v23` |
| 프로토콜 | gRPC (proto 기반) / REST 병행 |
| 인증 | OAuth 2.0 Access Token + `developer-token` 헤더 + `login-customer-id` 헤더 (MCC) |
| Access Level | 현재: **Test Access** (테스트 매니저 하위 계정만 호출 가능) |
| 계정 구조 | 무신사 MCC → 단일 Customer Account (브랜드는 Feed Label로 분리) |
| 캠페인 타입 | **Performance Max (PMax) Retail** |
| **Merchant Center 의존성** | **필수** — Retail PMax는 Google Ads 계정에 Merchant Center 링크 필수 (`ShoppingSetting.merchant_id` Required field) |

---

## 1. 캠페인 생성 체인 (Priority 1 — Blocking)

오프사이트 ON 시 **정확한 순서로** 4개 API를 호출해야 생성 완료. 하나라도 실패하면 부분 생성 → 롤백 정책 필요.

### 1-1. 캠페인 예산 생성
📖 [CampaignBudgetService](https://developers.google.com/google-ads/api/reference/rpc/v23/CampaignBudgetService) · [CampaignBudget 리소스](https://developers.google.com/google-ads/api/fields/v23/campaign_budget) · [Budget 가이드](https://developers.google.com/google-ads/api/docs/campaigns/budgets/overview)

| 항목 | 값 |
|---|---|
| Service | `CampaignBudgetService` |
| Method | `MutateCampaignBudgets` (CREATE) |
| 필요 권한 | Test access 로 가능 (테스트 매니저 하위 계정) |
| 입력 | `amount_micros` (일예산 × 10⁶), `delivery_method: STANDARD` |
| 반환 | `campaign_budget_resource_name` |
| 성공 조건 | 응답에 resource_name 존재, amount_micros 라운드트립 일치 |
| 테스트 케이스 | 최소(10,000원), 최대(100,000,000원), 경계값 초과(100,000,001원 → 실패 예상) |

### 1-2. 캠페인 생성
📖 [CampaignService](https://developers.google.com/google-ads/api/reference/rpc/v23/CampaignService) · [Campaign 리소스](https://developers.google.com/google-ads/api/fields/v23/campaign) · [Bidding Strategies 가이드](https://developers.google.com/google-ads/api/docs/campaigns/bidding/bidding-strategies)

| 항목 | 값 |
|---|---|
| Service | `CampaignService` |
| Method | `MutateCampaigns` (CREATE) |
| 입력 | `advertising_channel_type: PERFORMANCE_MAX`, `campaign_budget`, `start_date: YYYYMMDD`, `end_date?`, `bidding_strategy` |
| 입찰전략 변환 | PRD `ROAS 최적화` → `TARGET_ROAS` / `수동 성과형` → `MAXIMIZE_CONVERSION_VALUE` 또는 `ManualCpc` (확인 필요) |
| 반환 | `campaign_resource_name` |
| 성공 조건 | resource_name 존재, status=ENABLED, 예산 resource 연결됨 |
| 테스트 케이스 | ROAS 100%·5000% 경계값, 무기한(end_date 생략), 과거 start_date(실패 예상) |

### 1-3. AssetGroup 생성
📖 [AssetGroupService](https://developers.google.com/google-ads/api/reference/rpc/v23/AssetGroupService) · [AssetGroup 리소스](https://developers.google.com/google-ads/api/fields/v23/asset_group) · [PMax Asset Groups 가이드](https://developers.google.com/google-ads/api/docs/performance-max/asset-groups) · [Asset Requirements](https://developers.google.com/google-ads/api/docs/performance-max/asset-requirements)

| 항목 | 값 |
|---|---|
| Service | `AssetGroupService` |
| Method | `MutateAssetGroups` (CREATE) |
| 입력 | `campaign` (1-2 resource), `name`, `final_urls: [musinsa 랜딩 URL]` |
| 반환 | `asset_group_resource_name` |
| 성공 조건 | resource_name 존재, campaign에 연결됨 |
| ⚠️ Retail 특례 | Retail PMax + Merchant Center 링크 시 **Assets 없이 AssetGroup 생성 가능** (Merchant Center Feed에서 최소 assets 자동 생성). 단, AssetGroupAsset을 **하나라도 붙이는 순간 전체 최소 요구사항이 enforce**되므로 부분 추가 금지 |
| ⚠️ 브랜드 가이드라인 | Campaign의 Brand Guidelines 설정에 따라 BUSINESS_NAME/LOGO/LANDSCAPE_LOGO 공급 경로 달라짐 — **enabled**: `CampaignAsset`으로 / **disabled**: `AssetGroupAsset` 필수. 기본값 확인 필요 |

### 1-4. ListingGroupFilter 설정 (상품 범위)
📖 [AssetGroupListingGroupFilterService](https://developers.google.com/google-ads/api/reference/rpc/v23/AssetGroupListingGroupFilterService) · [AssetGroupListingGroupFilter 리소스](https://developers.google.com/google-ads/api/fields/v23/asset_group_listing_group_filter) · [PMax Listing Group Filters 가이드](https://developers.google.com/google-ads/api/docs/performance-max/listing-group-filters) · [Merchant Center 연동](https://developers.google.com/google-ads/api/docs/shopping-ads/merchant-center)

| 항목 | 값 |
|---|---|
| Service | `AssetGroupListingGroupFilterService` |
| Method | `MutateAssetGroupListingGroupFilters` |
| 수동 선택 | `type: SUBDIVISION`, `case_value: { product_item_id: { value: "{item_id}" } }` — 상품 수만큼 반복 |
| 스마트 선택 | `type: UNIT` (단일 노드, Feed Label 브랜드 전체 자동 포함) |
| Feed Label 의존 | Merchant Center Feed Label이 미리 존재해야 함 (최초 ON 시 자동 생성 요구사항) |
| 성공 조건 | 설정 후 `shopping_performance_view` 쿼리로 필터 상품만 집계되는지 |
| 테스트 케이스 | item 1개 / item 100개 / 존재하지 않는 item_id (실패 예상) |

---

## 2. 캠페인 수정 / 상태 제어 (Priority 2)

### 2-1. 캠페인 필드 수정
| 시나리오 | API | 검증 |
|---|---|---|
| 이름 수정 | `CampaignService.MutateCampaigns` UPDATE `name` | 라운드트립 |
| 종료일 수정 / 무기한 전환 | UPDATE `end_date` | 빈값·과거값·미래값 |
| 상태 변경 (ON/OFF) | UPDATE `status: ENABLED/PAUSED` | 변경 후 조회 일치 |

### 2-2. 예산 수정
| 항목 | 값 |
|---|---|
| API | `CampaignBudgetService.MutateCampaignBudgets` UPDATE `amount_micros` |
| 검증 | 변경 후 조회 일치, 하한/상한 밸리데이션 |

### 2-3. 상품 필터 재생성
| 항목 | 값 |
|---|---|
| API | `AssetGroupListingGroupFilterService` 전체 삭제 → 재생성 (PRD 표현: "item_id 기준 필터 재생성") |
| 검증 | 수정 후 쿼리로 포함 상품 목록 일치 |
| ⚠️ 주의 | 삭제→재생성 사이 atomic 보장 불가 → 리스크 기록 |

---

## 3. 성과 조회 (Priority 3)

📖 [GoogleAdsService (search / searchStream)](https://developers.google.com/google-ads/api/reference/rpc/v23/GoogleAdsService) · [GAQL 문법](https://developers.google.com/google-ads/api/docs/query/overview) · [GAQL 쿼리 빌더](https://developers.google.com/google-ads/api/fields/v23/query_validator) · [Reporting 개요](https://developers.google.com/google-ads/api/docs/reporting/overview)

### 3-1. 캠페인 레벨 (GAQL)
📖 [campaign 리소스 필드](https://developers.google.com/google-ads/api/fields/v23/campaign) · [metrics](https://developers.google.com/google-ads/api/fields/v23/metrics)
```sql
SELECT
  campaign.id,
  campaign.name,
  metrics.impressions,
  metrics.clicks,
  metrics.ctr,
  metrics.cost_micros,
  metrics.conversions_value
FROM campaign
WHERE segments.date BETWEEN '{start}' AND '{end}'
  AND campaign.resource_name = '{resource}'
```
| 항목 | 값 |
|---|---|
| Service | `GoogleAdsService.searchStream` |
| 검증 | 기대 지표 필드 전부 존재, cost_micros÷10⁶로 원화 환산 |
| PRD 매핑 | impressions · clicks · ctr · conversions_value · cost_micros · (ROAS 파생) · CPM |

### 3-2. 상품 레벨 (Shopping Performance View)
📖 [shopping_performance_view 리소스](https://developers.google.com/google-ads/api/fields/v23/shopping_performance_view) · [Shopping Ads 가이드](https://developers.google.com/google-ads/api/docs/shopping-ads/overview)
```sql
SELECT
  segments.product_item_id,
  metrics.clicks,
  metrics.impressions,
  metrics.cost_micros,
  metrics.conversions_value
FROM shopping_performance_view
WHERE segments.date BETWEEN '{start}' AND '{end}'
  AND campaign.resource_name = '{resource}'
```
| 항목 | 값 |
|---|---|
| 검증 | PRD 지표 (clicks · impressions · cost_micros · conversions_value) 전부 반환 |
| 제약 | 전환 데이터는 Google이 집계할 때까지 지연 가능 |

### 3-3. 일별 과금 회수 배치용 쿼리
- PRD 8-6절 GAQL 그대로 (cost_micros 일별 집계)
- 검증: 무신사 biswallet_hold 차감 로직과 라운드 정책 일치(÷10⁶ 정수 변환)

---

## 4. 메타 교차 검증 포인트 (Priority 3)

현아님 요구사항 "메타쪽 이슈(업체ID/브랜드ID)는 구글에서도 동일하게 확인".

| 식별자 | 메타 | 구글 | 교차 검증 |
|---|---|---|---|
| 브랜드 식별 | Product Set `MSS_{brand_id}` | Merchant Center Feed Label `brand_{id}` | 동일 brand_id로 양쪽 조회 시 동일 상품 집합 |
| 상품 식별 | product_id (catalog) | product_item_id (Merchant Center) | 무신사 item_id → 양쪽 동일 값으로 매핑되는지 |
| 캠페인 연결 | ad_campaign → meta_campaign_id | ad_campaign → google_campaign_resource_name | `ad_campaign` 1건 → 양쪽 각 1건 매핑 |

**필수 확인**:
- [ ] Merchant Center Feed Label과 Product Set 이름 규칙이 **동일한 brand_id**로 도출되는지
- [ ] 수동 선택 시 `product_item_id` = 메타 `retailer_id` 동일 값인지 (상품 레벨 성과 조인 전제)

---

## 5. 블로커 / 사전 확인 사항

| # | 이슈 | 상태 | 차단 영향 |
|---|---|---|---|
| B1 | API 버전 확정 | ✅ **해소** — v23 (stable, 2026-01-28) 사용. PRD의 v18은 outdated → PRD 수정 요청 필요 | — |
| B2 | Merchant Center 계정 + Google Ads 링크 | 🟡 **부분 해소** — Merchant Center 계정 보유 확인(2026-04-20). 테스트 매니저 하위 Ads 계정과 **링크 여부는 미확인** | 모든 Retail PMax 캠페인 생성 |
| B3 | AssetGroup Asset 없이 생성 | ✅ **해소** — Retail PMax + Merchant Center 링크 조건으로 **Asset 없이 AssetGroup 생성 가능**. 단, AssetGroupAsset 하나라도 붙이면 전체 요구사항 enforce (부분 추가 금지) | — |
| B4 | Basic Access 신청 시점 — 운영 전까지 | ⏳ 대기 중 | 실 운영 계정 호출 시 |
| B5 | test manager 계정 / Developer Token 세팅 완료 여부 | ✅ 완료 (Slack 스레드) | — |
| **B6** | Brand Guidelines enabled/disabled 기본값 | 🟡 확인 필요 — disabled면 AssetGroupAsset에 Business Name/Logo 필수 추가 (B3와 충돌 가능) | AssetGroup 생성 완주 |
| **B7** | 상품 Feed 세팅 (Merchant Center에 상품 등록) | 🟡 **미확인** — Merchant Center 계정은 있으나 초기 상품 Feed 상태 불명 (2026-04-20). Feed 없이는 Listing Group Filter 실효성 없음 | Listing Group Filter 작동 / T1 이후 실행 |

---

## 6. 테스트 실행 매트릭스 (자동화 대상)

| # | 시나리오 | API 체인 | 예상 결과 | 우선순위 |
|---|---|---|---|---|
| T1 | 캠페인 최소 생성 (스마트 선택) | Budget→Campaign→AssetGroup→ListingGroup(UNIT) | 4단계 모두 성공, resource_name 4개 | P0 |
| T2 | 캠페인 최소 생성 (수동 선택 1개 상품) | + ListingGroup(SUBDIVISION) | P0 + 상품 필터 적용 | P0 |
| T3 | 캠페인 생성 (수동 선택 100개 상품) | T2 확장 | rate limit / batch 제약 확인 | P1 |
| T4 | 이름/종료일 수정 | Campaign UPDATE | 라운드트립 일치 | P1 |
| T5 | 예산 수정 | CampaignBudget UPDATE | 라운드트립 일치 | P1 |
| T6 | ON/OFF 토글 | Campaign status UPDATE | ENABLED↔PAUSED 전환 | P1 |
| T7 | 상품 필터 재생성 | ListingGroupFilter 삭제→재생성 | 재생성 후 포함 상품 일치 | P2 |
| T8 | 캠페인 성과 조회 | GAQL FROM campaign | 지표 6종 모두 반환 | P2 |
| T9 | 상품 성과 조회 | GAQL FROM shopping_performance_view | product_item_id별 지표 반환 | P2 |
| T10 | 일별 cost 회수 쿼리 | GAQL (배치 시뮬) | 정확한 집계액 산출 | P2 |
| T11 | 경계값 실패 테스트 | 예산 초과·잘못된 date 등 | 각각 적절한 에러 코드 | P3 |

---

## 7. 성공 기준 (체크리스트로서)

- [x] **B1 해소**: v23 확정
- [x] **B3 해소**: Retail + Merchant Center 시 Asset 없이 생성 가능
- [ ] **B2/B6/B7 해소**: Merchant Center 계정 확보 + 피드 세팅 + 브랜드 가이드라인 기본값
- [ ] **T1 통과**: 테스트 매니저 하위 계정에서 4단계 체인 완주
- [ ] **T4~T6 통과**: 수정·ON/OFF 라운드트립 통과
- [ ] **T8~T9 통과**: 성과 지표 전부 반환
- [ ] **교차 검증**: 동일 item_id로 구글/메타 상품 레벨 성과 조회 가능
- [ ] **자동화 코드 커버**: 위 T1~T11을 JUnit 통합 테스트로 실행 가능

---

## 8. 리서치 결과 요약 (2026-04-20)

| 질문 | 답변 | 출처 |
|---|---|---|
| 최신 Google Ads API 버전? | **v23** (stable, 2026-01-28) / v23.2 minor (2026-03-25) | [Release Notes](https://developers.google.com/google-ads/api/docs/release-notes) |
| Retail PMax에 Merchant Center 필수? | **필수**. `ShoppingSetting.merchant_id`는 Required field. 우회 경로 없음 | [PMax Retail docs](https://developers.google.com/google-ads/api/docs/performance-max/retail) |
| Merchant Center 링크 위치? | MCC가 아닌 **개별 Google Ads 계정**에 Merchant Center 링크됨 | [Link MC docs](https://support.google.com/google-ads/answer/12499498) |
| AssetGroup을 Asset 없이 생성 가능? | **Retail은 가능** — Merchant Center Feed 기반 자동 생성. 단 AssetGroupAsset 하나라도 붙이면 전체 요구사항 enforce | [AssetGroups docs](https://developers.google.com/google-ads/api/performance-max/asset-groups) |
| Retail PMax에 여전히 필요한 Asset? | Brand Guidelines enabled → CampaignAsset으로 Business Name/Logo 공급 / disabled → AssetGroupAsset으로 Business Name/Logo/Headlines 등 필요 | [Asset Requirements](https://developers.google.com/google-ads/api/performance-max/asset-requirements) |

---

## 9. 다음 단계

1. **B2/B7 해소** — 무신사 Merchant Center 계정 보유/링크 여부 + 초기 상품 Feed 책임자 확인 (마케팅팀 / 영석님)
2. **B6 해소** — Brand Guidelines 기본값을 문서에서 검증 (또는 T1에서 실증)
3. **PRD 버전 업데이트 요청** — v18 → v23 (영석님께 요청)
4. **하네스 설계 (2단계)** — 체크리스트 T1~T11 실행 에이전트/스킬 구성
5. **최소 실행 (T1)** — Budget→Campaign→AssetGroup→ListingGroup 체인 완주 실증
