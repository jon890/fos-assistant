# Phase 02. 관리 화면의 문구를 고치고 검사를 모든 화면으로 넓힌다

**Execution profile**: standard

## 목표

`ADMIN` 이 보는 관리 화면과 에이전트 설정 화면의 문구를 `review-table.md` 대로 고치고, phase 01 의 문구 검사가 모든 화면을 보게 한다.

**범위 외**: 코드 주석, docs, backend 문구. 동작과 배치.

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 「에이전트 화면」 절

- 정본은 같은 폴더의 `review-table.md`, 규칙은 `web/AGENTS.md` 「화면 문구」 절이다
- **에이전트 메뉴 통합(목록 하나와 상세의 관리 절)이 이 계획보다 먼저 main 에 들어간다.** `app/admin/agents/agent-admin-panel.tsx` 의 문구는 옮겨진 자리(에이전트 목록과 상세의 관리 절)에서 찾는다
- 이 phase 가 다루는 표의 절: `app/admin/**`, `components/admin/**`, `components/agent/**`, 관리 용어 결정(모델 제공사, 에이전트 연결 주소, profile, AI 계정 사용 범위)

## 의도 메모

- 「Hermes profile」 은 관리 화면에서 「profile」 로 쓴다. 저장소 용어라 번역하지 않는다
- AI 계정 사용 범위의 선택지는 「그룹 공유」, 「전용」 이다. 저장 값(`SHARED_HOUSEHOLD`, `DEDICATED`)은 바꾸지 않는다

## 작업 항목

### 1. 표대로 문구를 고친다

위 절의 「바꿀 것」 과 관리 용어 결정을 반영한다

### 2. 검사가 비교하는 문구를 따라 고친다

`test/browser/**` 가운데 관리 화면과 에이전트 설정 검사를 새 문구로 고친다

### 3. 이 phase 를 검증하는 단위 검사

`test/unit/ui-copy.test.ts` 의 허용 목록을 비워 `web/src` 전체에서 평서체 문구가 0 건이게 한다

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
grep -rn -E 'Hermes profile|provider|credential 범위|실행 나무|설정 지문' web/src --include=*.tsx | grep -v '^\s*//'
```

- 여섯 명령이 통과한다
- 마지막 grep 은 식별자와 주석만 가리키고 화면 문구는 가리키지 않는다
- 모두 통과하면 `tasks/plan032-web-copy/index.json` 의 `status` 를 `completed` 로, `current_phase` 를 `2` 로 바꾼다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/app/admin/**/*.tsx` | 수정 |
| `web/src/components/admin/*.tsx` | 수정 |
| `web/src/components/agent/*.tsx` | 수정 |
| `test/browser/*.spec.ts` | 수정 |
| `test/unit/ui-copy.test.ts` | 수정 |
| `tasks/plan032-web-copy/index.json` | 수정 |
