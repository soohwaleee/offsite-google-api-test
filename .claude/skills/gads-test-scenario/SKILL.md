---
name: gads-test-scenario
description: Google Ads API 테스트 매트릭스 T0~T11의 시나리오 정의, 입력 페이로드, 기대 출력 스키마, assertion 규칙을 표준화한다. Google Ads API 테스트 코드 작성 또는 테스트 케이스 설계 작업에서 반드시 이 스킬을 사용할 것.
---

# gads-test-scenario

Google Ads API 테스트 매트릭스 명세. 설계자(gads-test-designer)가 각 시나리오를 JUnit 테스트로 변환할 때 참조한다.

## 왜 이 스킬이 필요한가

체크리스트(`_workspace/google-ads-api-checklist.md`)에는 시나리오 ID·목표·관련 API만 있고, **실제 테스트 코드로 만들기 위한 입력/기대 출력/assertion 세부 스펙은 없다**. 이 스킬은 각 T번호를 1:1로 매핑한 "실행 가능한 테스트 명세"다.

## 시나리오 카탈로그

### T0: 사전점검 (사용자 확인 + 환경 검증)

T0은 **API 호출 가능 여부** 자체를 검증. 실패 시 T1~T11 모두 차단.

#### T0-1. OAuth / Developer Token 인증
- **호출**: `GET /v23/customers/{customer_id}` (최소 호출)
- **기대**: HTTP 200, `customer.id` 반환
- **assertion**: `id == 환경변수 customer_id의 숫자부분`
- **실패 시 분류**: AUTH (gads-api-call 스킬의 에러 진단표 참조)

#### T0-2. Customer가 Test Account인지
- **호출**: GAQL `SELECT customer.id, customer.test_account, customer.manager FROM customer`
- **기대**: `test_account: true`
- **assertion**: `test_account == true` (현재 Test Access 레벨)
- **실패 시**: CONFIG — 잘못된 계정 사용 중

#### T0-3. Merchant Center 링크 확인 (B2 블로커 해소)
- **호출**: GAQL `SELECT merchant_center_link.* FROM merchant_center_link`
- **기대**: 최소 1개의 `status: ENABLED` 링크
- **assertion**: `results.any { it.status == "ENABLED" }`
- **실패 시**: CONFIG — Merchant Center 링크 필요

#### T0-4. MCC 하위 접근 가능 계정 확인
- **호출**: GAQL `SELECT customer_client.* FROM customer_client WHERE customer_client.level = 1`
- **기대**: 테스트 대상 customer_id가 목록에 포함
- **assertion**: `results.any { it.client_customer.endsWith(customer_id) }`

---

### T1: 캠페인 최소 생성 (스마트 선택)

**가장 중요한 시나리오**. 4단계 체인이 모두 성공해야 PASS.

#### Step 1: CampaignBudget 생성
- **API**: `CampaignBudgetService.mutate` (create)
- **페이로드**:
  ```kotlin
  CampaignBudget {
    name = "test-budget-${uuid}"
    amountMicros = 10_000_000_000L  // ₩10,000 일예산
    deliveryMethod = STANDARD
  }
  ```
- **assertion**: 응답에 `resource_name` 존재, `customers/{id}/campaignBudgets/{digits}` 형식

#### Step 2: Campaign 생성
- **API**: `CampaignService.mutate` (create)
- **페이로드**:
  ```kotlin
  Campaign {
    name = "test-pmax-${uuid}"
    advertisingChannelType = PERFORMANCE_MAX
    campaignBudget = Step1.resourceName
    status = PAUSED  // 생성 후 바로 집행 안 되도록
    startDate = today + 1
    endDate = today + 7
    biddingStrategyType = MAXIMIZE_CONVERSION_VALUE  // 또는 TARGET_ROAS with target
    shoppingSetting = ShoppingSetting {
      merchantId = T0-3에서 확인한 ENABLED link의 merchant_center_id
      feedLabel = "KR"  // 기본값. 브랜드별 Feed Label이 있으면 교체
    }
  }
  ```
- **assertion**: `resource_name` 존재, `advertising_channel_type == PERFORMANCE_MAX`

#### Step 3: AssetGroup 생성 (Asset 없이)
- **API**: `AssetGroupService.mutate` (create)
- **페이로드**:
  ```kotlin
  AssetGroup {
    name = "test-asset-group-${uuid}"
    campaign = Step2.resourceName
    finalUrls = listOf("https://www.musinsa.com/")
    status = PAUSED
  }
  ```
- **검증 포인트**: Retail PMax + Merchant Center 링크 조건에서 Assets 없이 생성 가능 (체크리스트 B3)
- **assertion**: `resource_name` 존재

#### Step 4: ListingGroupFilter (스마트 선택 UNIT)
- **API**: `AssetGroupListingGroupFilterService.mutate` (create)
- **페이로드**:
  ```kotlin
  AssetGroupListingGroupFilter {
    assetGroup = Step3.resourceName
    type = UNIT_INCLUDED  // 단일 노드, Feed 전체 포함
    listingSource = SHOPPING
  }
  ```
- **assertion**: `resource_name` 존재

**T1 전체 assertion**:
- 4개 resource_name 모두 반환
- 각 Step 응답 시간 < 3초 (정상 범위)
- tearDown에서 역순으로 삭제

---

### T2: 캠페인 생성 (수동 선택 1개 상품)

T1과 동일하되 Step 4만 변경:
```kotlin
AssetGroupListingGroupFilter {
  type = SUBDIVISION
  listingDimensionInfo = ListingDimensionInfo {
    productItemId = ProductItemId { value = "{실제_item_id}" }
  }
}
```
- **전제**: Merchant Center에 해당 item_id 상품이 존재해야 함 — T0에서 선행 검증 필요

---

### T3: 캠페인 생성 (수동 선택 100개 상품)

T2 확장. 100개 item_id에 대해 각각 `AssetGroupListingGroupFilter` 생성.
- **추가 검증**: 1 mutate request의 operation 개수 제한 (API quota)
- **assertion**: 100개 모두 생성 성공 OR rate limit 에러 적절히 반환

---

### T4: 이름 / 종료일 수정

- **전제**: T1이 성공한 캠페인 존재
- **API**: `CampaignService.mutate` (update)
- **페이로드**:
  ```kotlin
  Campaign {
    resourceName = {T1.Step2.resourceName}
    name = "test-pmax-renamed-${uuid}"
    endDate = today + 14
  }
  // updateMask: ["name", "end_date"]
  ```
- **assertion**: 이후 GAQL 조회 시 name / end_date 라운드트립 일치

---

### T5: 예산 수정

- **API**: `CampaignBudgetService.mutate` (update)
- **페이로드**: `amountMicros = 20_000_000_000L` (₩20,000)
- **assertion**: GAQL 조회 시 amount_micros 라운드트립

---

### T6: ON/OFF 토글

- **API**: `CampaignService.mutate` (update, status 필드만)
- **시나리오**:
  1. PAUSED → ENABLED
  2. ENABLED → PAUSED
- **assertion**: 각 전환 후 GAQL로 status 조회 일치

---

### T7: 상품 필터 재생성

- **API**:
  1. `AssetGroupListingGroupFilterService.mutate` (remove 기존 필터)
  2. `AssetGroupListingGroupFilterService.mutate` (create 새 필터)
- **리스크**: atomic 아님. 삭제-생성 사이에 캠페인 상태 확인 필요
- **assertion**:
  - 삭제 후 GAQL로 필터 0개 확인
  - 재생성 후 의도된 item_id만 포함

---

### T8: 캠페인 성과 조회

- **전제**: 성과 데이터가 존재하는 캠페인 (실제 집행된 것이 없으면 0으로 채워짐)
- **API**: GAQL search (gads-gaql-query 스킬의 T8 템플릿)
- **assertion**:
  - 응답에 `metrics.impressions`, `clicks`, `ctr`, `cost_micros`, `conversions_value` 6개 필드 모두 존재
  - 숫자 타입 정합성

---

### T9: 상품 레벨 성과

- **API**: GAQL on `shopping_performance_view`
- **assertion**: `segments.product_item_id`별 지표 반환
- **실패 허용**: 집행 데이터 없으면 빈 결과도 허용 (스키마만 검증)

---

### T10: 일별 cost 회수 쿼리 (배치 시뮬레이션)

- **API**: GAQL `SELECT campaign.id, segments.date, metrics.cost_micros FROM campaign WHERE segments.date = '{어제}'`
- **assertion**:
  - 쿼리 자체 성공
  - 비즈월렛 차감 로직 대상 값 산출 (cost_micros ÷ 10⁶ = KRW 정수)

---

### T11: 경계값 실패 테스트

의도적으로 실패하는 입력 → 적절한 에러 코드 반환 검증.

| 서브 케이스 | 입력 | 기대 에러 |
|---|---|---|
| 예산 초과 | amountMicros < 0 | `INVALID_ARGUMENT` |
| 과거 start_date | startDate = yesterday | `INVALID_CAMPAIGN_DATE` |
| 잘못된 resource_name | "customers/xxx/campaigns/abc" | `INVALID_ARGUMENT` 또는 404 |

**assertion**: 각 케이스에서 **정확한 에러 코드** 반환 (generic 500이면 FAIL)

---

## 공통 테스트 원칙

1. **독립성**: 각 테스트는 자체 리소스를 생성·정리. T4는 T1이 남긴 리소스에 의존하지 말고 setUp에서 새로 생성.
2. **Idempotent**: 같은 테스트를 2번 돌려도 같은 결과. uuid로 name 유니크하게.
3. **PAUSED로 시작**: 실수로 집행되지 않도록 모든 캠페인은 PAUSED로 생성.
4. **tearDown 필수**: 생성 순서의 역순으로 delete. 실패해도 다음 삭제 시도.

## 참고

- 체크리스트: `_workspace/google-ads-api-checklist.md`
- API 레퍼런스: gads-api-call 스킬의 엔드포인트 표
- GAQL 템플릿: gads-gaql-query 스킬
