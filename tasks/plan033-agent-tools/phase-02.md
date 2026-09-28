# Phase 02. 에이전트 상세에 「도구」 절을 둔다

**Execution profile**: standard

## 목표

`/agents/{code}` 에서 그 에이전트의 도구를 켜고 끈다. 주인은 주인 등급을, `ADMIN` 은 전부를 바꾼다.

**범위 외**: backend 는 phase 01 에서 끝났다. 스킬 올리기는 다른 계획이다.

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 「에이전트 도구」 와 「에이전트 화면」, `docs/flow.md` 「에이전트 도구를 고를 때」 의 「갈리는 지점」

- **에이전트 메뉴 통합이 main 에 들어간 뒤에 시작한다.** 상세 화면(`web/src/app/agents/[code]/page.tsx`)과 `ADMIN` 관리 절이 그 작업에서 생긴다. 시작 전에 `git merge origin/main` 을 하고 지금 모양을 읽는다
- 웹 API 경로는 기존 `web/src/app/api/agents/[code]/persona/route.ts` 와 `web/src/app/api/admin/agents/[code]/route.ts` 가 본보기다. 브라우저는 Control Plane 토큰을 갖지 않는다(`web/AGENTS.md`)
- 새 문구는 기존 에이전트 설정 화면처럼 해요체로 쓴다

## 의도 메모

- 목록은 등급별로 나눠 보인다. 바꿀 수 없는 도구는 누를 수 없게 두고 「관리자만 켤 수 있어요」 를 보인다
- 관리자 등급을 켤 때 확인 창을 띄운다. 무엇에 닿는지(예: 「홈서버 파일과 셸에 닿아요」) 한 줄과 함께 묻는다. 허락은 이때 한 번이다
- `GROUP` 에이전트에서는 셸·파일 계열을 누를 수 없게 두고 「그룹 공개 에이전트에는 켤 수 없어요」 를 보인다
- 저장은 누를 때마다 한다. 실패하면 서버가 다시 읽은 목록으로 되돌린다
- 저장 실패 뒤 `GET` 으로 현재 목록을 다시 읽는다. `AGENT_TOOLS_NOT_APPLIED` 의 `missingToolsets` 에 든 도구에는 「이 도구는 profile 설정에서 막혀 있어요. 관리자에게 알려 주세요.」 를 보인다
- `ADMIN` 이 다른 사람의 비공개 에이전트를 볼 때는 관리 절 안에서 `/api/v1/admin/agents/{code}/tools` 로 다룬다

## 작업 항목

### 1. 웹 API 경로

`web/src/app/api/agents/[code]/tools/route.ts` 와 `web/src/app/api/admin/agents/[code]/tools/route.ts` 에 `GET`, `PUT` 을 둔다

### 2. 도구 절

`web/src/components/agent/agent-tools-section.tsx` 를 만들고 상세 화면에 붙인다. 오류 코드 `AGENT_TOOLS_REQUIRE_PRIVATE`, `AGENT_TOOLS_NOT_APPLIED` 의 안내를 `web/src/components/error-message.ts` 에 더한다

### 3. 이 phase 를 검증하는 브라우저 검사 `test/browser/agent-tools.spec.ts`

- 정상: 주인이 `web` 을 켜면 다시 열어도 켜져 있다. `ADMIN` 이 비공개 에이전트에 `terminal` 을 켤 때 확인 창이 뜬다
- 실패 쪽: 주인 화면에서 `terminal` 을 누를 수 없다. 그룹 공개 에이전트에서 셸·파일 계열을 누를 수 없다. 저장 뒤 재조회에서 요청한 도구가 빠지면 실패 안내와 다시 읽은 토글 상태를 보인다

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 새 워크트리라 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

- 여섯 명령이 통과한다
- 모두 통과하면 `tasks/plan033-agent-tools/index.json` 의 `status` 를 `completed` 로, `current_phase` 를 `2` 로 바꾼다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/app/api/agents/[[]code]/tools/route.ts` | 신규 |
| `web/src/app/api/admin/agents/[[]code]/tools/route.ts` | 신규 |
| `web/src/components/agent/agent-tools-section.tsx` | 신규 |
| `web/src/app/agents/[[]code]/page.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/lib/agent.ts` | 수정 |
| `test/browser/agent-tools.spec.ts` | 신규 |
| `test/browser/fixtures.ts` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `tasks/plan033-agent-tools/index.json` | 수정 |
