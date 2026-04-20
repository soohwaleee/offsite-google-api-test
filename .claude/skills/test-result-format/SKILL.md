---
name: test-result-format
description: Google Ads API 테스트 실행 결과를 마크다운 리포트로 포맷팅한다. PASS/FAIL 요약 표, 블로커 분류, 이전 런 대비 diff, 체크리스트 업데이트 제안을 포함한다. gads-result-reporter가 사용한다. 테스트 실행 결과 문서화 작업에서 반드시 이 스킬을 사용할 것.
---

# test-result-format

테스트 실행 결과 리포트 포맷. 팀/PM이 30초 안에 이해할 수 있게 구조화한다.

## 왜 이 스킬이 필요한가

테스트 결과는 두 독자를 대상으로 한다:
1. **개발자** — 어떤 테스트가 왜 실패했는지 raw 데이터
2. **PM/리더** — 무엇이 되고 무엇이 안 되고 다음에 뭘 해야 하는지

원시 JUnit XML은 둘 다에 부적합하다. 이 스킬은 둘을 동시에 만족하는 리포트 포맷을 제공한다.

## 파일명 규칙

`_workspace/gads-test-run-{YYYY-MM-DD-HHmm}.md`

예: `gads-test-run-2026-04-20-2245.md`

## 표준 리포트 템플릿

```markdown
# Google Ads API 테스트 실행 리포트 — {YYYY-MM-DD HH:mm}

**하네스**: test-gads-api
**실행자**: {orchestrator 에이전트 또는 사용자}
**환경**: test manager 계정 ({customer_id})

---

## 📊 요약

| 지표 | 값 |
|---|---|
| 실행 시나리오 | N |
| PASS | p |
| FAIL | f |
| ERROR | e |
| SKIP | s |
| 소요 시간 | HH:MM:SS |
| 신규 블로커 | n |
| 해소된 블로커 | m |

---

## ✅ 결과

| # | 시나리오 | 상태 | 소요(ms) | 비고 |
|---|---|---|---|---|
| T0-1 | OAuth 인증 | ✅ PASS | 320 | — |
| T0-2 | test_account 확인 | ✅ PASS | 180 | — |
| T0-3 | Merchant Center 링크 | ❌ FAIL | 210 | link 0개 |
| T1 | 캠페인 최소 생성 | ⏭️ SKIP | — | T0-3 블로킹 |
| ... | | | | |

상태 아이콘: ✅ PASS · ❌ FAIL · ⚠️ ERROR · ⏭️ SKIP

---

## 🚧 블로커

### [CONFIG] Merchant Center 링크 없음
- **증거**: `_workspace/gads-test-logs/T0-3/response.json` — `results: []`
- **영향 범위**: T1~T7, T9 차단 (Retail PMax 생성 불가)
- **다음 액션**:
  1. Merchant Center 콘솔에서 Google Ads 링크 요청 수신 승인
  2. 또는 Google Ads UI → Linked Accounts → Merchant Center 요청 전송
- **체크리스트 반영**: B2 🟡 → 🔴 (링크 필요)

---

## 🔄 이전 런 대비

- **회귀 없음**
- 또는 `T1: FAIL → PASS` / `T4: 신규 실패`

(최초 런이면 "비교 대상 없음" 표기)

---

## 📋 체크리스트 업데이트 제안

- B1 v23 확정: 변경 없음 ✅
- B2 Merchant Center: 🟡 → 🔴 (링크 부재)
- B3 AssetGroup: 검증 미도달 (T1 SKIP)

> ※ 체크리스트 파일은 사용자 승인 후 수동 업데이트

---

## 🎯 다음 실행 제안

1. **B2 해소** 우선 — Merchant Center 링크 승인 후 T0-3부터 재실행
2. B2 해소되면 T1~T7 full run
3. 성과 관련(T8~T10)은 집행 데이터 누적 후 재실행 (최소 1일 지연)

---

## 📎 첨부

- raw 로그: `_workspace/gads-test-logs/{run-id}/`
- 블로커 상세: `_workspace/gads-blockers/{run-id}.md`
- 이전 런: `_workspace/gads-test-run-{prev-timestamp}.md`
```

## 포맷 규칙

- **짧고 스캔 가능**: PM이 30초에 파악
- **아이콘 통일**: ✅ ❌ ⚠️ ⏭️ 만 사용 (다른 이모지 혼용 금지)
- **근거 경로**: 블로커에는 raw 로그 상대 경로 필수
- **수치 단위**: 시간은 ms / won / micros 명시
- **제안은 순서화**: "다음 실행 제안"은 우선순위 매긴 번호 목록

## 작성 체크리스트

- [ ] 파일명에 timestamp 포함
- [ ] 요약 표 맨 위
- [ ] 각 FAIL에 블로커 섹션 대응
- [ ] 이전 런이 있으면 diff 섹션 생성
- [ ] 체크리스트 업데이트는 **제안**으로만 — 직접 편집 금지
- [ ] 마지막 섹션은 항상 "다음 실행 제안"
