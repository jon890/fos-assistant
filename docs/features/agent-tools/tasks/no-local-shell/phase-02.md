# Phase 02. Control Plane 이 기동할 때 실행 공간 도구를 다시 저장하고 정책 밖이면 끈다

**Execution profile**: standard

## 목표

이미 local 로 켜진 셸을 배포 뒤 첫 기동에서 바로잡는다. 실행 공간 도구가 켜진 에이전트를 지금 목록 그대로 실행 공간 쓰기로 다시 저장하고, `reason: unlisted` 로 거절되면 실행 공간 도구를 빼고 다시 쓴다.

**범위 외**: plugin 변경(phase 01). 등급, 기본 켜기, 화면(PR3 부터).

## 컨텍스트

**요구사항**: REQ1

**근거 문서**: `docs/features/agent-tools/spec.md` 의 DECISION17, `docs/features/agent-tools/design.md` 의 「PR1」 과 「실패와 일관성」, `docs/adr/ADR-20261120-no-local-shell.md`

- 409 해석은 `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesCallFailure.java` 의 `sandboxRejection` 이다. 본문의 `code` 가 `sandbox_unavailable` 이면 `HermesRequestRejected(ErrorCode.AGENT_SANDBOX_UNAVAILABLE, …)` 를 돌려준다.
- `HermesRequestRejected` 는 `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesRequestRejected.java` 이고 `status()` 만 더 갖는다.
- 목록 계산은 `agent/application/AgentDefaultToolsets.java` 의 private static `desired()` 를 쓴다. 지금 켜진 `AgentToolPolicy::isKnown` 이름, `CONTROL_PLANE_MCP`, `connectorBindings.connectorServers(agent.id())`, 켜져 있으면 `ATTACHMENT_INSPECTION`. `enablePending` 은 그룹 공개, 주인 없음, 비밀값 요청 스킬로 멈추므로 부르지 않는다.
- 에이전트 순회와 행 잠금은 `agent/application/AgentPendingToolsets.java` 의 `attemptAll`, `attempt`, `attemptLocked`(쪽 단위 번호 읽기, `TransactionTemplate`, `findByIdForUpdate`)를 따른다.
- 기동 때 도는 작업의 예는 `chat/attachment/application/AttachmentCleaner.java` 의 `@EventListener(ApplicationReadyEvent.class)` 다.
- 설정은 `agent/config/AgentProperties.java`(rule-23, rule-24)다. 테스트 설정 `backend/src/test/resources/application-test.yml` 은 같은 이유로 `pending-toolsets-cron: "-"` 를 둔다.

## 의도 메모

- 매 기동마다 돈다. 정책이 그대로면 결과가 같고, 표지를 둘 자리를 새로 만들지 않는다(ADR-20261120 / no-local-shell).
- `profileUnlisted()` 가 참일 때만 셸을 끈다. 그 밖의 거절과 Hermes 실패는 그 에이전트를 메모리의 끝내지 못한 목록에 두고, 기다리는 기본 도구 주기(`assistant.agents.pending-toolsets-cron`)에 끝날 때까지 다시 본다. Control Plane 과 Hermes 가 함께 재시작되면 첫 시도가 실패하기 쉽다.
- 공개 범위와 스킬로 제외하지 않는다.
- 지운 에이전트, 비밀값 요청 스킬이 있는 에이전트도 순회 대상에서 빼지 않는다. 지운 에이전트는 쿼리가 뺀다. 비밀값 요청 스킬이 있으면 실행 공간 도구가 켜져 있을 수 없다(지금 저장 검사).
- 기각: `ErrorCode` 를 새로 둔다. `AGENT_SANDBOX_UNAVAILABLE` 을 보는 호출처(기본 도구, 스킬 게시, 연결 붙이기, 다시 시도 작업)를 모두 고쳐야 한다. 표시 하나로 충분하다.

## 작업 항목

### 1. `HermesRequestRejected` 에 정책 밖 표시

`private final boolean profileUnlisted` 와 `public boolean profileUnlisted()` 를 더한다. 기존 두 생성자는 `false` 로 둔다. `RestClientResponseException` 을 받는 생성자에 `boolean profileUnlisted` 를 더 받는 생성자를 하나 더한다. Javadoc 에 「plugin 이 정책에 없는 profile 이라 거절했다(ADR-20261120 / no-local-shell)」 를 적는다.

### 2. `HermesCallFailure.sandboxRejection`

본문의 `reason` 필드이 문자열 `unlisted` 이면 표시를 참으로 만든다. `reason` 이 없거나 다른 값이면 거짓이다. 오류 코드와 메시지는 그대로다.

### 3. `AgentDefaultToolsets` 에 다시 저장

`public SandboxReapplyOutcome reapplySandbox(Agent agent)` 를 더한다. `SandboxReapplyOutcome` 은 `agent/application/dto/` 의 enum `NOT_NEEDED`, `REWRITTEN`, `SHELL_REMOVED` 다.

- 켜진 목록에 `AgentToolPolicy.hasSandboxToolset` 이 거짓이면 `NOT_NEEDED`.
- 목록(`enablePending` 과 같은 계산, 더할 이름 없음)을 `writeApiServerInSandbox(profile, desired, agent.sandboxOwner())` 로 쓰고 `REWRITTEN`.
- `HermesRequestRejected` 이고 `profileUnlisted()` 면 `AgentToolPolicy.withoutSandboxToolsets(desired)` 를 `writeApiServer` 로 쓰고 경고 로그(agent, profile)를 남긴 뒤 `SHELL_REMOVED`. 그 밖의 예외는 올린다.
- `write` 의 fallback 주석(「미등록 profile 의 셸을 local 로 바꾸므로」)을 「plugin 이 정책 밖 셸 저장을 거절한다(ADR-20261120 / no-local-shell)」 로 고친다. 동작은 바꾸지 않는다.

### 4. `AgentRepository` 에 순회 쿼리

`findIdsWithPendingDefaultToolsets` 옆에 `List<Long> findLiveIdsAfter(long afterId, Pageable pageable)`(지우지 않은 에이전트, `id > :afterId`, 번호 순, native 쿼리)를 더한다.

### 5. `agent/application/AgentSandboxSweep.java` 신규

- `@Component`, `@EventListener(ApplicationReadyEvent.class)` 의 `onReady()` 가 `properties.sandboxSweepOnStart()` 일 때 `sweepAll()` 을 부른다.
- `sweepAll()` 은 `AgentPendingToolsets.attemptAll` 처럼 50 개씩 쪽을 넘기며 `sweep(agentId)` 를 부르고, 끝에 결과별 수를 info 로그로 남긴다.
- `sweep(agentId)` 는 `TransactionTemplate` 안에서 `findByIdForUpdate` 로 잠그고, 행이 없거나 지웠으면 건너뛰고, `defaults.reapplySandbox(agent)` 를 부른다. 예외는 잡아 경고 로그를 남기고 끝내지 못한 목록(`Set<Long>`, 메모리)에 둔다. 성공하면 목록에서 뺀다.
- `@Scheduled(cron = "${assistant.agents.pending-toolsets-cron}")` 의 `retryUnfinished()` 가 끝내지 못한 목록만 다시 `sweep` 하고 남은 수를 로그로 남긴다. 목록이 비면 아무것도 하지 않는다.
- 클래스 Javadoc 에 ADR-20261120 / no-local-shell 과 「매 기동 돈다, 결과가 같다」 를 적는다.

### 6. 설정

- `AgentProperties` 에 `Boolean sandboxSweepOnStart`(기본 참)를 더하고 Javadoc 에 뜻을 적는다.
- `backend/src/main/resources/application.yml` 의 `assistant.agents` 에 `sandbox-sweep-on-start: true` 와 한 줄 주석을 둔다.
- `backend/src/test/resources/application-test.yml` 의 `assistant.agents` 에 `sandbox-sweep-on-start: false` 와 「검사가 뜰 때마다 Hermes 를 부르지 않게 끈다. 검사는 그 메서드를 직접 부른다」 를 둔다.

### 7. 테스트

- `backend/src/test/java/com/bifos/assistant/agent/AgentSandboxSweepTest.java`(mock): 에이전트 셋(정책 안 → 같은 목록 실행 공간 쓰기, `profileUnlisted` 거절 → 실행 공간 도구 뺀 목록 `writeApiServer`, 표시 없는 409 → 다시 쓰지 않음)과 셸이 꺼진 에이전트(쓰기 없음), 한 에이전트가 예외를 던져도 다음 에이전트를 보는지. 실패한 에이전트가 `retryUnfinished()` 에서 다시 시도되고, 성공하면 다음 차례에 다시 보지 않는지. 그룹 공개이거나 주인이 없는 에이전트도 다시 쓰는지. `reapplySandbox` 를 직접 보는 경우는 `AgentDefaultToolsetsTest` 에 더해도 된다.
- `HermesCallFailure` 의 테스트 파일(`grep -rln "sandboxRejection" backend/src/test/java` 로 찾고, 없으면 `backend/src/test/java/com/bifos/assistant/hermes/HermesCallFailureTest.java`): `reason: unlisted` 있음과 없음.

### 8. 기능 문서

`docs/features/agent-skill.md` 의 「에이전트 도구를 고를 때」 표에서 「정책은 유효한데 이 profile 이 등록되지 않았다」 줄을 「실행 공간 도구를 켜면 409 `AGENT_SANDBOX_UNAVAILABLE` 이다. local 로 켜지 않는다. Control Plane 은 기동할 때 이미 켜진 실행 공간 도구를 다시 저장하고 정책 밖이면 끈다(ADR-20261120 / no-local-shell)」 로 바꾼다. 같은 문서의 「스크립트와 실행 공간」 은 바꾸지 않는다. `docs/features/connector-policy.md` 의 RC-03 남는 위험 줄(「정책에 등록하지 않은 profile 과 기존 Hermes 예약 작업은 local 로 돈다」)을 「정책에 등록하지 않은 profile 은 실행 공간 도구를 켜지 못한다. 기존 Hermes 예약 작업은 local 로 돈다」 로 고친다.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*AgentSandboxSweepTest' --tests '*AgentDefaultToolsetsTest' --tests '*HermesCallFailure*' --tests '*AgentPendingToolsets*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew archTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesRequestRejected.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesCallFailure.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentDefaultToolsets.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/dto/SandboxReapplyOutcome.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentSandboxSweep.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/config/AgentProperties.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentSandboxSweepTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesCallFailureTest.java` | 신규 또는 수정 |
| `docs/features/agent-skill.md` | 수정 |
| `docs/features/connector-policy.md` | 수정 |
