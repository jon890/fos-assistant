# Phase 04. career 의 내장 memory 를 옮기고 그 toolset 을 끈다

**Execution profile**: standard

## 목표

`career` profile 이 들고 있는 Hermes 내장 memory 의 내용을 Control Plane 의 `memory` 표로 옮긴다.
옮긴 뒤 그 profile 의 `memory` toolset 을 끄고, 실제로 물어 확인한다.

**범위 외**:
홈서버의 profile 설정을 고치는 것은 별도 저장소 `fos-home-infra` 가 소유한다.
이 phase 는 옮길 내용을 뽑아 넣는 것과 무엇을 꺼야 하는지를 적는 데까지 한다.
설정 변경은 완료 보고에 적어 코디네이터가 그 저장소에 위임한다.

## 컨텍스트

ADR-003 이 「Hermes 에게 memory 조회 도구를 주지 않는다」 고 정했는데
실제 운영이 그것과 어긋나 있다.

| profile | `memory` toolset | `memories/` |
| --- | --- | --- |
| `bifos` | `disabled_toolsets` 에 있다 | 없다 |
| `career` | 켜져 있다 | `MEMORY.md` 10,192 바이트, `USER.md` 586 바이트 |

한쪽만 지키는 규칙은 지켜지지 않는 규칙이다.

내장 memory 는 profile 단위라 개인과 가족 공용을 나누지 못한다.
가족 중 한 사람만 보아야 할 것과 함께 보아야 할 것이 같은 파일에 섞인다.

측정한 것이 있다. `career` 의 `memory` 도구 정의가 3,178 바이트다.
toolset 을 끄면 그만큼도 함께 빠진다.

**근거 문서**: `docs/adr/ADR-003-memory-권한은-주입으로-강제한다.md`,
`docs/adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md`,
`docs/hermes-integration.md` 의 「도구 정의가 더 크다」 절

## 의도 메모

- 자동으로 옮기는 스크립트를 만들지 않는다.
  `MEMORY.md` 는 사람이 읽고 쓴 글이라 한 줄이 곧 한 사실이 아니다.
  기계로 쪼개면 문맥이 끊긴 조각이 남는다.
- 옮긴 뒤 `memories/` 파일을 지우지 않는다.
  toolset 을 끄면 에이전트가 읽지 않으므로 지울 이유가 없고,
  옮긴 것이 빠졌을 때 돌아가 볼 원본이 필요하다.

## Blocked 조건

`ssh homeserver` 로 붙지 못하면 `PHASE_BLOCKED: 홈서버에 붙지 못한다` 를 내고 멈춘다.
주소와 계정은 이 저장소에 없다. 별칭이 `~/.ssh/config` 에 있다.

## 작업 항목

### 1. 옮길 내용을 읽어 온다

```bash
# cwd: 저장소 root
ssh homeserver 'cat ~/.hermes/profiles/career/memories/MEMORY.md'
ssh homeserver 'cat ~/.hermes/profiles/career/memories/USER.md'
```

읽은 것을 저장소 밖에 둔다. **이 저장소에 커밋하지 않는다.**
공개 저장소이고 그 내용은 개인 기록이다.

### 2. 사실 하나가 한 줄이 되도록 나눈다

사람이 읽고 판단한다. 기계로 쪼개지 않는다.

나눈 각 줄에 범위를 정한다.

| 어떤 사실인가 | `scope` |
| --- | --- |
| 이 사람의 경력, 선호, 이직 우선순위 | `USER` |
| 가족이 함께 아는 것 | `FAMILY` |

`career` 의 기록은 대부분 `USER` 일 것이다.
어느 쪽인지 애매한 줄은 옮기지 말고 보고에 적는다. **짐작해서 넣지 않는다.**

### 3. API 로 넣는다

`POST /api/v1/memories` 로 하나씩 넣는다.
손으로 적는 경로이므로 바로 `ACCEPTED` 가 된다.

넣은 뒤 개수를 확인한다.

```bash
# cwd: 저장소 root
curl -s <Control Plane 주소>/api/v1/memories -H "Authorization: Bearer <토큰>" | jq 'length'
```

### 4. 끌 설정을 적는다

`fos-home-infra` 에 넘길 내용을 완료 보고에 적는다. **이 phase 에서 직접 고치지 않는다.**

`~/.hermes/profiles/career/config.yaml` 의 `agent.disabled_toolsets` 에 넣을 것이다.

```yaml
agent:
  disabled_toolsets:
    - memory
```

측정한 것으로는 `career` 에서 아래 넷을 더 빼도 된다.
`career-os` 스킬이 부르지 않는 것을 확인했다.

| toolset | 도구 정의 바이트 |
| --- | --- |
| `memory` | 3,178 |
| `delegation` | 3,740 |
| `browser-use` | 3,048 |
| `code_execution` | 2,955 |
| `vision` | 845 |

`terminal` 과 `file` 과 `skills` 는 남긴다.
`career-os` 가 `bun` 을 부르고 파일을 읽으며 스킬을 부른다.

`web` 은 판단이 필요하다. `career-os` 문서에 `web_search` 를 부르는 곳이 없었으나
공고를 찾을 때 쓸 수 있다. 빼지 말고 보고에 적는다.

### 5. 이 phase 를 검증하는 테스트

자동 테스트로 판정할 수 없는 부분이 있다.
Hermes profile 설정은 이 저장소 밖이고, 옮긴 내용이 맞는지는 사람이 읽어야 안다.

**그래서 판정 기준을 실제 왕복으로 둔다.** 아래를 순서대로 한다.

1. `MEMORY.md` 에만 있던 사실 하나를 고른다. 그것을 `career` 에게 묻는다
2. 옮기기 전에 답하는 것을 확인한다. 내장 memory 로 답하는 것이다
3. 옮기고 toolset 을 끈 뒤 같은 것을 다시 묻는다
4. 여전히 답하면 성공이다. Control Plane 이 주입한 것으로 답한 것이다
5. 답하지 못하면 그 사실이 옮겨지지 않았거나 주입되지 않은 것이다. 무엇이 빠졌는지 적는다

세 번째 단계는 설정을 고친 뒤라야 하므로 `fos-home-infra` 작업이 끝난 뒤다.
**이 phase 는 1번과 2번까지 하고, 3번부터는 코디네이터가 한다.**
1번에서 고른 질문과 2번의 답을 완료 보고에 그대로 적는다.

입력 토큰도 함께 측정해 적는다. 끄기 전과 뒤를 견주기 위해서다.

```bash
# cwd: 저장소 root
ssh homeserver 'docker exec -e HERMES_HOME=/opt/data/profiles/career hermes hermes prompt-size --platform api_server --json'
```

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
