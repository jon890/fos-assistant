# Phase 05. 사용량과 에이전트 화면의 내부 값을 역할에 따라 가린다

**Execution profile**: standard

## 목표

`MEMBER` 역할 사용자의 사용량, 실행 상세, 에이전트 상세 화면에서 금액, 모델 이름, 토큰 수, effort, 오류 코드, 영어 도구 이름을 빼거나 사람 말로 바꾼다.
`ADMIN` 은 지금 값을 그대로 본다. 일반 사용자 화면이 운영 화면처럼 읽히지 않게 하려는 것이다.

**범위 외**: 화면 구조(탭 재배치, 폼과 목록의 순서)는 바꾸지 않는다. Control Plane 의 응답은 바꾸지 않는다. 모델을 고르는 대화상자와 실행 상세의 모델 단계 표시는 다른 작업이 만든다.

## 컨텍스트

- 역할은 `web/src/components/shell/app-shell.tsx` 의 `useShellIsAdmin()` 이 준다. 서버 컴포넌트는 `web/src/lib/me.ts` 의 `readMe()` 로 `role` 을 읽는다. `web/src/app/memory/page.tsx` 가 그 패턴이다.
- `web/src/app/usage/page.tsx` 가 탭 넷을 그린다. 탭 목록은 `web/src/components/usage/usage-tabs.tsx` 의 `USAGE_TABS` 와 `parseUsageTab` 이 갖는다.
- 실행 기록은 좁은 폭에서 `execution-card.tsx`, 넓은 폭에서 `execution-table.tsx` 다. 둘 다 `execution-list.tsx` 의 도우미를 쓴다.
- 실행 상세는 `web/src/components/execution/execution-detail.tsx` 가 머리 요약을, `execution-event-row.tsx` 가 줄을 그린다. 실패 줄은 `실행 실패: {detail}` 이고 `detail` 에 `HERMES_BUSY` 같은 오류 코드가 온다.
- 오류 코드를 문구로 바꾸는 표는 `web/src/components/error-message.ts` 의 `describeError(code, fallback)` 이다.
- 도구 묶음의 `label` 과 `description` 은 Hermes 가 준 영어 이름과 평서체 설명이다. `name` 이 키다: `web`, `vision`, `todo`, `clarify`, `session_search`, `skills`, `tts`, `delegation`, `terminal`, `file`, `code_execution`, `browser`, `computer_use`, `cronjob`, `image_gen`, `video_gen`, `homeassistant`, `spotify`, `discord`.
- phase 03 이 `web/src/components/ui/switch.tsx` 의 `Switch` 를, phase 04 가 `web/src/lib/format.ts` 의 `formatSeconds` 를 만들었다.
- 브라우저 검사의 기본 사용자는 관리자다. 다른 사용자로 로그인하는 것은 `test/browser/fixtures.ts` 의 `setSession` 이 한다. 씨 뿌린 에이전트는 모두 관리자 소유의 비공개라 `MEMBER` 는 그것으로 실행을 만들 수 없다.
  `MEMBER` 검사는 `test/browser/agent-lifecycle.spec.ts` 25~60줄의 방법을 따른다: 전용 메일로 로그인해 자기 에이전트를 만들고, 그 에이전트로 대화를 보내 실행을 만들고, `afterEach` 에서 에이전트를 지운다. 붐빔 실패는 대역의 `hermes.busy()` 로 만든다. 그룹 공개 에이전트를 남기지 않는다(`identity.spec.ts` 가 어긋난다).

**근거 문서**: `docs/code-architecture.md` 의 「사용량 화면의 탭」 절, `docs/flow.md` 의 「실행이 실패할 때」 절, `web/AGENTS.md` 의 「화면 문구」 절, `docs/prd.md`

## 의도 메모

- 화면에서만 가린다. 비밀을 지키는 경계가 아니라 읽기 쉽게 하는 것이다. 응답에는 값이 그대로 온다.
- 다른 작업이 실행 기록에 모델 단계(빠르게, 균형, 깊게)를 보인다. 역할 규칙을 같게 둔다: `ADMIN` 은 실제 값 전부, `MEMBER` 는 단계 이름과 걸린 시간만. 이 phase 는 숨기는 쪽만 한다.
- 도구 이름을 Control Plane 이 한국어로 주게 바꾸지 않는다. 화면 문구는 화면이 갖는다.

## 작업 항목

### 1. `web/src/components/usage/usage-tabs.tsx` 와 `web/src/app/usage/page.tsx`

- `UsageTabs` 에 `isAdmin: boolean` 을 더한다. `MEMBER` 에게는 `fingerprints` 탭을 그리지 않는다. 탭 이름 「입력 지문」 을 `web/AGENTS.md` 용어 표의 「설정별 사용량」 으로 바꾼다.
- `parseUsageTab(value, isAdmin)` 으로 바꿔, `MEMBER` 가 `?tab=fingerprints` 로 오면 `summary` 를 돌려준다.
- `page.tsx` 는 `readMe()` 로 역할을 읽는다. `MEMBER` 면 breakdown 두 조회를 부르지 않는다.
- 머리 문장: `ADMIN` 은 지금 문장, `MEMBER` 는 「이번 달에 비서와 한 일을 모아 보여 드려요.」
- 요약 탭: `MEMBER` 에게는 `MonthlySummary` 에 `isAdmin={false}` 를 줘 실행 건수 `Stat` 하나만 그린다(라벨 「이번 달 실행」, 값 `n건`, 설명 없음). `BreakdownSection` 은 그리지 않는다.

### 2. `web/src/components/usage/execution-card.tsx`, `execution-table.tsx`, `execution-list.tsx`

`ExecutionList` 는 서버 컴포넌트다. `web/src/app/usage/page.tsx` 가 `isAdmin` 을 `ExecutionList` 에 넘기고, 그것이 카드와 표에 넘긴다.

| 값 | `ADMIN` | `MEMBER` |
| --- | --- | --- |
| 에이전트 이름, 시각, 걸린 시간, 상태 배지, 스킬 이름, 다시 보낸 실행 표시 | 보인다 | 보인다 |
| 에이전트 코드, 금액 둘, provider 와 모델, effort, 토큰, 문맥 글자 수, 문맥이 빠졌다는 줄, `title` 의 가격표 판 | 보인다 | 그리지 않는다 |

걸린 시간은 `MEMBER` 에게 `formatSeconds` 로 보이고 1초 미만이면 「1초 미만」 이다. `ADMIN` 은 `formatDuration` 그대로다.
표의 열 머리도 `MEMBER` 에게는 남는 열만 그린다.
`data-testid` 는 그리는 요소에서 그대로 지킨다.

### 3. `web/src/components/execution/execution-detail.tsx` 와 `execution-event-row.tsx`

- 머리 요약 `dl`: `MEMBER` 에게는 에이전트, 상태, 걸린 시간만 그린다. 모델, 입력 토큰, 출력 토큰, 환산 금액은 `ADMIN` 만.
- 제목: `summary.agentName ?? summary.agentCode ?? ...` 에서 `MEMBER` 에게는 `agentCode` 대신 `agentLabel(summary.agentName)` 을 쓴다.
- 실패 줄: `ExecutionEventRow` 에 `isAdmin` 을 넘긴다(`ExecutionTree` 와 `ExecutionNode` 를 거친다. `activity-panel.tsx` 가 `ExecutionTree` 를 쓰면 거기서도 넘긴다).
  - `MEMBER`: `실행 실패: ${describeError(detail, "실행을 마치지 못했어요.")}`. `detail` 이 없으면 `실행 실패`.
  - `ADMIN`: `실행 실패: ${describeError(detail, detail)} (${detail})`. 표에 없는 코드면 `실행 실패: ${detail}`.
  - 글자색을 `text-destructive` 로 둔다.
- `ExecutionNode` 의 이름 `node.agentName ?? node.agentCode` 도 `MEMBER` 에게는 `agentLabel(node.agentName)` 이다.

### 4. `web/src/lib/toolset-label.ts` 신규

```ts
export function toolsetText(name: string, fallback: { label: string; description: string }): { label: string; description: string }
```

| `name` | 이름 | 설명 |
| --- | --- | --- |
| `web` | 웹 검색 | 웹에서 찾아봐요 |
| `vision` | 사진 보기 | 올린 사진을 읽어요 |
| `todo` | 할 일 정리 | 긴 일을 할 일로 나눠 챙겨요 |
| `clarify` | 되묻기 | 모호하면 먼저 물어봐요 |
| `session_search` | 지난 대화 찾기 | 예전 대화에서 찾아봐요 |
| `skills` | 스킬 | 올려 둔 스킬을 써요 |
| `tts` | 소리 내어 읽기 | 글을 음성으로 읽어요 |
| `delegation` | 도우미에게 맡기기 | 일을 나눠 도우미에게 맡겨요 |
| `terminal` | 명령 실행 | 서버에서 명령을 실행해요 |
| `file` | 파일 | 파일을 읽고 써요 |
| `code_execution` | 코드 실행 | 코드를 돌려 계산해요 |
| `browser` | 브라우저 | 웹 페이지를 열어 조작해요 |
| `computer_use` | 컴퓨터 조작 | 화면을 보고 컴퓨터를 조작해요 |
| `cronjob` | 예약 실행 | 정한 시각에 일을 해요 |
| `image_gen` | 그림 만들기 | 그림을 만들어요 |
| `video_gen` | 동영상 만들기 | 동영상을 만들어요 |
| `homeassistant` | 집 기기 | 집의 기기를 살피고 조작해요 |
| `spotify` | Spotify | 음악을 틀고 멈춰요 |
| `discord` | Discord | Discord 에 글을 보내고 읽어요 |

표에 없는 `name` 은 `fallback` 을 그대로 돌려준다. 다른 모듈을 import 하지 않는다(`node --test` 가 직접 읽는다).

`web/src/components/agent/agent-tools-section.tsx`:

- 줄의 이름과 설명, 스위치의 `aria-label`, 확인 창 제목(`{confirming.label} 도구 켜기`)에 `toolsetText` 를 쓴다.
- 묶음 제목 「주인 등급」 을 「바로 켤 수 있어요」, 「관리자 등급」 을 「관리자만 켤 수 있어요」 로 바꾼다. 오른쪽 위 배지(「관리자」/「주인」)는 뺀다.
- `web/src/components/agent/agent-skills-section.tsx` 의 배지 「Hermes 기본」 을 「기본 스킬」 로 바꾼다.

### 5. 검사

- `test/unit/toolset-label.test.ts` 신규: 표의 `name` 열아홉이 모두 한국어 이름을 갖는다, 모르는 `name` 은 `fallback` 을 돌려준다, 설명이 모두 「요」 로 끝난다.
- `test/browser/usage.spec.ts` 에 `MEMBER` 검사를 더한다: 실행 기록에 `USD`, `example-model`, `effort`, `문맥` 이 없고 에이전트 이름과 상태 배지가 있다. 요약 탭에 「API 가격」 이 없다. `?tab=fingerprints` 로 가면 요약 탭이 열린다.
- 같은 파일의 기존 관리자 검사는 그대로 통과해야 한다. 탭 이름을 「설정별 사용량」 으로 찾게 고친다(`usage-breakdown.spec.ts` 포함).
- `test/browser/execution-tree.spec.ts` 에 더한다: 붐빔으로 실패한 실행의 상세를 `MEMBER` 로 열면 `HERMES_BUSY` 가 없고 「지금 요청이 많아요」 가 있다. 관리자는 둘 다 본다.
- `test/browser/agent-tools.spec.ts`: 도구 이름을 영어 `label` 로 찾는 곳을 한국어 이름으로 고친다. 「주인 등급」, 「관리자 등급」 을 찾는 곳도 고친다.
- `test/browser/skills.spec.ts`: 「Hermes 기본」 을 찾는 곳을 고친다.

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/toolset-label.test.ts test/unit/error-message.test.ts
! grep -rnE '주인 등급|관리자 등급|Hermes 기본|입력 지문' web/src
cd web && pnpm typecheck && pnpm lint
cd web && pnpm test:browser test/browser/usage.spec.ts test/browser/usage-breakdown.spec.ts test/browser/execution-tree.spec.ts test/browser/agent-tools.spec.ts test/browser/skills.spec.ts test/browser/activity-panel.spec.ts
```

기대값: 모두 종료 코드 0.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/toolset-label.ts` | 신규 |
| `test/unit/toolset-label.test.ts` | 신규 |
| `web/src/app/usage/page.tsx` | 수정 |
| `web/src/components/usage/*.tsx` | 수정 |
| `web/src/components/execution/*.tsx` | 수정 |
| `web/src/components/chat/activity/activity-panel.tsx` | 수정 |
| `web/src/components/agent/agent-tools-section.tsx` | 수정 |
| `web/src/components/agent/agent-skills-section.tsx` | 수정 |
| `test/browser/usage.spec.ts` | 수정 |
| `test/browser/usage-breakdown.spec.ts` | 수정 |
| `test/browser/execution-tree.spec.ts` | 수정 |
| `test/browser/agent-tools.spec.ts` | 수정 |
| `test/browser/skills.spec.ts` | 수정 |
