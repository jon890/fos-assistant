# Phase 03. 사용량 화면과 관리 화면을 읽을 수 있게 만든다

**Execution profile**: standard

## 목표

사용량 화면과 에이전트 관리 화면을 좁은 화면에서도 읽을 수 있게 만든다.
지금 사용량은 아홉 칸짜리 표라서 휴대폰에서는 가로로 밀어야 한 줄을 다 읽는다.

**범위 외**

- 새 집계를 만들지 않는다. 지금 오는 값만으로 다시 보인다.
- 기간을 고르거나 걸러 보는 것은 하지 않는다.
- 차트를 그리지 않는다. 새 의존을 더하지 않는다.

## 컨텍스트

**phase-01 과 phase-02 가 끝나 있어야 한다.**
색 토큰과 `components/ui/` 가 이미 있어야 한다.

백엔드는 이미 아래를 준다. 새로 만들지 않는다.

| 경로 | 주는 것 |
| --- | --- |
| `GET /api/v1/usage/executions?limit=50` | 실행 한 줄마다의 에이전트, 모델, 상태, 토큰, 소요, 환산 금액 |
| `GET /api/v1/usage/monthly-cost` | 이번 달 합계와 가격을 찾지 못한 실행 수 |
| `GET /api/v1/admin/agents` | 등록된 에이전트 전부 |

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| 사용량 화면 | `web/src/app/usage/page.tsx` |
| 관리 화면 | `web/src/app/admin/agents/agent-admin-panel.tsx` |
| 공용 조각 | `web/src/components/ui/` |

**근거 문서**: `docs/data-schema.md` 의 「agent_execution」 절,
`docs/code-architecture.md` 의 「web 화면 구조」 절,
`docs/adr/ADR-004-구독제에서도-api-가격으로-환산해-보인다.md`

## 의도 메모

- 금액이 비어 있는 것과 0 인 것을 다르게 보인다.
  0 은 공짜라는 뜻이고 비어 있는 것은 가격표에서 찾지 못했다는 뜻이다.
  지금 `가격 없음` 으로 쓰고 있다. 그 구분을 잃지 않는다.
- 표를 `overflow-x-auto` 로 감싸는 것으로 끝내지 않는다.
  가로로 밀어야 하는 표는 밀 수 있다는 것을 사람이 모른다.
  좁은 화면에서는 표 대신 한 실행에 카드 하나를 쓴다.
- 토큰 수를 자릿수 구분 없이 보이지 않는다. `14954` 보다 `14,954` 가 빨리 읽힌다.
- 소요 시간을 `4136 ms` 로 쓰지 않는다. 초를 넘으면 `4.1초` 로 쓴다.
- 합계 하나를 크게 보인다. 지금은 표 위에 한 줄로 섞여 있어 눈에 들어오지 않는다.
- 관리 화면에서 `PRIVATE` 와 `FAMILY` 를 영문 그대로 보이지 않는다.
  다만 고르는 값은 그 값 그대로 보낸다. 화면 글자만 바꾼다.
- **도구가 열린 에이전트를 가족 공개로 바꾸는 것이 위험하다는 것을 화면이 알려야 한다.**
  `FAMILY` 로 바꾸려 할 때 한 번 확인받는다.

## 작업 항목

### 1. 공용 조각을 더한다

`web/src/components/ui/` 에 둔다.

| 파일 | 맡는 것 |
| --- | --- |
| `ui/stat.tsx` | 큰 숫자 하나와 그 설명 |
| `ui/badge.tsx` | 상태와 공개 범위를 나타내는 작은 표 |
| `ui/empty-state.tsx` | 아무것도 없을 때 보이는 안내 |

`ui/skeleton.tsx` 는 phase-02 가 이미 만들었다. 다시 만들지 않는다.

### 2. 값을 사람이 읽는 형태로 바꾼다

`web/src/lib/format.ts` 를 만든다.

| 함수 | 하는 일 |
| --- | --- |
| `formatTokens` | 자릿수를 구분한다. 비어 있으면 `-` |
| `formatDuration` | 1000ms 미만은 `840ms`, 넘으면 `4.1초` |
| `formatAmount` | 지금 `usage/page.tsx` 안에 있는 것을 옮긴다 |
| `formatWhen` | 오늘이면 시각만, 아니면 날짜와 시각 |

지금 `usage/page.tsx` 안에 있는 `formatAmount` 와 `formatCost` 를 여기로 옮긴다.

### 3. 사용량 화면을 다시 만든다

`web/src/components/usage/` 를 만든다.

| 파일 | 맡는 것 |
| --- | --- |
| `usage/monthly-summary.tsx` | 이번 달 합계. 큰 숫자로 |
| `usage/execution-table.tsx` | 넓은 화면의 표 |
| `usage/execution-card.tsx` | 좁은 화면의 카드 한 장 |
| `usage/execution-list.tsx` | 너비에 따라 둘 중 하나를 고른다 |

합계는 세 값을 나란히 보인다.

- 이번 달 환산 합계
- 실행 건수
- 가격을 찾지 못한 실행 수. 0 이면 이 자리를 비운다

표는 `md` 이상에서만 보인다. 그 아래에서는 카드를 쓴다.

카드 한 장에 담는 것은 아래와 같다.

```
┌──────────────────────────────────┐
│ 커리어                    0.0604 USD │
│ openai-codex / gpt-5.6-sol        │
│ 14,954 → 31   4.1초   09-17 13:53 │
└──────────────────────────────────┘
```

실패한 실행은 오류 코드를 표에 보이고 카드에서는 눈에 띄게 한다.

실행이 하나도 없으면 `ui/empty-state.tsx` 를 쓴다.

### 4. 관리 화면을 다시 만든다

`web/src/components/admin/` 를 만든다.

| 파일 | 맡는 것 |
| --- | --- |
| `admin/agent-list.tsx` | 등록된 에이전트 목록 |
| `admin/agent-card.tsx` | 한 에이전트. 좁은 화면에서도 읽힌다 |
| `admin/agent-form.tsx` | 새 에이전트 등록 |
| `admin/visibility-confirm.tsx` | 가족 공개로 바꿀 때 확인 |

`agent-admin-panel.tsx` 는 상태와 호출만 맡고 화면을 직접 그리지 않는다.

에이전트 하나에 보이는 것은 아래와 같다.

- 이름과 코드
- Hermes profile 과 주소
- provider 와 모델, 모델을 마지막으로 읽은 시각
- 공개 범위와 사용 여부
- 모델을 다시 읽는 단추

공개 범위 글자는 이렇게 쓴다.

| 값 | 화면 글자 |
| --- | --- |
| `PRIVATE` | 나만 |
| `FAMILY` | 가족 공개 |

### 5. 가족 공개로 바꿀 때 확인받는다

`admin/visibility-confirm.tsx` 를 만든다.

`FAMILY` 로 바꾸려 할 때 확인을 한 번 받는다. `PRIVATE` 로 되돌릴 때는 받지 않는다.

확인 문구에 그 에이전트 이름을 넣고, 가족 구성원 누구나 그 에이전트로 대화할 수 있게 된다고 적는다.

**도구가 열려 있는지는 화면이 알지 못한다.** 그 정보가 API 에 없다.
그러므로 도구를 근거로 문구를 바꾸지 않는다. 공개된다는 사실만 적는다.

### 6. 머리를 정리한다

`web/src/app/layout.tsx` 의 머리를 고친다.

- 지금 링크 셋이 나란히 있다. 어디에 있는지 표시가 없다.
- 지금 있는 화면을 눈에 띄게 한다.
- `에이전트 관리` 는 관리자에게만 보인다. 지금은 누구에게나 보인다.
  세션에서 판단할 수 없으면 눌렀을 때 오류가 나는 지금 동작을 유지하고 그 사실을 보고에 적는다.
- 밝기 단추는 phase-01 이 이미 넣었다. 자리만 맞춘다.

### 7. 이 phase 를 검증하는 브라우저 테스트

`test/browser/usage.spec.ts` 와 `test/browser/admin.spec.ts` 를 만든다.
하네스는 phase-01 이 만든 `test/browser/fixtures.ts` 를 쓴다.

`usage.spec.ts` 가 확인하는 것

- `mobile` 폭에서 표가 아니라 카드로 보이고, 가로 스크롤이 생기지 않는다.
- `desktop` 폭에서 표로 보인다.
- 이번 달 합계가 보인다.
- 가격을 찾지 못한 실행이 `0` 이 아니라 찾지 못했다고 보인다.
- 실행이 없으면 빈 상태 문구가 보인다.

`admin.spec.ts` 가 확인하는 것

- 공개 범위가 `PRIVATE` 가 아니라 `나만` 으로 보인다.
- 가족 공개로 바꾸려 하면 확인을 받고, 취소하면 값이 그대로다.
- 확인하면 값이 바뀐다.

가격을 찾지 못한 실행은 가짜 Hermes 가 모르는 모델 이름을 돌려주게 해서 만든다.

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck
cd web && pnpm build
```

빌드는 자리표시자 환경 변수가 필요하다. `web/Dockerfile` 이 쓰는 것과 같다.

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

기존 시나리오가 그대로 통과해야 한다.

```bash
# cwd: 저장소 root
grep -rn '"PRIVATE"\|"FAMILY"' web/src/components/admin/ | grep -v 'value=' && echo "확인: 영문 값이 화면에 그대로 나올 수 있다" || echo "통과"
```

```bash
# cwd: 저장소 root
cd web && pnpm test:browser
```

`mobile` 과 `desktop` 두 폭에서 모두 통과해야 한다.

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `web/src/lib/format.ts` | 신규 |
| `web/src/components/ui/stat.tsx` | 신규 |
| `web/src/components/ui/badge.tsx` | 신규 |
| `web/src/components/ui/empty-state.tsx` | 신규 |
| `web/src/components/usage/` | 신규. 네 파일 |
| `web/src/components/admin/` | 신규. 네 파일 |
| `web/src/app/usage/page.tsx` | 수정 |
| `web/src/app/admin/agents/agent-admin-panel.tsx` | 수정 |
| `web/src/app/layout.tsx` | 수정 |
| `test/browser/usage.spec.ts` | 신규 |
| `test/browser/admin.spec.ts` | 신규 |

## 끝낸 뒤

`tasks/plan005-ui/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
