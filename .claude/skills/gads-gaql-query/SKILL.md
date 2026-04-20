---
name: gads-gaql-query
description: Google Ads Query Language(GAQL)로 리소스 조회와 성과 지표 쿼리를 수행한다. 자주 쓰는 쿼리 템플릿(merchant_center_link, campaign metrics, shopping_performance_view 등)을 제공한다. Google Ads API 조회/검증/사전점검 작업에서 반드시 이 스킬을 사용할 것.
---

# gads-gaql-query

GAQL(Google Ads Query Language) 쿼리 유틸. Google Ads API의 모든 조회 작업은 GAQL로 수행한다.

## 왜 이 스킬이 필요한가

Google Ads API는 리소스별 "Get" 메서드 대신 **GAQL로 통합 조회**한다. SQL과 유사하지만 JOIN이 없고, 필드명에 `.`이 포함된다. 테스트 사전점검(T0)과 성과 검증(T8~T10)에서 집중적으로 쓰인다.

## 기본 구조

```
SELECT
  {resource1.field1},
  {resource2.field2},
  metrics.{metric_name},
  segments.{segment_name}
FROM {resource_name}
WHERE {condition}
ORDER BY {field}
LIMIT {n}
```

- **FROM**은 항상 1개 리소스
- **segments.date** 로 기간 필터
- **metrics.\*** 는 리소스에 조인되어 자동 집계

공식: [GAQL 문법](https://developers.google.com/google-ads/api/docs/query/overview)

## 호출 방법

```
POST https://googleads.googleapis.com/v23/customers/{customer_id}/googleAds:search
Body: { "query": "SELECT ..." }

또는 대용량은:
POST .../googleAds:searchStream
```

## 자주 쓰는 템플릿

### T0-1. Customer 기본 정보 (인증/권한 사전점검)
```sql
SELECT
  customer.id,
  customer.descriptive_name,
  customer.currency_code,
  customer.time_zone,
  customer.test_account,
  customer.manager
FROM customer
```
→ `test_account: true` 확인하면 현재 계정이 테스트 계정인지 검증.

### T0-2. Merchant Center 링크 상태 (B2 블로커 확인)
```sql
SELECT
  merchant_center_link.id,
  merchant_center_link.merchant_center_id,
  merchant_center_link.status
FROM merchant_center_link
```
→ `status: ENABLED`인 링크가 있어야 Retail PMax 생성 가능.

### T0-3. 접근 가능한 하위 고객 목록
```sql
SELECT
  customer_client.client_customer,
  customer_client.descriptive_name,
  customer_client.level,
  customer_client.manager,
  customer_client.test_account
FROM customer_client
WHERE customer_client.level = 1
```
→ MCC 하위에 어떤 계정들이 있는지 확인.

### T8. 캠페인 성과
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
WHERE segments.date BETWEEN '2026-04-01' AND '2026-04-20'
  AND campaign.resource_name = 'customers/{customer_id}/campaigns/{id}'
```

### T9. 상품 레벨 성과 (Shopping Performance View)
```sql
SELECT
  segments.product_item_id,
  metrics.impressions,
  metrics.clicks,
  metrics.cost_micros,
  metrics.conversions_value
FROM shopping_performance_view
WHERE segments.date BETWEEN '2026-04-01' AND '2026-04-20'
  AND campaign.resource_name = 'customers/{customer_id}/campaigns/{id}'
```

### T10. 일별 cost (비즈월렛 회수 배치용)
```sql
SELECT
  campaign.id,
  segments.date,
  metrics.cost_micros
FROM campaign
WHERE segments.date = '2026-04-19'
```

## 필드 레퍼런스

- [campaign](https://developers.google.com/google-ads/api/fields/v23/campaign)
- [metrics](https://developers.google.com/google-ads/api/fields/v23/metrics)
- [shopping_performance_view](https://developers.google.com/google-ads/api/fields/v23/shopping_performance_view)
- [merchant_center_link](https://developers.google.com/google-ads/api/fields/v23/merchant_center_link)
- [customer](https://developers.google.com/google-ads/api/fields/v23/customer)
- [customer_client](https://developers.google.com/google-ads/api/fields/v23/customer_client)
- [전체 리소스 목록 v23](https://developers.google.com/google-ads/api/fields/v23/overview)

## 주의 사항

- **FROM 2개 리소스 금지**: JOIN 없음. 여러 리소스 조회는 여러 쿼리로 분리
- **metrics만 있는 쿼리 금지**: metrics는 항상 다른 리소스와 함께
- **날짜 포맷**: `YYYY-MM-DD` (하이픈)
- **resource_name 전체 경로**: `customers/{customer_id}/campaigns/{campaign_id}` — 부분 ID만 쓰면 안 됨
- **`metrics.cost_micros` ÷ 10⁶ = 원화**

## 에러 진단

| 에러 | 원인 |
|---|---|
| `QUERY_ERROR` | GAQL 문법 오류 — 필드명 오타, 잘못된 FROM |
| `UNRECOGNIZED_FIELD` | 해당 버전(v23)에 없는 필드 — 필드 레퍼런스 재확인 |
| `FIELD_NOT_SELECTABLE` | SELECT 불가 필드 (보통 ID만 선택 가능한 경우) |
| `INVALID_SEGMENT_FOR_METRIC` | metrics와 segments 조합 불가 — 공식 문서의 segments 호환표 확인 |
