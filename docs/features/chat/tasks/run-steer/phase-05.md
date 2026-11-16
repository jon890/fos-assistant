# Phase 05. 계약 문서와 기능 파일

**Execution profile**: standard

## 목표

Hermes 계약 문서와 기능 파일이 steer 를 부르는 서버 동작을 적는다.

**범위 외**: 화면 동작과 E2E 가짜 Hermes(PR2), 운영 왕복 확인(배포 뒤 코디네이터, REQ10).

## 컨텍스트

**요구사항**: REQ9

**근거 문서**: `docs/features/chat/spec.md`, `docs/features/chat/design.md`, `docs/adr/ADR-20261116-run-steer.md`

- 계약 문서: `hermes/docs/hermes-contract.md` 의 「Runs API」 표, 「도는 실행에 지시를 더하는 `steer`」(지금 v0.21.5 기준이며 「우리는 아직 부르지 않는다」).
- 기능 파일: `docs/features/chat.md` 의 「대기열과 중지」.
- 공개 저장소다. 운영 주소·명령·경로를 적지 않는다.

## 작업 항목

### 1. `hermes/docs/hermes-contract.md`

steer 절을 v2026.9.24 기준으로 고친다: 받는 때(`running` 만, 승인 대기·중지 중 409), 본문, 넣는 두 때(다음 API 호출 직전 가장 최근 `tool` 행 뒤, 도구 묶음 직후), `display_kind: "steer"`, `pending_steer`(모든 종료 사건과 조회), 중지 시 버림, 위임 자식에 안 감, 「Control Plane 이 부른다(설정이 켜져 있을 때)」, 소스로 읽었고 운영 왕복은 배포 뒤 확인. Runs API 표와 「실행을 시작한 뒤 바꿀 수 있는 것」 문장을 맞춘다. 가짜 Hermes 줄은 「Java 가짜(`StubHermesRunsClient`)는 steer 를 안다, E2E 가짜는 아직」 으로 둔다.

### 2. `docs/features/chat.md`

「응답 중 대기열」 아래에 「도는 실행에 끼워 넣기」 절: 설정, 끼우는 조건과 turn 종류, 말풍선 세 상태, 돌아온 글과 중지, 「끼우는 중」 동안 다음 turn 을 붙잡는 것, 다시 생성 제한. 화면은 PR2 에서 더한다고 적지 않는다(기능 파일은 지금 동작만 적는다). 결정은 ADR-20261116 / run-steer 를 링크하고 옮겨 적지 않는다.

### 3. 문서 검사

`test/repo/` 의 문서 시험과 ADR 링크 검사를 돌린다.

## 검증

```bash
npx --yes tsx --test test/repo/doc-files.test.ts
node /Users/kbt/personal/fos-skills/skills/plan/scripts/adr-docs.ts check --changed-since origin/main
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/docs/hermes-contract.md` | 수정 |
| `docs/features/chat.md` | 수정 |
