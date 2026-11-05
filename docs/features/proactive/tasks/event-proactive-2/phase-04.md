# Phase 04. 기능 파일과 스키마 문서

**Execution profile**: standard

## 목표

지금 동작하는 알림과 켜고 끄는 흐름을 기능 파일과 스키마 문서에 적는다.

**범위 외**: 코드(phase 01~03). 설계와 스펙은 바뀐 결정이 있을 때만 고친다.

## 컨텍스트

문서 자리는 루트 `AGENTS.md` 의 「읽기 순서」 가 정한다. 기능 파일은 지금 동작을, 설계는 구현 방법을 갖는다.

**요구사항**: REQ5, REQ6, REQ7, REQ10, REQ11

**근거 문서**: `docs/features/proactive/spec.md`, `docs/features/proactive/design.md`

## 의도 메모

- 기능 파일에 설계 표를 다시 적지 않는다. 동작과 갈리는 지점만 적고 설계를 가리킨다
- 계획 폴더는 PR 본문에 인수 검증 계획과 결과를 옮긴 뒤 같은 PR 에서 지운다

## 작업 항목

### 1. `docs/features/proactive.md` 의 「사건 감지」

「지금 들어간 것은 확인과 실행이다」 줄을 지금 동작(알림, 조용한 시간, 켜고 끄기)으로 바꾸고, 알림·아침 묶음·다시 연결 알림·켜기 조건과 화면 소절을 한 줄씩 더한다. 「먼저 살펴보기 절」 에 새 소절을 적는다.

### 2. `docs/features/attention.md` 의 「알림 종류」 표

사건 감지의 두 원인(살펴본 결과, 다시 연결 필요)과 받는 사람, 누르면 갈 곳을 더한다.

### 3. `backend/docs/data-schema.md` 의 `agent_connector_binding`

상태 칸에 다시 연결 알림 표시와 미룬 알림을 더한다. `notification` 의 종류·갈 곳 칸 설명에 새 값이 필요하면 더한다.

## 검증

```bash
node --test test/unit/doc-files.test.ts test/unit/doc-code-references.test.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/features/proactive.md` | 수정 |
| `docs/features/attention.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/proactive/tasks/event-proactive-2/` | 삭제(PR 본문에 인수 검증 계획과 결과를 옮긴 뒤) |
