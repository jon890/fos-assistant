# Phase 07. 책임 문서, 옛 ADR 의 대체 표시, 용어

**Execution profile**: standard

## 목표

구현한 흐름과 계약을 책임 문서가 갖게 한다. 옛 ADR 에 ADR-083 이 대체한 부분을 적고, 「커넥터」 의 정의와 「연결」, 「바인딩」 을 용어 표에 둔다.
이 phase 가 끝나면 ADR-083 의 「아직 구현 전이다」 를 지운다.

**범위 외**: 코드 변경. 앞 phase 들이 끝냈다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`, `docs/adr/INDEX.md`, `docs/README.md`

- 결정: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`. 「대체된 부분」 에 옛 ADR 과 대체한 부분이 있다
- ADR 형식과 대체 표시: 「대체된 부분」 은 옛 ADR 의 결정 바로 아래에 둔다. ADR 은 지우지 않는다. 전체가 대체됐으면 `status` 를 `superseded` 로 바꾼다. 본보기는 `docs/adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md` 의 `대체된 부분` 줄과 `docs/adr/INDEX.md` 의 `Accepted. … 부분은 ADR-0xx 가 대체한다` 와 `Superseded ([ADR-0xx](…))`
- 사용자의 정의(2026-10-05): 「커넥터는 에이전트에게 도구를 쥐어 주는 것이고 에이전트의 역할을 넓혀 주는 것으로 이해해야 한다.」 「커넥터 에이전트」 라는 말은 옮겨 가기 설명 밖에서 쓰지 않는다
- 「커넥터 에이전트」, 「연결용 에이전트」, 「전용 profile」, 「전용 에이전트」 를 언급하는 문서는 아래 명령으로 찾는다

```bash
git grep -n -E "커넥터 에이전트|연결용 에이전트|연결용 profile|전용 profile|전용 에이전트|connectorManaged|connector_managed" -- docs README.md README.ko.md AGENTS.md hermes/README.md
```

## 의도 메모

- 문서는 지금 사실만 적는다. 옛 커넥터 에이전트는 「옮겨 가기 전에 만든 에이전트가 남아 있는 동안」 의 규칙으로만 남긴다
- 문서가 계획서를 번호로 가리키지 않는다
- 루트 `README.md` 와 `README.ko.md` 는 같은 내용을 유지한다

## 작업 항목

### 1. 커넥터 문서

- `docs/connectors.md`: 첫 단락을 커넥터의 정의로 시작한다. 연결(계정 한 번)과 바인딩(에이전트에 붙이기)을 설명하고 「Control Plane API」 표를 phase 05 의 경로로 바꾼다. 「저장과 비밀값」 에 보관 파일과 profile `.env` 의 복사본을 적는다. 승인 절의 「연결용 에이전트」 를 「연결을 붙인 에이전트」 로 고친다
- `docs/backend/connector-install.md`: 「설치와 실패 처리」 를 연결 등록과 바인딩 설치로 나눠 다시 쓴다. 「커넥터 에이전트의 경계」 를 「옛 커넥터 에이전트」 절로 바꿔 남는 동안의 규칙만 적는다. 「연결 상태의 흐름」 mermaid 를 연결 상태와 바인딩 상태 둘로 그린다. 옮겨 가기 절차(연결 확인이 값을 옮기고, 붙이고, 반영 완료 뒤 옛 에이전트를 지운다)를 적는다
- `docs/backend/connector-tool-policy.md`: 판정이 바인딩으로 연결을 고르는 것, 판정에 넘기는 상태, 줄의 `agent_id` 뜻, 승인 실행의 profile
- `docs/connectors/gmail.md`: 커넥터 에이전트를 말하는 줄을 붙이기로 고친다
- `docs/privacy.md`: 값이 남는 곳을 보관 파일과 붙인 에이전트 profile 의 복사본으로 고친다. 연결을 해제하면 둘 다 지워지고, 떼면 그 profile 의 복사본만 지워진다는 것을 「연결을 끊고 지우는 법」 에 적는다
- `docs/connector-authoring.md`: 「본문이 그 에이전트의 지침」 과 8,000자 상한을 말하는 절을, 커넥터 스킬이 붙인 에이전트의 스킬로 설치된다는 것과 올린 스킬과 같은 제한으로 고친다
- 코드 주석이 절 이름을 가리킨다. `ConnectorConnectionService` 의 클래스 주석이 「설치와 실패 처리」 를 가리키므로 `docs/backend/connector-install.md` 를 다시 쓸 때 그 절 이름을 남긴다. `test/unit` 의 문서 참조 검사가 본다

### 2. 흐름과 구조

- `docs/flow.md`: 「커넥터를 붙일 때」 절을 더한다. 계정 연결, 붙이기, 재시작 대기와 반영 완료, 직접 호출의 판정, 떼기를 mermaid 시퀀스로 그리고 실패(대시보드 409, 표식 없는 profile, 연결 미확인)와 동시 요청(같은 사용자의 잠금)의 갈래를 넣는다
- `docs/code-architecture.md`: `fos-ctx` 줄을 「바인딩 profile 과 옛 설치 profile 의 커넥터 도구 호출을 Control Plane 에 물어 막는다」 로 고친다
- `docs/backend/packages.md`: 「connector」 절에 바인딩과 port `AgentConnectorBindings` 를 적고 옛 커넥터 에이전트 문장을 「남아 있는 동안」 으로 고친다
- `docs/frontend/structure.md`: 에이전트 상세의 「연결」 절과 옛 에이전트 표시, 「연결」 화면의 붙인 에이전트 목록
- `docs/prd.md`: 커넥터 문단을 정의와 바인딩으로 고친다

### 3. 다른 책임 문서의 커넥터 에이전트 언급

위 `git grep` 이 찾은 나머지 파일(`docs/backend/context-bundle.md`, `docs/backend/memory.md`, `docs/backend/conversation.md`, `docs/backend/agent-delegation.md`, `docs/backend/schema/users-agents.md`, `docs/backend/schema/memory.md`, `docs/backend/schema/execution.md`, `docs/backend/skill.md`, `docs/backend/follow-up.md`, `docs/backend/attachment.md`, `docs/frontend/chat.md`, `docs/hermes/tools-and-skills.md`, `docs/hermes/connector-policy.md`, `docs/hermes/upgrades.md`)에서 지금 사실과 다른 문장을 고친다. 옛 에이전트에만 걸리는 규칙은 그렇다고 적는다.

### 4. 옛 ADR 과 목록

- ADR-029, ADR-039, ADR-044, ADR-045, ADR-049, ADR-080, ADR-082 의 결정 바로 아래에 「대체된 부분」 을 둔다. 무엇이 대체됐는지는 ADR-083 의 「대체된 부분」 과 같은 문장이다
- 일곱 모두 `accepted` 로 둔다. ADR-045 의 경계도 남은 옛 커넥터 에이전트에는 아직 걸리므로 전체 대체가 아니다. 옛 에이전트가 모두 지워지고 격리 코드를 지우는 작업이 ADR-045 를 `superseded` 로 바꾼다
- ADR-083 의 `status` 에서 「아직 구현 전이다」 를 지운다
- `docs/adr/INDEX.md`: ADR-083 줄의 상태에서 「아직 구현 전이다」 를 지우고 `Accepted. 새 연결에 대해 ADR-039, ADR-044, ADR-045 를, 커넥터 에이전트 부분에 대해 ADR-029, ADR-049, ADR-080, ADR-082 를 대체한다` 로 둔다. 일곱 줄에는 `… 부분은 ADR-083 이 대체한다` 를 더한다. ADR-045 줄에는 남은 옛 커넥터 에이전트에만 걸린다고 적는다

### 5. 남은 언급 확인

작업을 마친 뒤 컨텍스트의 `git grep` 을 다시 돌린다. 남은 줄은 옮겨 가기 설명, 옛 커넥터 에이전트에만 걸리는 규칙, 옛 ADR 의 본문뿐이어야 한다. 아니면 그 문서를 고친다.

### 6. 용어

- `AGENTS.md` 의 「용어」 표에 줄 셋을 더한다
  - 커넥터: 에이전트에게 쥐어 주는 도구 묶음. 쓰지 않는 말은 「커넥터 에이전트」(옮겨 가기 설명 밖)
  - 연결: 사용자가 커넥터 하나에 계정을 연결한 것. 코드는 `connector_connection`
  - 바인딩: 에이전트에 연결을 붙인 것. 화면에서는 「붙이기」. 코드는 `agent_connector_binding`
- 이 절 이름을 바꾸지 않는다. `.github/workflows/` 의 리뷰 프롬프트가 절 이름을 가리킨다
- `README.md` 와 `README.ko.md` 의 커넥터 문장을 정의와 붙이기로 고친다
- `hermes/README.md` 의 「연결용 profile」 문장을 고친다

## 검증

```bash
scripts/check-local.sh agent-connections connector-connection connector-agent-detail admin agent-tools
```

종료 코드 0 이어야 한다. 명령과 순서는 그 스크립트가 갖는다. 인자는 브라우저 검사에만 쓰이고 나머지 단계는 모두 돈다. 전체 브라우저 검사는 PR 의 CI 가 맡는다.

모두 종료 코드 0 이어야 한다. `test/unit` 에는 문서 링크와 ADR 목록을 보는 검사가 있다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `docs/connectors.md` | 수정 |
| `docs/connectors/gmail.md` | 수정 |
| `docs/connector-authoring.md` | 수정 |
| `docs/privacy.md` | 수정 |
| `docs/backend/connector-install.md` | 수정 |
| `docs/backend/connector-tool-policy.md` | 수정 |
| `docs/flow.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/frontend/structure.md` | 수정 |
| `docs/prd.md` | 수정 |
| `docs/backend/*.md` | 수정 |
| `docs/backend/schema/*.md` | 수정 |
| `docs/frontend/chat.md` | 수정 |
| `docs/hermes/*.md` | 수정 |
| `docs/adr/ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md` | 수정 |
| `docs/adr/ADR-039-외부-서비스-연결은-사용자별-전용-에이전트로-실행한다.md` | 수정 |
| `docs/adr/ADR-044-커넥터-manifest-는-읽기-전용-이미지-도구만-열-수-있다.md` | 수정 |
| `docs/adr/ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md` | 수정 |
| `docs/adr/ADR-080-먼저-살펴보기는-점검-대화의-turn-하나로-돌고-읽기-경계를-control-plane-이-강제한다.md` | 수정 |
| `docs/adr/ADR-082-먼저-살펴보기의-쓰기-도구는-관리자가-에이전트마다-켜고-커넥터-쓰기는-승인-카드로-보낸다.md` | 수정 |
| `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `AGENTS.md` | 수정 |
| `README.md` | 수정 |
| `README.ko.md` | 수정 |
| `hermes/README.md` | 수정 |
