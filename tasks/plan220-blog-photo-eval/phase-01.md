# Phase 01. 지난 사진 참조와 원본 조회를 본문 없는 단계 사건으로 남긴다

**Execution profile**: standard

## 목표

Control Plane 이 실행 입력에 지난 사진 참조를 실을 때 `referenced`, `attachment_inspect` 요청을 처리할 때마다 `inspected` 단계 사건을 한 줄씩 남긴다.
블로그 사진 평가가 「조회하지 않았다」 와 「조회했지만 읽지 못했다」 를 가르는 근거가 이 두 사건이다.

**범위 외**: 합성 사진과 시나리오(phase 02), 평가 스크립트(phase 03). 실행 입력의 안내 문구(「사진은 모두 N장이다」)는 바꾸지 않는다. 다른 계획이 고친다.

## 컨텍스트

- 사건의 칸과 뜻은 `docs/features/attachment.md` 의 「본문 없는 단계 사건」 표가 갖는다. 이 phase 는 그 표대로 구현한다
- 기존 사건은 `backend/src/main/java/com/bifos/assistant/chat/attachment/application/MediaPipelineEvents.java` 의 `embedded` 와 `draftReferenced` 다. 같은 문자열 조립 방식과 `positions(List<Long>)` 도움 함수를 그대로 쓴다
- `embedded` 는 `AttachmentImages.java` 174줄에서, `draftReferenced` 는 `ContentDraftService.java` 322줄에서 부른다
- 실행 입력은 `AttachmentService.agentInput(Long conversationId, List<ChatAttachment> attached, String text)` 가 만든다. `sent` 는 이 대화에서 메시지에 묶인 모든 첨부이고, 이번 메시지의 첨부(`attached`)도 이미 메시지에 묶인 상태로 들어 있다. `inspectable(ChatAttachment)` 는 보관 중인지를 SQL 로 다시 읽는다
- 원본 조회는 `backend/src/main/java/com/bifos/assistant/mcp/application/AttachmentInspectService.java` 의 `inspect(McpPrincipal, JsonNode)` 다. `authorize` 가 서명과 실행을 확인한 뒤 `consume` 이 조회 한도를 세고, `AttachmentService.inspectOverview` 나 `inspect` 가 바이트를 만든다. 마지막에 `active(execution.id())` 를 다시 확인한다
- 요청 값은 `AttachmentInspectRequest(long attachmentId, List<Integer> region, boolean overview)` 다. 영역이 없으면 `region` 은 `null` 이다
- 로그 판독기는 `test/support/media-pipeline-log.ts` 의 `validateBackendPipelineEvent` 와 `parseBackendPipelineEvents` 다. 모르는 단계를 만나면 `BACKEND_EVENT_INVALID` 를 던지므로, backend 가 새 사건을 남기기 시작하면 판독기도 같은 커밋에서 받아야 e2e 의 `test/e2e/scenarios/media-pipeline-draft.ts` 가 깨지지 않는다. 그 시나리오는 단계로 골라 읽으므로 새 사건을 무시한다

**근거 문서**: `docs/features/attachment.md` 의 「본문 없는 단계 사건」, `docs/adr/ADR-20261020-blog-photo-eval.md` 의 결정 4

## 의도 메모

- 조회 기록을 DB 테이블에 저장하는 안과 실행 기록의 가림 규칙에 예외를 두는 안은 ADR 에서 기각했다. 로그 한 줄만 더한다
- 서명이나 실행 확인(`authorize`)에서 거절한 요청은 누가 불렀는지 확정하지 못하므로 사건을 남기지 않는다. `consume` 의 한도 거절부터는 남긴다
- 영역 좌표, 파일 이름, 사진 바이트는 싣지 않는다. 결과 칸에는 `OK` 나 `ErrorCode` 의 이름만 싣는다
- `referenced` 는 이번 메시지의 사진을 뺀 지난 사진만 싣는다. 이번 메시지의 사진은 이미 `embedded` 가 다룬다
- `inspectable` 은 첨부마다 SQL 을 한 번 읽는다. `agentInput` 에서 이미 사진 참조 줄을 만들며 부르므로, 같은 첨부에 두 번 부르지 않게 결과를 한 번 모아 두 곳에서 쓴다

## 작업 항목

### 1. `MediaPipelineEvents` 에 두 사건을 더한다

`backend/src/main/java/com/bifos/assistant/chat/attachment/application/MediaPipelineEvents.java`

- `public static void referenced(Long conversationId, List<Long> attachmentIds)`. 목록이 비면 아무것도 남기지 않는다. 줄은 `media-pipeline {"schemaVersion":1,"stage":"referenced","conversationId":<번호>,"count":<장수>,"assets":<positions(attachmentIds)>}` 다
- `public static void inspected(Long conversationId, Long executionId, long attachmentId, boolean overview, boolean region, ErrorCode failure)`. `failure` 가 `null` 이면 결과는 `OK`, 아니면 `failure.name()` 이다. 조회 방식은 `overview` 가 참이면 `OVERVIEW`, 아니고 `region` 이 참이면 `REGION`, 둘 다 아니면 `ORIGINAL` 이다. 줄은 `media-pipeline {"schemaVersion":1,"stage":"inspected","conversationId":<번호>,"executionId":<번호>,"assetId":"<첨부 번호>","mode":"<방식>","outcome":"<결과>"}` 다
- 두 메서드의 Javadoc 에 「그 단계가 일어난 사실만 뜻하며 모델이 사진을 읽었다는 증거가 아니다」 를 기존 메서드와 같은 말투로 적는다. `ErrorCode` 는 `com.bifos.assistant.shared.error.ErrorCode` 다

### 2. `AttachmentService.agentInput` 이 `referenced` 를 남긴다

`backend/src/main/java/com/bifos/assistant/chat/attachment/application/AttachmentService.java`

- `sent` 마다 `inspectable(it)` 을 한 번만 불러 결과를 `Set<Long>`(보관 중인 첨부 번호)으로 모은다. `agentInput` 안의 `inspectable(it)` 호출은 `attached` 쪽(298, 301줄 근처)을 포함해 모두 이 집합을 읽게 바꾼다. `attached` 는 `sent` 에 들어 있어 동작은 같다
- 지난 사진은 `sent` 가운데 `attached` 의 번호에 없고 보관 중인 첨부다. `sent` 의 순서대로 번호를 모아 `MediaPipelineEvents.referenced(conversationId, 그 번호들)` 을 `return` 직전에 부른다
- `sent` 가 비어 일찍 돌아가는 경로는 바꾸지 않는다

### 3. `AttachmentInspectService.inspect` 가 `inspected` 를 남긴다

`backend/src/main/java/com/bifos/assistant/mcp/application/AttachmentInspectService.java`

- `authorize` 가 돌려준 뒤부터 반환까지(`consume`, 바이트 만들기, 마지막 `active` 확인)를 `try` 로 감싼다
- 성공하면 `MediaPipelineEvents.inspected(execution.conversationId(), execution.id(), request.attachmentId(), request.overview(), request.region() != null, null)` 을 부르고 결과를 돌려준다
- `ApiException` 이면 같은 값과 `ex.code()` 로 남기고 다시 던진다. 그 밖의 `RuntimeException` 이면 `ErrorCode.INTERNAL_ERROR` 로 남기고 다시 던진다
- `validate` 는 바꾸지 않는다. 조회 예산과 파일에 손대지 않는 재검증이라 사건이 아니다

### 4. 로그 판독기가 두 사건을 받는다

`test/support/media-pipeline-log.ts`

- `BackendPipelineEvent` 의 `stage` 에 `"referenced"` 와 `"inspected"` 를 더하고, `executionId?: number`, `assetId?: string`, `mode?: "ORIGINAL" | "REGION" | "OVERVIEW"`, `outcome?: string` 필드를 더한다. `BackendPipelineEvent` 는 `stage` 로 가르는 판별 유니온으로 바꾼다. `embedded`, `draftReferenced`, `referenced` 는 `count` 와 `assets` 가 필수이고 `inspected` 는 `executionId`, `assetId`, `mode`, `outcome` 을 갖는다. `test/e2e/scenarios/media-pipeline-draft.ts` 와 `backendEvidence` 가 `event.assets` 를 읽는 곳은 `stage` 를 좁힌 뒤 읽도록 고쳐 strict 타입 검사를 통과시킨다. 그래서 e2e 시나리오 파일도 이 phase 의 변경 파일이다
- `KEYS` 에 `referenced: ["schemaVersion", "stage", "conversationId", "count", "assets"]`, `inspected: ["schemaVersion", "stage", "conversationId", "executionId", "assetId", "mode", "outcome"]` 를 더한다
- `validateBackendPipelineEvent` 는 단계별로 허용 칸이 정확히 같은지 본다. `referenced` 는 `count` 와 `assets` 검사를 기존과 같이 하고 자리가 0부터 하나씩 늘어야 한다. `inspected` 는 `executionId` 가 1 이상의 안전한 정수, `assetId` 가 `ID` 정규식, `mode` 가 세 값 가운데 하나, `outcome` 이 `^[A-Z][A-Z_]{0,63}$` 이어야 한다. 어긋나면 `BACKEND_EVENT_INVALID` 다
- `backendEvidence` 는 `embedded` 와 `draftReferenced` 만 받는다. 다른 단계가 오면 `BACKEND_EVENT_STAGE_UNSUPPORTED` 를 던진다

### 5. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/chat/MediaPipelineEventsTest.java`: 기존 `ListAppender` 방식으로 세 사례를 더한다. 지난 사진 두 장의 `referenced` 줄이 `count` 2 와 자리 0, 1 을 싣는다. 빈 목록은 줄을 남기지 않는다. `inspected` 가 `overview`·`region` 조합마다 방식을 맞게 적고, `failure` 가 있으면 그 이름을, 없으면 `OK` 를 적는다
- `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java`: `nextTextTurnRestoresPastReferences` 옆에 사례를 하나 더한다. 사진을 붙인 메시지 뒤에 사진 없는 메시지를 보내면 두 번째 실행 입력을 만들 때 그 사진의 `referenced` 줄이 남고, 사진을 붙인 첫 메시지에서는 `referenced` 줄이 남지 않는다. 로그는 `MediaPipelineEvents` 로거에 `ListAppender` 를 붙여 읽는다
- `backend/src/test/java/com/bifos/assistant/mcp/application/AttachmentInspectServiceTest.java`: 성공한 조회가 `OK` 줄을, `limitsRepeatedCallAndRejectsCancellationDuringRead` 와 같은 세 번째 반복 조회가 `MCP_CALL_CONTEXT_INVALID`(한도 전용 코드는 없고 `consume` 이 `invalid()` 로 던진다)의 줄을 남기는지 본다. 서명이 바뀐 요청(`tamperedTopLevelProofCannotSelectCurrentExecution` 의 입력)은 줄을 남기지 않는다
- `test/unit/media-pipeline-log.test.ts`: 두 단계의 정상 줄을 받는다. `inspected` 에 좌표 칸이나 파일 이름 칸이 섞이면, `mode` 가 목록 밖이면, `outcome` 에 소문자나 공백이 있으면 거절한다. `backendEvidence` 가 `inspected` 를 받으면 `BACKEND_EVENT_STAGE_UNSUPPORTED` 를 던진다

## 검증

```bash
cd backend && ./gradlew test --tests '*MediaPipelineEventsTest' --tests '*ChatAttachmentTurnTest' --tests '*AttachmentInspectServiceTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
node --test test/unit/media-pipeline-log.test.ts
node --test 'test/unit/**/*.test.ts'
cd backend && ./gradlew archTest
cd test && ../web/node_modules/.bin/tsc -p tsconfig.json
```

`web/node_modules` 가 없으면 먼저 `pnpm --dir web install` 을 돌린다. 모두 종료 코드 0 이다.

`node --test 'test/unit/**/*.test.ts'` 는 `test/unit/doc-code-references.test.ts` 가 포함하며, 앞 docs 커밋이 아직 없는 파일을 가리키는 세 줄에 `ref-ignore` 표기를 달아 두었으므로 통과해야 한다. 그 표기는 phase 03 에서 뗀다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/attachment/application/MediaPipelineEvents.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/attachment/application/AttachmentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/AttachmentInspectService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaPipelineEventsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/AttachmentInspectServiceTest.java` | 수정 |
| `test/support/media-pipeline-log.ts` | 수정 |
| `test/unit/media-pipeline-log.test.ts` | 수정 |
| `test/e2e/scenarios/media-pipeline-draft.ts` | 수정 |
