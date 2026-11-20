# Phase 02. 연결 절이 빠진 스킬과 고치는 방법을 안내한다

**Execution profile**: standard

## 목표

에이전트 상세의 「이 에이전트가 쓰는 연결」 절이 빠진 스킬이 있는 바인딩 아래에 스킬 이름과 REQ9 문구를 보인다.

**범위 외**: 응답 칸(phase 01).

## 컨텍스트

**요구사항**: REQ9

**근거 문서**: `docs/features/agent-skill/spec.md` 의 REQ9, DECISION10, `docs/features/agent-skill/design.md` 의 「PR2 의 경계」, `docs/features/connector.md` 의 「화면」

- 화면은 `web/src/components/agent/agent-connections-section.tsx` 의 `ConnectionRow`, 타입은 `web/src/lib/agent-connection.ts` 의 `AgentConnectionView` 다.
- 문구 고르기는 순수 함수로 `lib/agent-connection.ts` 에 두고 `web/test/unit/` 의 단위 테스트로 본다. 브라우저 테스트는 가짜 대시보드가 `skipped_skills` 를 내지 않아 이 문구를 만들 수 없다.

## 의도 메모

- 문구는 스킬마다 고른다. 이름 겹침 → 실행 환경 없음(`sandbox_unregistered` 나 `sandbox_env_missing`) → 터미널(까닭이 `terminal_off` 만일 때) 순서로 해당하는 것을 모두 보인다. 터미널 문구를 「꺼짐만」 일 때로 묶는 것은 터미널만 켜고 설치되지 않는 헛걸음을 막는 DECISION10 의 뜻이다.
- 모르는 까닭만 있는 스킬은 이름만 보인다.
- 「스킬 도구를 켜세요」 안내는 설치될 스킬(`skills` 에서 빠진 이름을 뺀 것)이 있을 때만 띄운다.

## 작업 항목

### 1. `web/src/lib/agent-connection.ts`

- `AgentConnectionView` 에 `skippedSkills: SkippedSkill[]`(`{name: string; reasons: string[]}`)를 더한다.
- `skippedSkillMessages(reasons: readonly string[]): string[]` 와 `installableSkills(view): string[]` 를 둔다.

### 2. `web/src/components/agent/agent-connections-section.tsx`

- `ConnectionRow` 가 `view.skippedSkills` 마다 `「이름」 스킬은 함께 설치되지 않았어요.` 와 문구를 보인다(`data-testid="agent-connection-skipped-skill"`).
- 스킬 도구 안내는 `installableSkills(view).length > 0 && skillsOff` 로 바꾼다.
- 떼기 뒤 줄을 바꿀 때 `skippedSkills: []` 로 비운다.
- `AgentConnectionView` 를 만드는 다른 곳(`connector-agent-chooser.tsx` 등)이 있으면 타입을 맞춘다.

### 3. 문서

- `docs/features/connector.md` 「화면」 표에 빠진 스킬 안내 줄을 더하고, 스킬 도구 안내 줄의 조건을 맞춘다.

### 4. 테스트

- `web/test/unit/agent-connection.test.ts`: 까닭 조합마다 문구와 순서(이름 겹침만, 실행 환경, 터미널만, 터미널과 env 없음, 셋 모두, 모르는 값), `installableSkills`.

## 검증

```bash
cd web && pnpm exec tsc --noEmit
cd web && pnpm exec eslint src/lib/agent-connection.ts src/components/agent/agent-connections-section.tsx
cd web && node --test test/unit/agent-connection.test.ts
```

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ8 | 가짜 대시보드가 `skipped_skills` 를 내는 붙이기 | `AgentConnectionControllerTest` 와 `ConnectorBindingDriftTest` 의 빠진 스킬 사례를 돌린다 | 연결 목록의 그 바인딩에 이름과 까닭, 옛 응답이면 빈 목록, 다시 설치가 빈 목록이면 비고 실패하면 옛 기록 | 백엔드 H2 테스트 | worker | 없음 | PR 전 |
| REQ8 | 응답 모양 | `HttpHermesConnectorClientTest` 를 돌린다 | 설치 응답 읽기가 칸 없음과 틀린 모양을 구분 | 백엔드 테스트 | worker | 없음 | PR 전 |
| REQ8 | 마이그레이션 | Draft CI 의 `backend-mysql` 결과를 본다 | 스키마와 엔티티가 맞다 | CI | worker | push | PR 뒤 |
| REQ9 | 까닭 조합 | `web/test/unit/agent-connection.test.ts` 를 돌린다 | REQ9 문구가 정한 순서로 해당하는 것만 나온다 | 웹 단위 테스트 | worker | 없음 | PR 전 |
| REQ9 | fos-agents 의 agent-skills 사본 배포 | 셸 조건이 안 맞는 에이전트에 붙이고 연결 절을 본다 | 빠진 스킬과 까닭 문구가 보인다 | 운영 | 코디네이터 | 배포: fos-agents 의 agent-skills 사본과 이 PR | PR 뒤 |

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/agent-connection.ts` | 수정 |
| `web/src/components/agent/agent-connections-section.tsx` | 수정 |
| `web/test/unit/agent-connection.test.ts` | 신규 |
| `docs/features/connector.md` | 수정 |
