# Phase 08. 화면: 먼저 살펴보기 절, 점검 대화의 단추와 배지

**Execution profile**: standard

## 목표

에이전트 상세에 「먼저 살펴보기」 절을 두고, 점검 대화의 머리 줄에 「지금 살펴보기」 단추를, 대화 목록의 점검 대화에 「살펴보기」 배지를 둔다.
사용자는 스킬을 부르지 않고 단추 하나로 살펴보기를 시작한다.

**범위 외**: backend 와 대역(앞 phase 들). 「지금」 화면과 웹 알림(PR #163, #164).

## 컨텍스트

**근거 문서**: `docs/frontend/structure.md` 의 「에이전트 화면」 표와 「먼저 살펴보기 절」, `docs/frontend/chat.md` 의 「점검 대화」, `docs/frontend/shell.md` 의 「대화 목록」 의 배지 문단, `docs/flow.md` 의 「먼저 살펴보기」 와 「실행이 실패할 때」 의 `PROACTIVE_CHECK_UNAVAILABLE` 줄

- 화면 규칙은 `web/AGENTS.md` 가 갖는다. 브라우저는 Control Plane 을 직접 부르지 않고 `app/api/` 서버 라우트를 거친다. 색은 토큰으로만, 안내와 오류는 `components/ui/notice.tsx` 의 `Notice`, 배지는 `Badge` 로 그린다. 주 단추는 한 화면에 하나다
- 서버 라우트 본보기: `web/src/app/api/agents/[code]/starters/route.ts`(`AGENT_CODE_PATTERN` 검사, `callControlPlane`, `errorResponse`)
- 에이전트 상세: `web/src/components/agent/agent-detail-loader.tsx` 가 서버에서 읽고 `agent-detail-body.tsx` 가 절을 차례로 그린다. 스킬 절(`AgentSkillsSection`) 다음에 둔다. 커넥터 에이전트(`connectorManaged`)에는 그리지 않는다
- toolset 의 한국어 이름: `web/src/lib/toolset-label.ts`
- 오류 문구: `web/src/components/error-message.ts`(없으면 그 역할의 파일을 `grep -rn "USER_BUSY" web/src` 로 찾는다)
- 대화 목록 타입: `web/src/components/shell/conversations-provider.tsx` 의 `Conversation`. 목록 줄: `web/src/components/shell/conversation-nav.tsx`
- 대화 화면 머리 줄: `web/src/components/chat/conversation-session.tsx` 의 머리 영역(에이전트 이름을 그리는 자리). 도는 turn 을 따라가는 경로는 `beginObserving` 과 대화 단위 SSE 다
- 브라우저 검사: `test/browser/` 아래 `*.spec.ts`. 본보기는 `test/browser/agent-tools.spec.ts`(에이전트 상세 절), `test/browser/chat-delegation-wake.spec.ts`(사용자 질문 없는 turn 의 표시). 대역 Hermes 는 phase 07 이 살펴보기의 기본 답을 준다

## 의도 메모

- 사용자에게 스킬을 부르라고 안내하지 않는다. `SKILL_MISSING` 안내는 에이전트를 준비하는 사람이 스킬을 설치해야 한다는 뜻으로 쓴다.
- 시작이 `PROACTIVE_CHECK_UNAVAILABLE` 로 거절되면 상태를 다시 읽어 까닭을 그린다. 거절 응답에는 까닭이 없다.
- 점검 대화의 단추는 그 대화에 도는 turn 이 있는 동안 끈다.
- 화면에 실행 번호, 오류 코드 원문, 모델, 금액을 그리지 않는다(ADR-063).

## 작업 항목

### 1. 서버 라우트와 API 도우미

- `web/src/app/api/agents/[code]/proactive-check/route.ts` 신규: `GET` 이 `/api/v1/agents/{code}/proactive-check` 를 그대로 넘긴다
- `web/src/app/api/agents/[code]/proactive-check/runs/route.ts` 신규: `POST` 가 `/api/v1/agents/{code}/proactive-check/runs` 를 부르고 202 와 본문을 넘긴다. 본문은 받지 않는다
- `web/src/lib/proactive-check.ts` 신규: 응답 타입(`ProactiveCheckStatus`, `ProactiveCheckBlocker`, `ProactiveCheckLastCheck`), 브라우저에서 부르는 `fetchProactiveCheckStatus(code)` 와 `startProactiveCheck(code)`, 까닭 코드와 마지막 결과를 한국어 문장으로 바꾸는 함수. 문장은 `docs/frontend/structure.md` 의 「먼저 살펴보기 절」 표 그대로다

### 2. 에이전트 상세의 절

- `web/src/components/agent/agent-proactive-check-section.tsx` 신규: `<section aria-label="먼저 살펴보기">`. 문서 표의 상태마다 그린다. 단추를 누르면 시작하고 202 면 `/chat/{conversationId}` 로 간다. 거절 문구는 단추 아래 `Notice` 로 그린다
- `agent-detail-loader.tsx` 가 상태를 서버에서 함께 읽어 넘긴다. 실패하면 절 안에 실패 문구만 그린다. 커넥터 에이전트는 읽지 않는다
- `agent-detail-body.tsx` 가 스킬 절 다음에 그린다
- `agent-detail-body.tsx` 를 쓰는 관리자 영역 상세(`/admin/agents/{code}`)에서도 같은 절이 보이는지 확인한다. 관리자도 자기 점검 대화만 다룬다

### 3. 대화 목록의 배지와 점검 대화의 단추

- `conversations-provider.tsx` 의 `Conversation` 에 `purpose: "CHAT" | "CHECK"` 를 더한다. 응답에 없으면 `"CHAT"` 으로 읽는다
- `conversation-nav.tsx`: `purpose` 가 `CHECK` 인 줄의 제목 앞에 `Badge` 「살펴보기」 를 둔다. 검색과 묶음은 그대로다
- `conversation-session.tsx`: 지금 대화의 `purpose` 가 `CHECK` 면 머리 줄에 「지금 살펴보기」 단추를 둔다. 누르면 `startProactiveCheck(agentCode)` 를 부르고, 202 면 도는 turn 을 따라간다(이미 있는 관찰 경로를 쓴다). 거절은 단추 아래에 그린다

### 4. 오류 문구

- `PROACTIVE_CHECK_UNAVAILABLE` 의 문구를 더한다: 「지금은 이 에이전트로 살펴볼 수 없어요. 에이전트 화면에서 까닭을 확인해 주세요.」

### 5. 이 phase 를 검증하는 브라우저 검사 `test/browser/proactive-check.spec.ts`

- 시험 에이전트에 `terminal` 을 켜면 절에 「터미널」 같은 한국어 이름과 끄라는 안내가 보이고 단추가 꺼져 있다
- 허용된 toolset 과 스킬만 두면 단추가 켜지고, 누르면 `/chat/<UUID>` 로 가서 시작 알림 줄과 결과 답(원문 링크 하나)이 보인다
- 사이드바 목록의 그 대화 줄에 「살펴보기」 배지가 보인다
- 점검 대화 머리 줄의 「지금 살펴보기」 를 누르면 같은 대화에 시작 알림 줄이 하나 더 생긴다
- 검사 끝에서 바꾼 toolset 과 스킬을 되돌린다

## 검증

```bash
# cwd: web/
pnpm typecheck
pnpm build
작업 공간 밖의 브라우저 검사 대기 스크립트
pnpm test:browser proactive-check
```

```bash
# cwd: 저장소 root
scripts/quality.sh check
grep -rn 'style={{' web/src/
```

기대값: 앞의 명령은 종료 코드 0. `grep` 은 아무것도 내지 않는다(종료 코드 1). 전체 브라우저 검사는 PR 의 CI `browser-mobile`, `browser-desktop` 이 돌린다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/app/api/agents/[code]/proactive-check/route.ts` | 신규 |
| `web/src/app/api/agents/[code]/proactive-check/runs/route.ts` | 신규 |
| `web/src/lib/proactive-check.ts` | 신규 |
| `web/src/components/agent/agent-proactive-check-section.tsx` | 신규 |
| `web/src/components/agent/agent-detail-loader.tsx` | 수정 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `web/src/components/shell/conversations-provider.tsx` | 수정 |
| `web/src/components/shell/conversation-nav.tsx` | 수정 |
| `web/src/components/chat/conversation-session.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `test/browser/proactive-check.spec.ts` | 신규 |
