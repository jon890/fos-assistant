# Phase 03. 웹에서 에이전트를 고르고 관리한다

**Execution profile**: standard

## 목표

대화를 시작할 때 에이전트를 고르고, 사용량에서 역할별로 구분해 본다.
관리자는 웹에서 에이전트를 등록하고 공개 범위를 바꾼다.

**범위 외**

- Hermes profile 을 웹에서 만들지 않는다. CLI 로만 만든다. 이유는 ADR-007 에 있다.
- 에이전트 삭제는 하지 않는다. `enabled` 를 내린다.
- 토큰 스트리밍은 plan004 가 한다.

## 컨텍스트

웹은 Next.js 16 과 React 19 를 쓴다.
브라우저는 Control Plane 을 직접 부르지 않는다.
Next.js 서버 라우트가 세션에서 메일 주소를 꺼내 짧은 수명의 토큰을 만들어 대신 부른다.

**공개 범위는 편의 기능이 아니라 보안 통제다.**
도구가 열린 에이전트를 가족 공개로 두면 그 순간 웹 챗 창이 홈서버 셸이 된다.
그래서 관리 화면에서 범위를 바꿀 때 무엇이 달라지는지 화면에 적어야 한다.
근거는 `docs/adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md` 에 있다.

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| 대화 화면 | `web/src/components/chat-panel.tsx` |
| 작업 영역 선택 | 같은 파일. 에이전트 선택도 같은 방식으로 둔다 |
| 서버 라우트 | `web/src/app/api/chat/route.ts` |
| 서버에서 직접 읽는 화면 | `web/src/app/usage/page.tsx` |
| 오류 문구 | `web/src/components/error-message.ts` |

**근거 문서**: `docs/flow.md`, `docs/adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md`

## 의도 메모

- 에이전트 선택은 **대화를 시작할 때만** 보인다.
  이어지는 대화에서는 고정된 에이전트를 읽기 전용으로 보인다.
  중간에 바꾸지 못하는 것이 화면에서도 드러나야 한다.
- 에이전트가 하나뿐이면 선택기를 보이지 않고 그것을 쓴다. 고를 것이 없는 선택기는 소음이다.
- 관리 화면은 별도 경로에 둔다. 일상 화면에 섞지 않는다.
- 사용량 화면에 에이전트 열을 더한다. 역할별로 얼마 썼는지가 이 열로 보인다.

## 작업 항목

### 1. 서버 라우트를 더한다

| 파일 | 하는 일 |
| --- | --- |
| `web/src/app/api/agents/route.ts` | `GET` 이 `/api/v1/agents` 를 부른다 |
| `web/src/app/api/admin/agents/route.ts` | `GET` 과 `POST` 가 `/api/v1/admin/agents` 를 부른다 |
| `web/src/app/api/admin/agents/[code]/route.ts` | `PATCH` 가 공개 범위와 사용 여부를 바꾼다 |
| `web/src/app/api/admin/agents/[code]/sync-model/route.ts` | `POST` 가 모델을 다시 맞춘다 |

`web/src/app/api/usage/route.ts` 와 같은 형태를 따른다. 오류는 코드와 문구를 그대로 넘긴다.
경로 인자는 `[a-z0-9][a-z0-9-]*` 만 받는다. 아니면 부르지 않고 400 으로 답한다.

### 2. 대화 화면에 에이전트를 더한다

`web/src/components/chat-panel.tsx` 를 고친다.

- 처음 뜰 때 쓸 수 있는 에이전트를 읽는다.
- 새 대화면 선택기를 보인다. 하나뿐이면 선택기 없이 그것을 쓴다.
- 이어지는 대화면 그 대화의 에이전트 이름을 읽기 전용으로 보인다.
- 메시지를 보낼 때 `agentCode` 를 함께 보낸다.
- 에이전트가 하나도 없으면 입력창을 잠그고 관리자에게 등록을 요청하도록 안내한다.

### 3. 관리 화면을 만든다

`web/src/app/admin/agents/page.tsx` 를 만든다.

- 등록된 에이전트를 표로 보인다. `code`, `name`, `model`, 공개 범위, 사용 여부, 마지막 모델 확인 시각.
- 등록 양식을 둔다. `model` 은 받지 않는다. Hermes 에 물어 채운다.
- **공개 범위를 고르는 자리에 그것이 무엇을 뜻하는지 적는다.**
  가족 공개는 모든 구성원이 그 에이전트의 도구를 쓸 수 있다는 뜻이다.
- 사용 여부를 바꾸는 단추와 모델을 다시 맞추는 단추를 둔다.
- 관리자가 아니면 이 화면에 들어오지 못한다. 서버에서 확인하고 되돌린다.

### 4. 사용량 화면에 에이전트를 더한다

`web/src/app/usage/page.tsx` 에 에이전트 열을 더한다.
백엔드 응답에 `agentCode` 와 `agentName` 이 있어야 한다.
없으면 `UsageController` 의 응답에 더한다.

### 5. 오류 문구를 더한다

`web/src/components/error-message.ts` 에 더한다.

| 코드 | 문구가 담을 것 |
| --- | --- |
| `AGENT_NOT_FOUND` | 없는 에이전트이거나 쓸 수 없는 에이전트다 |
| `AGENT_DISABLED` | 이 에이전트는 지금 쓰지 않도록 되어 있다 |
| `AGENT_MODEL_UNKNOWN` | Hermes 에서 모델을 읽지 못했다 |

### 6. 이 phase 를 검증하는 e2e 테스트

`test/e2e/scenarios/` 아래에 더하고 `test/e2e/run.ts` 에 등록한다.
기존 시나리오 파일의 형태를 따른다.

- 쓸 수 있는 에이전트 목록에 남의 개인 에이전트가 없다.
- 에이전트를 골라 보낸 대화가 그 에이전트로 고정된다.
- 관리자가 아닌 사용자는 등록과 변경이 거절된다.
- 사용량 응답에 에이전트가 담긴다.

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

```bash
# cwd: 저장소 root
grep -rn "FAMILY" web/src/app/admin/agents/ \
  && echo "확인: 가족 공개의 뜻이 화면에 적혀 있는지 위 결과를 본다"
```

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `web/src/app/api/agents/route.ts` | 신규 |
| `web/src/app/api/admin/agents/route.ts` | 신규 |
| `web/src/app/api/admin/agents/[code]/route.ts` | 신규 |
| `web/src/app/api/admin/agents/[code]/sync-model/route.ts` | 신규 |
| `web/src/app/admin/agents/page.tsx` | 신규 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/app/usage/page.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `test/e2e/scenarios/` | 신규 |

## 끝낸 뒤

`tasks/plan003-agents/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 다음 번호로 올린다.
