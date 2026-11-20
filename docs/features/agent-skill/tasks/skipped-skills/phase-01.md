# Phase 01. 빠진 스킬을 바인딩에 저장하고 연결 목록 응답에 낸다

**Execution profile**: standard

## 목표

붙이기와 다시 설치 응답의 `skipped_skills` 를 바인딩 행에 남기고, 에이전트 연결 목록과 붙이기 응답의 바인딩마다 낸다.

**범위 외**: 화면 안내(phase 02). 대시보드 plugin 은 PR1 에서 끝났다.

## 컨텍스트

**요구사항**: REQ8

**근거 문서**: `docs/features/agent-skill/spec.md`, `docs/features/agent-skill/design.md` 의 「PR2 의 경계」, `hermes/plugins/dashboard-profile-api/README.md` 의 `PUT /api/connectors` 행

- 대시보드 응답 칸: `skipped_skills: [{name, reasons}]`. 붙이기만 내고, 떼기와 옛 plugin 은 칸이 없다. 까닭 값은 `terminal_off`, `sandbox_unregistered`, `sandbox_env_missing`, `name_taken` 이다.
- 설치 응답 읽기는 `backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesConnectorClient.java` 의 `installResult`, 결과 타입은 `HermesConnectorClient.InstallResult`(port) 다. `optionalBoolean` 처럼 칸이 없으면 기본값이고, 있으면 모양이 틀릴 때 실패한다.
- 결과 기록은 `ConnectorBindingInstalls.record(ConnectorBinding, InstallResult, Instant)` 이다. 설치 응답을 받았을 때(`result.installed() != null`)만 불린다. 설치가 실패하면 불리지 않아 옛 기록이 남는다.
- JSON 글 컬럼의 선례는 `agent_connector_binding.event_watch_state`(`TEXT`) 다. H2 의 `JSON` 컬럼이 글 인자를 JSON 문자열 하나로 저장해 `TEXT` 로 둔다(`backend/docs/data-schema.md`).
- 마이그레이션은 예약한 `backend/src/main/resources/db/migration/V20261105074429__binding_skipped_skills.sql` 이다.

## 의도 메모

- 설계의 「JSON 컬럼」 은 JSON 글을 담는 컬럼으로 읽고 `TEXT` 로 둔다. `JSON` 타입은 H2 와 MySQL 에서 저장 모양이 달라진다.
- 빈 목록은 null 로 저장한다. 옛 행과 같은 뜻이라 백필이 필요 없다.
- 까닭은 글 그대로 저장하고 Control Plane 이 enum 으로 묶지 않는다. 대시보드가 새 까닭을 더해도 설치 읽기가 실패하지 않게 한다. 화면이 아는 값만 문구로 바꾼다.
- 관리자 응답(`ConnectionAdminDtos.AgentConnectionResponse`)에는 칸을 더하지 않는다. REQ8 은 주인의 연결 목록만 요구한다.

## 작업 항목

### 1. port 와 설치 응답 읽기

- `HermesConnectorClient.InstallResult` 에 `List<SkippedSkill> skippedSkills` 를 더하고 중첩 record `SkippedSkill(String name, List<String> reasons)` 를 둔다. 기존 2·3 인자 생성자는 빈 목록이다.
- `HttpHermesConnectorClient.installResult` 가 `skipped_skills` 를 읽는다. 없거나 null 이면 빈 목록, 배열이 아니거나 항목의 `name` 이 빈 글이거나 `reasons` 가 글 배열이 아니면 `IllegalStateException`.

### 2. 저장

- 마이그레이션: `ALTER TABLE agent_connector_binding ADD COLUMN skipped_skills TEXT NULL COMMENT '...'`.
- `connector/connection/domain/SkippedSkill.java` record(이름, 까닭 목록)와 JSON 읽기·쓰기. `EventWatchState` 의 mapper 설정을 따른다.
- `ConnectorBinding` 에 `skipped_skills` 컬럼 필드와 `recordSkippedSkills(List<SkippedSkill>)`, `skippedSkills()` 를 더한다.
- `ConnectorBindingInstalls.record(binding, installed, now)` 가 `installed.skippedSkills()` 를 domain 값으로 바꿔 적는다.

### 3. 응답

- `AgentConnectionView` 에 `List<SkippedSkill> skippedSkills` 를 더한다. 붙어 있지 않으면 빈 목록이다(`ConnectorBindingService.view`).
- `ConnectionDtos.AgentConnectionResponse` 에 `skippedSkills: [{name, reasons}]` 를 더한다.
- `skills` 의 주석을 「붙이면 설치할 수 있는 스킬 이름(에이전트용 스킬 포함)」 으로 맞춘다(`AgentConnectionView`, `ConnectionDtos`, `ConnectionAdminDtos`).

### 4. 문서

- `backend/docs/data-schema.md` 의 「agent_connector_binding」 에 `skipped_skills` 를 적는다.
- `docs/features/connector.md` 의 바인딩 설치 결과 절에 저장과 응답을 한 줄 더한다.

### 5. 테스트

- `HttpHermesConnectorClientTest`: `skipped_skills` 를 읽고, 없으면 빈 목록, 모양이 틀리면 실패.
- `AgentConnectionControllerTest`: 빠진 스킬이 있는 붙이기 응답과 그 뒤 연결 목록의 그 바인딩에 `skippedSkills` 의 이름과 까닭이 있다. 칸이 없는 옛 응답이면 빈 목록이다.
- `ConnectorBindingDriftTest`: 기록이 있는 바인딩을 다시 설치해 빈 목록을 받으면 비고, 다시 설치가 실패하면 옛 기록이 남는다.
- `InstallResult` 생성자 모양이 바뀌어 깨지는 테스트는 2·3 인자 생성자를 남겨 고치지 않는다.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*HttpHermesConnectorClientTest' --tests '*AgentConnectionControllerTest' --tests '*ConnectorBindingDriftTest' --tests '*ConnectorBindingServiceTest' --tests '*ArchitectureRules*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074429__binding_skipped_skills.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/SkippedSkill.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectorBinding.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingInstalls.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/AgentConnectionView.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/admin/connector/presentation/ConnectionAdminDtos.java` | 수정 (주석) |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/AgentConnectionControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingDriftTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java` | 수정 (뷰 생성자) |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/connector.md` | 수정 |
