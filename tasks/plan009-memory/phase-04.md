# Phase 04. career 의 내장 memory 를 옮기고 그 toolset 을 끈다

**Execution profile**: standard

## 목표

`career` profile 의 `USER.md` 내용을 Control Plane 의 `memory` 표로 옮긴다.
옮긴 뒤 그 profile 의 `memory` toolset 을 끌 것을 정리해 넘긴다.

**범위 외**:
홈서버의 profile 설정을 고치는 것은 별도 저장소 `fos-home-infra` 가 소유한다.
이 phase 는 옮길 내용을 넣는 것과 무엇을 꺼야 하는지를 적는 데까지 한다.

## 컨텍스트

ADR-003 이 「Hermes 에게 memory 조회 도구를 주지 않는다」 고 정했는데
`career` 의 운영이 그것과 어긋나 있었다.

| profile | `memory` toolset | `memories/` |
| --- | --- | --- |
| `bifos` | `disabled_toolsets` 에 있다 | 없다 |
| `career` | 켜져 있다 | `USER.md` 586 바이트 |

**`MEMORY.md` 는 2026년 9월 17일에 비웠다.**
자동으로 쌓인 14개 항목 10,192 바이트였고 내용이 대부분 지난 세션의 작업 로그였다.
커밋 해시와 worktree 경로와 `[score=...]` 메타데이터가 본문에 섞여 있었고,
날짜가 모두 6월 11일부터 17일 사이라 석 달째 갱신되지 않은 상태였다.
커리어 판단에 쓸 사실은 하나도 없었다.

비운 효과를 측정했다.

| 시점 | 입력 토큰 | system prompt 글자 | memory 글자 |
| --- | --- | --- | --- |
| 비우기 전 | 12,388 | 22,799 | 9,461 |
| 비운 뒤 | 9,551 | 13,501 | 163 |

2,837 토큰, 22.9% 가 줄었다.

그래서 이 phase 가 옮길 것은 `USER.md` 뿐이다.
그 파일은 이름, 시간대, 답변 방식 선호를 담고 있고 지금도 유효한 사실이다.

**근거 문서**: `docs/adr/ADR-003-memory-권한은-주입으로-강제한다.md`,
`docs/adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md`,
`docs/hermes-integration.md` 의 「도구 정의가 더 크다」 절

## 의도 메모

- `MEMORY.md` 를 옮기지 않기로 한 것은 사람이 내용을 읽고 판단한 결과다.
  자동으로 쪼개 옮기는 스크립트를 만들지 않는다.
- `USER.md` 파일 자체는 지우지 않는다.
  toolset 을 끄면 에이전트가 읽지 않으므로 지울 이유가 없다.
- 옮긴 항목의 `scope` 는 전부 `USER` 다.
  `career` 는 한 사람이 쓰는 에이전트이고, 그 내용도 그 사람에 관한 것이다.

## Blocked 조건

`ssh homeserver` 로 붙지 못하면 `PHASE_BLOCKED: 홈서버에 붙지 못한다` 를 내고 멈춘다.
주소와 계정은 이 저장소에 없다. 별칭이 `~/.ssh/config` 에 있다.

## 작업 항목

### 1. 옮길 내용을 읽어 온다

```bash
# cwd: 저장소 root
ssh homeserver 'cat ~/.hermes/profiles/career/memories/USER.md'
```

읽은 것을 이 저장소에 커밋하지 않는다. 공개 저장소이고 그 내용은 개인 기록이다.
`.omc/` 는 `.gitignore` 에 있으므로 그 아래에 두는 것은 괜찮다.

`MEMORY.md` 는 비어 있다. `# Long-Term Memory` 한 줄만 남아 있다.
그 파일에서는 옮길 것이 없다.

### 2. 사실 하나가 한 줄이 되도록 나눈다

`USER.md` 는 이미 목록 형태라 거의 그대로 나뉜다.
담긴 것은 이름과 부를 이름, 시간대, Discord 핸들, 그리고 답변 방식 선호 넷이다.

**Discord 핸들처럼 다른 경로에서만 쓰이는 식별자는 옮기지 않는다.**
이 비서는 웹으로 쓰고 Discord 를 지나지 않는다.
옮기지 않은 줄과 그 이유를 보고에 적는다.

`scope` 는 전부 `USER` 로 한다.
`FAMILY` 로 옮길 것이 있다고 판단되면 **옮기지 말고 보고에 적는다.**
`FAMILY` 는 다른 구성원의 실행에도 주입되므로 사람이 정한다.

### 3. API 로 넣는다

`POST /api/v1/memories` 로 하나씩 넣는다.
손으로 적는 경로이므로 바로 `ACCEPTED` 가 된다.

넣은 개수를 확인한다.

```bash
# cwd: 저장소 root
curl -s "<Control Plane 주소>/api/v1/memories" -H "Authorization: Bearer <토큰>" | jq 'length'
```

### 4. 끌 설정을 정리해 넘긴다

`fos-home-infra` 에 넘길 내용을 완료 보고에 적는다. **이 phase 에서 직접 고치지 않는다.**

`~/.hermes/profiles/career/config.yaml` 의 `agent.disabled_toolsets` 에 넣을 것이다.

측정한 도구 정의 크기다. 아래 다섯은 `career-os` 스킬이 부르지 않는 것을 확인했다.

| toolset | 도구 정의 바이트 |
| --- | --- |
| `delegation` | 3,740 |
| `memory` | 3,178 |
| `browser-use` | 3,048 |
| `code_execution` | 2,955 |
| `vision` | 845 |

`terminal` 과 `file` 과 `skills` 는 남긴다.
`career-os` 가 `bun` 을 부르고 파일을 읽으며 스킬을 부른다.

`web` 은 빼지 말고 보고에만 적는다.
`career-os` 문서에 `web_search` 를 부르는 곳은 없었으나 공고를 찾을 때 쓸 수 있다.

`sync-profile` 이 쓰는 브라우저는 Hermes 의 `browser-use` 가 아니라
외부 스크립트이고 `terminal` 로 돌리므로, `browser-use` 를 꺼도 그 스킬은 동작한다.

### 5. 이 phase 를 검증하는 테스트

이 phase 는 코드를 바꾸지 않는다. 자료를 옮기고 설정 변경안을 적는다.
그래서 판정 기준을 실제 왕복으로 둔다.

옮긴 사실 하나를 골라 `career` 에게 묻는다.
답변 방식 선호처럼 `USER.md` 에만 있던 것을 고른다.

1. 옮기고 나서 웹 화면에서 `career` 에게 그것을 묻는다
2. 답하면 성공이다. Control Plane 이 주입한 것으로 답한 것이다.
   `memory` toolset 은 아직 켜져 있지만 `MEMORY.md` 가 비어 있고
   `USER.md` 에는 그 답의 근거가 되는 줄이 남아 있으므로,
   **어느 쪽으로 답했는지를 가르려면 3번이 필요하다**
3. `fos-home-infra` 가 toolset 을 끈 뒤 같은 것을 다시 묻는다.
   그때도 답하면 Control Plane 주입으로 답한 것이 확정된다

**이 phase 는 1번과 2번까지 한다. 3번은 코디네이터가 한다.**
고른 질문과 2번의 답을 완료 보고에 그대로 적는다.

입력 토큰도 함께 측정해 적는다. 끄기 전과 뒤를 견주기 위해서다.

```bash
# cwd: 저장소 root
ssh homeserver 'docker exec -e HERMES_HOME=/opt/data/profiles/career hermes hermes prompt-size --platform api_server --json'
```

기준값은 이 phase 시작 시점의 9,551 토큰이다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
```

옮긴 항목이 실제로 주입되는지 본다.
가짜 Hermes 가 받은 `instructions` 에 옮긴 내용이 들어 있어야 한다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ContextAssemblerTest*'
```

## Critical Files

| 파일 | 변경 |
|---|---|
| (코드 변경 없음) | 이 phase 는 자료를 옮기고 설정 변경안을 적는다 |

## 끝낸 뒤

`tasks/plan009-memory/index.json` 의 이 phase 를 `completed` 로 바꾸고
plan 의 `status` 를 `completed` 로 바꾼다.
