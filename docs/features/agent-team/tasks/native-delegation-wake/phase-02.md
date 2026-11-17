# Phase 02. 끝 소식 경로

**Execution profile**: standard

## 목표

fos-ctx 가 `subagent_stop` 에서 보낼 끝 소식을 받아 등록 줄에 상태와 결과 글을 적고, 5초 뒤 부모 대화를 깨우는 사건을 낸다.

**범위 외**: 자동 turn 에 결과를 넣는 일(phase 3). 이 phase 에서 깨워도 넣을 결과를 내는 구현이 아직 없어 turn 은 열리지 않는다. fos-ctx 의 hook 코드(다음 PR).

## 컨텍스트

**요구사항**: REQ10(끝 소식을 받는다), REQ14(설정을 끄면 결과를 전하지 않는다)

**근거 문서**: `docs/features/agent-team/design.md` 의 「PR6 내장 위임 결과」 → 「끝 소식 경로」, 「깨우는 때」, 「설정을 끈 동안」, 「실패와 일관성」, `docs/features/agent-team/spec.md`(DECISION39), `hermes/docs/hermes-contract.md` 「하위 에이전트의 끝 hook 은 결과 줄보다 먼저 온다」

- phase 1 이 `hermes_session_binding` 의 결과 칸, `NativeResultStatus`, `SubagentResultTexts` 를 만들었다.
- 등록 경로의 모양을 그대로 따른다: `SubagentSessionController.register`, `SubagentRegistration.verify`(HMAC 은 `McpCallContext.hmac(tokenHash, signed)`).
- 토큰 인증 경로 목록: `mcp/presentation/AgentTokenAuthenticationFilter.java` 의 `AGENT_TOKEN_PATHS`, JWT 를 건너뛰는 목록: `shared/auth/ControlPlaneJwtFilter.java` 의 `UNFILTERED_PATHS`. 둘 다 정확히 같은 경로만 맞춘다.
- `SESSION_BINDING_REJECTED` 는 `403` 이다(`shared/error/ErrorCode.java`). 토큰 없는 요청의 `401` 은 필터가 낸다.
- 글 자르기: `chat.nextturn.application.port.DelegationOutputClip.clip(String)`(구현 `orchestration.application.DelegationOutput`, 상한은 `assistant.delegation.output-max-chars`).
- 설정: `chat.nextturn.config.DelegationWakeProperties` 를 `LiveProperties<DelegationWakeProperties>` 로 읽는다(`DelegationWakeService` 본보기).
- 예약과 스레드: `DelegationWakeService.retryLaterAfterUserBusy`/`dueAfterUserBusy` 가 `TaskScheduler.schedule` 뒤 `BackgroundTasks.start` 로 새 가상 스레드에서 사건을 낸다. 같은 모양을 쓴다.
- 사건: `chat.nextturn.application.dto.DelegationFinished(conversationId, executionId)`. `NextTurnDispatcher.onDelegationFinished` 는 대화 번호만 쓰고, `DelegationLineNotifier.publish` 는 실행 번호가 null 이면 아무것도 하지 않는다.
- 테스트 명령은 worker 지침대로 무거운 검사 대기열로 감싸 돌린다.

## 의도 메모

- 결과 글은 서명하지 않는다. 토큰이 profile 을 증명하고, 서명은 session 세 값을 묶는다.
- 서명한 루트가 등록 줄의 루트와 다르면 `403` 이다. 부모가 다른 것은 받는다. 등록 뒤 부모 session 이 압축으로 바뀔 수 있어서다(로그에만 남긴다).
- 저장은 `stopped_at IS NULL` 조건부 update 하나다. 다시 보낸 소식과 동시 요청은 하나만 적힌다.
- 예약이 실패해도 결과는 남는다. 다음 turn 닫기와 기동 훑기가 다시 깨운다.

## 작업 항목

### 1. 새 `mcp/application/dto/SubagentStop.java`

`SubagentRegistration` 과 같은 모양의 record 와 `verify(JsonNode body, String tokenHash)`.

- `v` 는 정수 1, `parent_session_id`, `parent_root_session_id`, `child_session_id`, `sig` 는 필수 글, `child_status` 는 글이나 null, `child_summary` 는 글이나 null.
- 서명할 글: `v1-subagent-stop`, `parent_root_session_id`, `parent_session_id`, `child_session_id` 를 `\n` 으로 이은 UTF-8.
- 어긴 본문은 모두 `SESSION_BINDING_REJECTED`. 이유는 로그에만 남기고 본문, 서명, 토큰 해시는 남기지 않는다.

### 2. `mcp/presentation/SubagentSessionController.java` 와 `McpDtos`

- `POST /internal/hermes/session-bindings/subagent/stop`. 본문을 문자열로 받아 읽는 것은 등록과 같다.
- 응답: `200 {"result":"recorded"}`, `200 {"result":"ignored"}`, 등록 줄이 없으면 `404` 와 새 오류 코드 `SESSION_BINDING_NOT_FOUND`(`ErrorCode` 에 `HttpStatus.NOT_FOUND` 로 더한다), 루트가 다르면 `403`(`SESSION_BINDING_REJECTED`).
- `McpDtos` 에 `SubagentStopResponse(String result)` 를 더한다.
- 두 필터의 목록에 새 경로를 더한다.

### 3. 새 `orchestration/application/SubagentStopRecorder.java` 와 `orchestration/application/dto/SubagentStopResult.java`

`SubagentStopResult { RECORDED, IGNORED, NOT_FOUND }`. `record(profileName, rootSessionId, parentSessionId, childSessionId, childStatus, childSummary)`:

1. `findByProfileNameAndSessionId` 로 줄을 읽는다. 없으면 `NOT_FOUND`.
2. 줄의 `rootSessionId` 가 서명한 루트와 다르면 `SESSION_BINDING_REJECTED` 를 던진다. 부모가 다르면 정보 로그만 남긴다.
3. `wake_eligible` 이 거짓이면 `IGNORED`.
4. 상태는 `completed` 면 `SUCCEEDED`, 그 밖(모르는 값, null 포함)은 `FAILED`.
5. 결과 글은 `DelegationOutputClip.clip` 으로 자르고 `SubagentResultTexts` 로 봉한다. AAD 는 `hermes_session_binding:<id>:result_text:user:<user_id>`. 봉한 key 가 줄의 `result_key_id` 와 다르고 `goal_preview` 가 있으면 goal 을 옛 key 로 열어 새 key 로 다시 봉해 같은 update 에 넣는다. 두 칸이 한 key 를 쓰기 때문이다.
6. `HermesSessionBindingRepository` 의 새 `@Modifying` 질의 하나로 `stopped_at IS NULL` 인 줄만 `stopped_at`, `result_status`, `result_text`, `result_key_id`, `goal_preview` 를 적는다. 설정이 꺼져 있으면 `result_delivered_at` 도 같은 시각으로 적는다(스펙 DECISION39). 바뀐 줄이 0 이면 `IGNORED`.
7. 커밋한 뒤 설정이 켜져 있으면 origin 실행의 `conversationId` 로 6초 뒤를 예약한다. 때가 되면 `BackgroundTasks.start("native-delegation-wake-" + conversationId, ...)` 안에서 `DelegationFinished(conversationId, null)` 을 낸다. 예약이나 스레드가 실패하면 경고 로그만 남긴다.

시각은 주입한 `Clock` 으로 읽는다. 지연은 상수 `WAKE_DELAY = Duration.ofSeconds(6)` 다(설계 「깨우는 때」 의 5초 묶음에 1초 여유).

### 4. Javadoc

`DelegationFinished` 의 `executionId` 가 「Hermes 내장 위임이 끝났으면 null」 일 수 있다고 적는다. `NextTurnDispatcher.onDelegationFinished`, `DelegationLineNotifier.onFinished` 의 Javadoc 도 같다. 동작은 바꾸지 않는다.

### 5. 문서

`hermes/plugins/fos-ctx/README.md` 에 「하위 에이전트 끝 소식 계약」 절을 더한다: 경로, 본문 칸, 서명할 글, 응답 표. 설계 「끝 소식 경로」 의 표와 같은 뜻이다. 플러그인은 `404` 와 `200 ignored` 에 로그만 남긴다고 적는다.

### 6. 테스트

- 새 `backend/src/test/java/com/bifos/assistant/mcp/application/SubagentStopTest.java`: 정상 본문, 서명 첫 줄을 `v1-subagent` 로 만든 서명은 거절(등록 서명을 재사용하지 못한다), `child_status` 가 숫자면 거절, `child_summary` 없음은 null.
- `backend/src/test/java/com/bifos/assistant/mcp/SubagentSessionEndpointTest.java`: 깨울 자식의 끝 소식이 `200 recorded` 이고 칸이 차며 `result_text` 가 봉해졌다. 같은 소식을 다시 보내면 `200 ignored` 이고 칸이 바뀌지 않는다. 깨울 자식이 아니면 `ignored`. 등록 없는 자식은 `404`. 토큰 없으면 `401`. 루트가 다르면 `403`. 결과 글이 상한을 넘으면 잘렸다. 서명은 `McpCallSigner` 에 끝 소식용 메서드를 더해 계산한다.
- 새 `backend/src/test/java/com/bifos/assistant/orchestration/SubagentStopRecorderTest.java`(단위, 저장소와 스케줄러 대역): `completed` 는 `SUCCEEDED`, `interrupted` 와 모르는 값은 `FAILED`. 설정이 꺼져 있으면 `result_delivered_at` 을 적고 예약하지 않는다. 켜져 있으면 6초 뒤로 한 번 예약한다. 바뀐 줄이 0 이면 예약하지 않는다.
- `backend/src/test/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilterTest.java`: 새 경로가 JWT 없이 필터를 지난다.

## 검증

```bash
cd backend && ./gradlew test --tests '*SubagentStopTest' --tests '*SubagentStopRecorderTest' --tests '*SubagentSessionEndpointTest' --tests '*ControlPlaneJwtFilterTest' --tests '*SubagentRegistrationTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
node scripts/checks/check-file-length.mjs
node --test test/repo/doc-links.test.ts test/repo/doc-code-references.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/application/dto/SubagentStop.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/SubagentSessionController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenAuthenticationFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/SubagentStopRecorder.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/dto/SubagentStopResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/infra/HermesSessionBindingRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/dto/DelegationFinished.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/NextTurnDispatcher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/DelegationLineNotifier.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/SubagentStopTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/SubagentStopRecorderTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/SubagentSessionEndpointTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilterTest.java` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
