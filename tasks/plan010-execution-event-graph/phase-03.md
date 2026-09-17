# Phase 03. 실행 나무를 화면에 그린다

**Execution profile**: standard

## 목표

실행 하나의 도구 호출과 하위 에이전트를 나무로 보이는 화면을 만든다.
읽기 전용이다.

**범위 외**:
DAG 편집기를 만들지 않는다.
실시간으로 갱신하지 않는다. 화면을 열 때 한 번 읽는다.

## 컨텍스트

phase-02 가 `GET /api/v1/usage/executions/{id}/tree` 를 열었다.
이 phase 는 그 응답을 그린다.

그리려는 모양이다.

```
Chief
├─ tool: search
├─ subagent: researcher
│  └─ tool: browser
└─ tool: filesystem
```

지금은 하위 에이전트를 만드는 경로가 없어 대부분 도구 한 층만 나온다.
그래도 층을 담을 수 있는 모양으로 그린다. plan011 이 그 경로를 만든다.

화면 규칙은 이미 정해져 있다.
색을 인라인으로 적지 않고 `globals.css` 의 `@theme` 토큰을 Tailwind 클래스로 쓴다.
브랜드 색을 본문 글자에 쓰지 않는다. 읽는 글이 색을 가지면 누를 수 있는 것과 구분되지 않는다.

**근거 문서**: `docs/adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md`,
`docs/code-architecture.md` 의 「web 화면 구조」 와 「실행 사건」 절

## 의도 메모

- 그림 라이브러리를 들이지 않는다. 들여쓴 목록으로 충분하다.
  노드가 몇 개 안 되고, 좁은 화면에서 그림은 가로로 넘친다.
- 실시간 갱신을 만들지 않는다.
  돌고 있는 실행을 보려면 대화 화면이 이미 그것을 흘려 보인다.
  이 화면은 끝난 뒤에 다시 보는 자리다.
- 모든 사건을 다 보이지 않는다.
  `TOOL_STARTED` 와 `TOOL_COMPLETED` 를 한 줄로 합쳐 보인다.
  둘을 따로 보이면 줄 수가 두 배가 되고 읽을 것이 늘지 않는다.

## 작업 항목

### 1. `/executions/[id]` 경로를 만든다

`web/src/app/executions/[id]/page.tsx` 다.

머리에 그 실행의 요약을 둔다.

| 보이는 것 | 비고 |
| --- | --- |
| 에이전트 이름 | |
| 상태 | `RUNNING` 은 「도는 중」 |
| 모델 | |
| 입력과 출력 토큰 | |
| 환산 금액 | 없으면 비워 둔다. 0 으로 채우지 않는다 |
| 걸린 시간 | 끝나지 않았으면 비워 둔다 |

그 아래에 나무를 둔다.

### 2. `web/src/components/execution/` 아래에 부품을 만든다

| 파일 | 하는 일 |
| --- | --- |
| `execution-tree.tsx` | 나무 전체 |
| `execution-node.tsx` | 노드 하나. 자기 자신을 자식으로 다시 그린다 |
| `execution-event-row.tsx` | 사건 한 줄 |

`execution-node.tsx` 가 재귀로 자신을 그린다.
**깊이 상한을 화면에서도 둔다.** 서버가 8 에서 자르지만 화면도 자체로 멈춘다.
서버가 `truncated` 를 참으로 주면 그 자리에 「여기부터 보이지 않는다」 를 한 줄로 적는다.

### 3. 사건을 한 줄로 합친다

`TOOL_STARTED` 와 그 뒤의 `TOOL_COMPLETED` 를 짝지어 한 줄로 그린다.

| 보이는 것 | 어디서 |
| --- | --- |
| 도구 이름 | `toolName` |
| 걸린 시간 | `TOOL_COMPLETED` 의 `durationMs` |
| 상세 | `detail` |

짝이 없으면 그대로 한 줄로 그린다. 그 실행이 도중에 끊긴 것이다.
`TOOL_STARTED` 만 있으면 「끝나지 않음」 으로 보인다.

`SUBAGENT_STARTED` 는 자식 노드가 있으면 그 노드로 그리고, 없으면 한 줄로 그린다.

`RUN_STARTED` 와 `RUN_COMPLETED` 는 따로 줄로 그리지 않는다.
머리의 상태와 걸린 시간이 이미 그것을 말한다.
`RUN_FAILED` 만 그 자리에 오류로 그린다.

### 4. 서버 라우트를 만든다

`web/src/app/api/usage/executions/[id]/tree/route.ts` 다.
브라우저가 Control Plane 토큰을 갖지 않으므로 서버 라우트가 토큰을 만들어 부른다.

### 5. 사용량 목록에서 들어가게 한다

`/usage` 의 실행 목록에서 한 줄을 누르면 `/executions/{id}` 로 간다.

phase-02 가 `hasChildren` 을 응답에 더했다.
자식이 있는 실행은 목록에서 그것을 표시한다.

### 6. 빈 상태와 실패 상태

| 상황 | 화면 |
| --- | --- |
| 사건이 하나도 없다 | 「기록된 사건이 없다」 한 줄. 요약은 그대로 보인다 |
| 남의 실행 번호다 | 없는 것과 같은 오류. 사용량 목록으로 되돌린다 |
| 읽지 못했다 | 그 자리만 오류를 보이고 요약은 남긴다 |

사건이 없는 것은 정상이다.
한 번에 받는 경로로 돈 실행은 도구 사건이 오지 않는다.

### 7. 이 phase 를 검증하는 테스트

`test/browser/execution-tree.spec.ts` 를 새로 만든다.

- **정상 경로**: 도구 사건 둘을 가진 실행을 열면 두 줄이 보이고 도구 이름이 들어 있다
- `TOOL_STARTED` 와 `TOOL_COMPLETED` 가 한 줄로 합쳐진다
- **이 phase 가 다루는 실패**: 사건이 없는 실행을 열면 「기록된 사건이 없다」 가 보이고
  요약은 그대로 보인다
- 하위 에이전트 노드가 한 단 들여써서 보인다
- `mobile` 과 `desktop` 두 폭에서 가로로 넘치지 않는다.
  깊은 나무에서 들여쓰기가 화면을 밀어내지 않는지 본다

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
```

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/components/execution/ | grep -iE 'background|color|border' && echo "실패: 색 인라인 스타일" || echo "통과"
```

브라우저에서 390px 과 1280px 을 열어 본 결과를 보고에 적는다.
특히 깊이 3 이상의 나무에서 가로 스크롤이 생기는지 본다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/app/executions/[id]/page.tsx` | 신규 |
| `web/src/app/api/usage/executions/[id]/tree/route.ts` | 신규 |
| `web/src/components/execution/execution-tree.tsx` | 신규 |
| `web/src/components/execution/execution-node.tsx` | 신규 |
| `web/src/components/execution/execution-event-row.tsx` | 신규 |
| `web/src/components/usage/` 의 실행 목록 | 수정 |
| `test/browser/execution-tree.spec.ts` | 신규 |

## 끝낸 뒤

`tasks/plan010-execution-event-graph/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
