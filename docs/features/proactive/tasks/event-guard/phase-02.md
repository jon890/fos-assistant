# Phase 02. Control Plane 의 `tool-guard` 판정과 자식 등록 거절

**Execution profile**: standard

## 목표

`POST /internal/hermes/tool-guard` 가 서명을 확인하고, `EventToolGuard` 가 그 session 이 사건 살펴보기 트리인지 판정한다. 자식 session 등록은 바꾸지 않는다.

**범위 외**: hook(phase 1), 시작 전 점검과 깨우기(phase 3).

## 컨텍스트

**요구사항**: REQ9, REQ10

**근거 문서**: `docs/features/proactive/design.md` 의 「사건 실행의 도구 판정(PR3)」 판정 순서 1~4

- 서명 확인은 `mcp/application/dto/SubagentRegistration.java` 와 `connector/approval/application/dto/ConnectorPolicyRequest.java` 를 따른다
- 경로 인증은 `AgentTokenAuthenticationFilter.AGENT_TOKEN_PATHS` 와 `ControlPlaneJwtFilter.UNFILTERED_PATHS` 에 함께 더한다
- 판정은 `orchestration` 에 둔다. `mcp` 는 `orchestration` 위다(`TopLevelPackageOrder`)

## 의도 메모

- 도는 실행이 없으면 점검 대화의 session 일 때, 모르는 session 은 그 profile 에 사건 살펴보기가 돌 때 막는다
- 판정 중 예외는 200 `block` 이다. 이 경로는 404 를 내지 않는다(hook 이 404 를 옛 Control Plane 으로 읽는다)
- 새 테이블은 없다. 점검 대화 판정은 `ConversationRepository` 의 JPQL 하나다(rule-51 의 MySQL 시험에 더한다)

## 작업 항목

### 1. `proactive/check/application/ProactiveCheckGuard.isEventTree`, `eventRunningOn`

트리 루트의 살펴보기 줄이 계기 `EVENT` 인가, 그 profile 의 에이전트로 도는 `EVENT` 살펴보기가 있는가.

### 2. `orchestration/application/EventToolGuard`

설계의 판정 순서 1~4. 답은 `ToolGuardAnswer(allowed, message)`.

### 3. `mcp/presentation/ToolGuardController`, `mcp/application/dto/ToolGuardRequest`

`connector-policy` 와 같은 인증과 거절. 응답은 `{"decision", "message"}`.

### 5. 시험

`EventToolGuardTest`(통합), `ToolGuardRequestTest`, `ToolGuardControllerTest`.

## 검증

```bash
cd backend && ./gradlew test --tests '*EventToolGuard*' --tests '*ToolGuard*' 
cd backend && ./gradlew archTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckGuard.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/infra/ProactiveCheckRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/EventToolGuard.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/dto/ToolGuardAnswer.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/ToolGuardController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/dto/ToolGuardRequest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenAuthenticationFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/EventToolGuardTest.java` | 신규 |
