---
name: gads-api-call
description: Google Ads API v23 호출에 필요한 OAuth 2.0 access token 갱신, 필수 헤더(developer-token, login-customer-id) 구성, 에러 코드 해석을 표준화한다. Google Ads API를 호출하거나 OAuth 토큰을 다루거나 googleads.googleapis.com 엔드포인트 관련 작업에서 반드시 이 스킬을 사용할 것.
---

# gads-api-call

Google Ads API v23 호출 공통 규약. 설계자와 실행자가 공유한다.

## 왜 이 스킬이 필요한가

Google Ads API는 일반 Google API와 달리 **2단계 게이트**(Developer Token + OAuth)를 통과해야 한다. 한 단계만 빠져도 불명확한 401/500 에러가 난다. 이 스킬은 "호출이 실패하면 우선 이 체크리스트부터"라는 1차 진단 가이드를 제공한다.

## 필수 환경변수

Spring Boot `application-test.yml` 또는 테스트 실행 시 환경변수로 주입:

```yaml
google-ads:
  client-id: ${GOOGLE_ADS_CLIENT_ID}
  client-secret: ${GOOGLE_ADS_CLIENT_SECRET}
  developer-token: ${GOOGLE_ADS_DEVELOPER_TOKEN}
  refresh-token: ${GOOGLE_ADS_REFRESH_TOKEN}
  login-customer-id: ${GOOGLE_ADS_LOGIN_CUSTOMER_ID}  # MCC ID, 숫자만 (하이픈 없음)
  customer-id: ${GOOGLE_ADS_CUSTOMER_ID}              # 테스트 대상 하위 계정
```

## 호출 시 필수 헤더

```
Authorization: Bearer {access_token}
developer-token: {DEVELOPER_TOKEN}
login-customer-id: {MCC_ID_숫자만}
```

`login-customer-id`는 MCC로 로그인하여 하위 계정에 접근할 때 **항상** 필요. 빠뜨리면 `INVALID_LOGIN_CUSTOMER_ID` 에러.

## Access Token 갱신 흐름

Access Token은 1시간 수명. 매번 호출 전 다음 단계 수행:

```
POST https://oauth2.googleapis.com/token
  client_id={CLIENT_ID}
  client_secret={CLIENT_SECRET}
  refresh_token={REFRESH_TOKEN}
  grant_type=refresh_token

→ { "access_token": "ya29.a0A...", "expires_in": 3599, ... }
```

토큰은 메모리에 캐시하고 만료 5분 전부터 갱신.

## 공식 엔드포인트

| 작업 | 엔드포인트 (REST) |
|---|---|
| Customer 조회 | `GET /v23/customers/{customer_id}` |
| Resource mutate | `POST /v23/customers/{customer_id}/{resource}:mutate` |
| GAQL search | `POST /v23/customers/{customer_id}/googleAds:search` |
| GAQL stream | `POST /v23/customers/{customer_id}/googleAds:searchStream` |

Base URL: `https://googleads.googleapis.com`

## 에러 코드 진단표

호출 실패 시 이 순서로 체크:

| HTTP | 에러 코드 | 진단 |
|---|---|---|
| 401 | `UNAUTHENTICATED` | Access Token 만료 → 갱신 / `Authorization` 헤더 누락 |
| 401 | `invalid_grant` (토큰 갱신 시) | Refresh Token 만료(6개월 미사용) → 재발급 필요 |
| 403 | `PERMISSION_DENIED` | `developer-token` 헤더 누락 또는 잘못됨 / MCC에 해당 계정이 연결 안 됨 |
| 403 | `CUSTOMER_NOT_ENABLED` | 테스트 계정이 아직 활성화 안 됨 |
| 400 | `INVALID_LOGIN_CUSTOMER_ID` | `login-customer-id` 헤더 누락/형식 오류 |
| 400 | `INVALID_CUSTOMER_ID` | URL의 customer_id가 잘못됨 (하이픈 포함 X) |
| 400 | `REQUIRED_FIELD_MISSING` | mutate 페이로드 누락 필드 — 응답의 `location` 필드 확인 |
| 400 | `DEVELOPER_TOKEN_NOT_APPROVED` | Test Access 이상 레벨 필요 — 현재 Test Access면 test manager 하위 계정으로만 호출 |
| 429 | `RESOURCE_EXHAUSTED` | Rate limit → `Retry-After` 대기 후 재시도 |

상세: [ErrorCode 레퍼런스](https://developers.google.com/google-ads/api/reference/rpc/v23/ErrorCode)

## 금액 단위 주의

Google Ads는 **모든 금액을 micros로 표현**:
- `₩1 = 1,000,000 micros`
- `₩10,000 → amount_micros = 10_000_000_000`
- `₩100,000,000 → amount_micros = 100_000_000_000_000`

Kotlin에서 안전하게 변환:
```kotlin
fun wonToMicros(won: Long): Long = Math.multiplyExact(won, 1_000_000L)
fun microsToWon(micros: Long): Long = micros / 1_000_000L
```

## 참고 문서

- [OAuth 2.0 설정](https://developers.google.com/google-ads/api/docs/oauth/overview)
- [Developer Token](https://developers.google.com/google-ads/api/docs/access-levels)
- [공식 Java/Kotlin Client](https://github.com/googleads/google-ads-java)
- [v23 Release Notes](https://developers.google.com/google-ads/api/docs/release-notes)
