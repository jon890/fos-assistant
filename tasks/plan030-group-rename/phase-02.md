# Phase 02. 화면과 브라우저 검사를 group 으로 바꾼다

**Execution profile**: standard

## 목표

web 의 타입, 상수, 보내는 값, 화면 문구를 phase 01 이 바꾼 Control Plane 과 맞춘다.
사용자가 보는 「가족 공개」, 「가족 공용」 이 「그룹 공개」, 「그룹 공용」 이 된다.

**범위 외**

- backend 와 `test/e2e/` 는 phase 01 이 바꿨다. 이 phase 는 고치지 않는다
- `credential_scope` 의 표시 문구 「가족 공유 credential」(`web/src/components/admin/agent-form.tsx`)은 바꾸지 않는다. 그룹마다 credential 을 나눌지는 `docs/prd.md` 의 미결 항목이다
- 사이트 설명 `web/src/app/layout.tsx` 의 「가족이 함께 쓰는 개인 AI 비서」 는 제품이 누구를 위한 것인지 말하는 문장이라 그대로 둔다
- 글꼴 `font-family` 는 이 작업과 무관하다

## 컨텍스트

**근거 문서**: `docs/data-schema.md` 의 `agent.visibility`(`PRIVATE` 또는 `GROUP`)와 `memory.scope`(`USER` 또는 `GROUP`), `docs/prd.md` 첫 절

- `AGENTS.md` 「용어」 표의 「사용자들이 모인 단위」 줄
- `web/AGENTS.md`. `pnpm build` 의 자리표시자 환경 변수와 브라우저 검사의 함정이 거기 있다

**지금 모양** (구현 전에 다시 읽는다)

| 자리 | 지금 |
| --- | --- |
| `web/src/lib/agent.ts` | `visibility: "PRIVATE" \| "FAMILY"` 두 곳, `export const FAMILY_VISIBILITY: AdminAgent["visibility"] = "FAMILY";` |
| `web/src/app/admin/agents/agent-admin-panel.tsx` | `FAMILY_VISIBILITY` import, `confirmFamilyVisibility()`, 동작 이름 `"family"`, 안내 「공개 범위는 보안 설정이다. 가족 공개로 바꾸면 모든 사용자가 이 에이전트로 대화할 수 있다.」 |
| `web/src/components/admin/agent-card.tsx` | `AgentAction = "private" \| "family" \| …`, 주석 `` `family` 는 카드가 아니라 공개 확인 창이 보낸다 ``, 배지 「가족 공개」, 단추 「가족 공개로 변경」 |
| `web/src/components/admin/visibility-confirm.tsx` | 제목 「{agent.name} 에이전트를 가족에게 공개할까요?」, 단추 「가족 공개」 |
| `web/src/components/admin/agent-form.tsx` | `<option value="FAMILY">가족 공개</option>` |
| `web/src/components/memory/memory-form.tsx` | `<option value="FAMILY">가족 공용</option>` |
| `web/src/components/memory/memory-list.tsx` | `scope: "USER" \| "FAMILY"`, 변수 `family`, 제목 「우리 가족이 함께 아는 것」, 빈 상태 「아직 가족 공용 Memory가 없습니다.」 |
| `web/src/components/error-message.ts` | 주석 `something a family member can act on` |

## 의도 메모

- 화면 문구도 「그룹」 으로 바꾼다. 코드, 문서, 화면이 같은 낱말을 쓰게 하려는 사용자 결정이다
- 공개 확인 창의 조사는 「그룹에 공개할까요?」 로 쓴다
- 안내 문장은 「그룹 공개로 바꾸면 그룹의 모든 사용자가 이 에이전트로 대화할 수 있다.」 로 쓴다
- 브라우저 검사의 가짜 사용자 이름 「가족 사용자」 는 사람의 표시 이름이라 이 작업과 무관하다. 다만 `test/browser/starters.spec.ts` 의 「가족 구성원」 은 `AGENTS.md` 가 쓰지 않는 말 「구성원」 을 쓰므로 「그룹 사용자」 로 바꾼다

## 작업 항목

### 1. 타입과 상수를 바꾼다

- `web/src/lib/agent.ts`: `"PRIVATE" | "GROUP"`, `GROUP_VISIBILITY = "GROUP"`
- `web/src/components/memory/memory-list.tsx`: `scope: "USER" | "GROUP"`, 변수 `family` → `group`
- `web/src/components/admin/agent-card.tsx`: `AgentAction` 의 `"family"` → `"group"`, 주석도 같다
- `web/src/app/admin/agents/agent-admin-panel.tsx`: `GROUP_VISIBILITY`, `confirmGroupVisibility()`, 동작 이름 `"group"`
- `web/src/components/error-message.ts` 주석을 한국어로 바꾼다(「Control Plane 의 오류 코드를 사용자가 행동할 수 있는 문구로 바꾼다」 뜻)

### 2. 보내는 값과 문구를 바꾼다

- 두 `<option value="FAMILY">` 를 `value="GROUP"` 으로. 글자는 「그룹 공개」, 「그룹 공용」
- 배지와 단추: 「그룹 공개」, 「그룹 공개로 변경」
- 확인 창: 「{agent.name} 에이전트를 그룹에 공개할까요?」, 단추 「그룹 공개」
- Memory 화면: 제목 「우리 그룹이 함께 아는 것」, 빈 상태 「아직 그룹 공용 Memory가 없습니다.」
- 관리 화면 안내 문장은 의도 메모의 문장으로

### 3. 이 phase 를 검증하는 브라우저 검사를 따라 바꾼다

- `test/browser/fixtures.ts`: `PERSONA_FAMILY_AGENT_CODE` → `PERSONA_GROUP_AGENT_CODE`, 에이전트 code `browserpersonafamily` → `browserpersonagroup`, 그 profile 이름과 key 이름도 같은 규칙으로, 표시 이름 「가족 성격 비서」 → 「그룹 성격 비서」, `visibility: "PRIVATE" | "GROUP"`, 주석의 `FAMILY` 와 「가족 공개」
- `test/browser/admin.spec.ts`, `identity.spec.ts`, `memory.spec.ts`, `persona.spec.ts`, `starters.spec.ts`, `legacy-conversation-url.spec.ts`: 비교하는 값과 문구(`"FAMILY"`, 「가족 공개」, 「가족 공개로 변경」, 「가족에게 공개할까요?」, 「가족 공용」, 「우리 가족이 함께 아는 것」, 「가족 성격 비서 소개」, 「가족 성격 비서 성격」)와 검사 제목, 주석
- 정상 경로: 관리자가 에이전트를 「그룹 공개로 변경」 → 확인 창 → 배지가 「그룹 공개」 가 되는 기존 `admin.spec.ts` 검사가 새 문구로 통과한다
- 실패 쪽: `MEMBER` 역할에게 「그룹 공용」 선택지가 보이지 않는 기존 `memory.spec.ts` 검사가 새 문구로 통과한다

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 새 워크트리라 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
grep -rnE 'FAMILY|[Ff]amily' web/src test/browser
grep -rn '가족' web/src test/browser
```

- 여섯 명령이 모두 통과한다
- 첫 grep 은 `globals.css` 와 `identity.spec.ts` 의 `font-family`, `fontFamily` 만 가리킨다
- 둘째 grep 은 `layout.tsx` 의 사이트 설명, `agent-form.tsx` 의 「가족 공유 credential」, 브라우저 검사의 가짜 사용자 이름 「가족 사용자」 만 가리킨다
- 모두 통과하면 `tasks/plan030-group-rename/index.json` 의 `status` 를 `completed` 로, `current_phase` 를 `2` 로 바꾼다

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `web/src/lib/agent.ts` | 수정 |
| `web/src/app/admin/agents/agent-admin-panel.tsx` | 수정 |
| `web/src/components/admin/agent-card.tsx` | 수정 |
| `web/src/components/admin/visibility-confirm.tsx` | 수정 |
| `web/src/components/admin/agent-form.tsx` | 수정 |
| `web/src/components/memory/memory-form.tsx` | 수정 |
| `web/src/components/memory/memory-list.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
| `test/browser/*.spec.ts` | 수정 |
