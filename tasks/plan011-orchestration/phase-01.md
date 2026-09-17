# Phase 01. Hermes 의 orchestration 기능을 조사한다

**Execution profile**: deep

## 목표

세 방식을 실제로 확인해 견주고, 어느 것을 쓸지 정하는 ADR 을 쓴다.
**이 phase 는 구현하지 않는다.** 조사와 결정만 한다.

**범위 외**: 구현은 phase-02 가 한다. 그 전에 사용자가 ADR 을 받아들여야 한다.

## 컨텍스트

만들려는 구조다.

```
Chief → Orchestrator → Specialist / Worker → Reviewer / Synthesizer
```

Hermes 는 이미 여러 기능을 갖고 있다.
`hermes --help` 에 `kanban`, `delegation` toolset, `peer`, `project` 가 보인다.
그중 무엇이 우리가 필요한 것을 하는지 확인하지 않았다.

**확인하지 않은 것을 확인한 것처럼 적지 않는다.**
이 저장소에서 그렇게 해서 두 번 틀렸다.
스트리밍이 통과했다고 보고됐는데 운영에서 조각이 하나도 흐르지 않았고,
브라우저 테스트가 통과했다고 보고됐는데 전체로 돌리니 하나가 실패했다.
둘 다 가짜가 실제와 달라서 생긴 일이다.

**근거 문서**: `docs/adr/ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md`,
`docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md`,
`docs/hermes-integration.md`

## 의도 메모

- 세 방식 중 어느 것이 이길지 미리 정하지 않는다.
  조사 전에 결론을 적으면 그 결론에 맞는 근거만 모으게 된다.
- 비용 축을 반드시 본다.
  자식 실행의 토큰이 부모에 이미 포함되는지 모르면,
  비용을 두 번 세거나 자식 비용을 잃는다.

## Blocked 조건

`ssh homeserver` 로 붙지 못하면 `PHASE_BLOCKED: 홈서버에 붙지 못한다` 를 내고 멈춘다.
이 phase 는 실제 Hermes 에 물어보는 것이 전부라 문서만으로 할 수 없다.

## 작업 항목

### 1. Hermes 버전과 기능 목록을 확인한다

```bash
# cwd: 저장소 root
ssh homeserver 'docker exec hermes hermes --version'
ssh homeserver 'docker exec hermes hermes kanban --help'
ssh homeserver 'docker exec hermes hermes project --help'
ssh homeserver 'docker exec hermes hermes peer --help'
ssh homeserver 'docker exec hermes hermes plugins --help'
```

`delegation` toolset 이 어떤 도구를 주는지도 확인한다.

```bash
# cwd: 저장소 root
ssh homeserver 'docker exec -e HERMES_HOME=/opt/data/profiles/bifos hermes hermes tools list'
```

`career` 의 `delegation` 도구 정의가 3,740 바이트인 것을 앞서 측정했다.
그 안에 무엇이 들어 있는지 본다.

API server 가 무엇을 지원하는지도 본다.

```bash
# cwd: 저장소 root
ssh homeserver 'K=$(grep "^API_SERVER_KEY=" ~/.hermes/profiles/bifos/.env | cut -d= -f2-); docker exec hermes sh -c "curl -s http://127.0.0.1:8643/v1/capabilities -H \"Authorization: Bearer $K\""'
```

API 키를 출력하거나 커밋하지 않는다.
`bifos` 의 포트가 8643 이고 `career` 는 8652 다.

### 2. 자식 실행의 토큰이 부모에 포함되는지 확인한다

**이것이 이 phase 에서 가장 중요한 확인이다.**

`delegation` toolset 을 켠 profile 에서 하위 에이전트를 부르는 실행을 하나 돌린다.
그 실행의 `usage` 를 읽어, 하위 에이전트가 쓴 토큰이 그 합계에 들어 있는지 본다.

확인하는 방법을 스스로 정해도 된다. 다만 **근거가 숫자여야 한다.**
「포함되는 것 같다」 는 결론으로 쓰지 않는다.

확인하지 못하면 확인하지 못했다고 적는다.
그 경우 ADR 은 「부모와 자식을 더한 합계를 화면에 보이지 않는다」 를 유지한다.
ADR-014 가 이미 그렇게 적었다.

### 3. 세 방식을 견준다

| 방식 | 무엇 |
| --- | --- |
| A | Hermes Kanban 을 그대로 쓴다 |
| B | Hermes Plugin 으로 orchestration API 를 연다 |
| C | Control Plane 이 orchestration 을 갖고 Hermes 를 실행기로만 쓴다 |

아래 요구를 기준으로 각각 판정한다. **확인한 것과 추론한 것을 구분해 적는다.**

| 요구 | 왜 필요한가 |
| --- | --- |
| Task 를 쪼갠다 | |
| 에이전트를 고른다 | |
| 의존이 있는 Task 그래프 | |
| 병렬 실행 | |
| 실패와 재시도 상태 | |
| 결과 합치기 | |
| 부모와 뿌리 실행 연결 | `agent_execution` 에 그 칸이 이미 있다 |
| 모든 실행의 사용량과 비용 추적 | |
| 사용자·Memory·credential 경계 상속 | 아래를 본다 |

**경계 상속이 가장 까다롭다.**

- 실행할 profile 은 요청자의 바인딩에서만 꺼낸다. 요청 본문이 정하지 못한다
- Memory 접근 권한은 Control Plane 이 주입으로 정한다
- 자식 실행이 부모와 다른 사용자의 것을 보게 되면 안 된다

A 와 B 는 Hermes 안에서 자식이 만들어지므로, 그 자식이 어느 사용자의 것인지를
Control Plane 이 알 방법과 Memory 를 주입할 방법이 있는지 확인해야 한다.

### 4. ADR 을 쓴다

`docs/adr/ADR-015-<슬러그>.md` 다. 번호는 `docs/adr/INDEX.md` 를 보고 비어 있는 다음 것을 쓴다.

형식은 기존 ADR 을 따른다. `ADR-013` 이 최근 것이다.
결정, 맥락, 대안 기각, 결과를 담고 결과에는 얻는 것과 감당할 것을 모두 적는다.

**확인하지 못한 것을 「감당할 것」 에 적는다.**
특히 자식 토큰이 부모에 포함되는지를 확인하지 못했다면 그것을 적는다.

`docs/adr/INDEX.md` 에 한 줄을 더한다.

### 5. 이 phase 를 검증하는 테스트

코드를 만들지 않으므로 단위 테스트를 쓰지 않는다.
대신 기존 테스트가 그대로 통과하는 것과 아래 세 산출물이 판정 기준이다.

- 명령별 실제 출력을 담은 조사 기록. 저장소 밖에 두고 경로를 보고에 적는다
- 자식 토큰 확인 결과. 숫자이거나 「확인하지 못했다」 와 그 이유
- `docs/adr/ADR-0NN-*.md` 와 갱신된 `INDEX.md`

ADR 을 한국어 검사기에 통과시킨다. 이것이 이 phase 의 판정 기준이다.

```bash
# cwd: 저장소 root
/Users/nhn/personal/fos-skills/korean-check/scripts/check.sh docs/adr/ADR-0NN-*.md docs/adr/INDEX.md
```

## 검증

```bash
# cwd: 저장소 root
/Users/nhn/personal/fos-skills/korean-check/scripts/check.sh docs/adr/INDEX.md
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
```

코드를 바꾸지 않았으므로 기존 테스트가 그대로 통과해야 한다.

## 끝낸 뒤

**ADR 을 사용자가 받아들이기 전에 phase-02 로 가지 않는다.**
`worker_done` 을 보내고 멈춘다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `docs/adr/ADR-0NN-<슬러그>.md` | 신규 |
| `docs/adr/INDEX.md` | 수정 |
