# Phase 02. 사진이 놓인 자리를 에이전트에게 알린다

**Execution profile**: standard

## 목표

메시지를 보낼 때 첨부 번호를 함께 받아 그 메시지에 묶고,
Hermes 에 보내는 입력에 사진이 놓인 자리를 덧붙인다.

**범위 외**:
화면은 phase-03 이 한다. 이 phase 는 경로와 실행 입력까지다.
사진을 받아 두는 것은 phase-01 이 끝냈다.

## 컨텍스트

`POST /v1/runs` 는 이미지를 담은 항목을 해석하지 않는다.
그래서 사진을 대화 본문에 실어 보내는 길이 없다.

**대신 `read_file` 이 이미지 확장자를 만나면 `vision_analyze` 를 쓰라는 안내를 낸다.**
사진을 에이전트가 닿는 자리에 놓아 두면 모델이 그것을 읽는다.

**근거 문서**:
`docs/adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md`,
`docs/hermes-integration.md` 의 「`/v1/runs` 는 이미지를 받지 않는다」 와
「이미지 파일은 `vision_analyze` 로 본다」,
`docs/code-architecture.md` 의 「사진 첨부」 절,
`docs/flow.md` 의 「사진을 올려 보낼 때」 절.

**그 둘을 먼저 읽는다.** 왜 본문에 싣지 않는지가 거기 있다.

### 사용자가 쓴 것을 고치지 않는다

`chat_message.content` 는 사람이 쓴 그대로 둔다.
덧붙이는 것은 Hermes 에 보내는 입력에만 한다.

지난 대화를 다시 읽을 때 사람이 쓴 것과 우리가 덧붙인 것이 섞이면 안 된다.

## 의도 메모

- 첨부를 실행 기록에 적는 칸을 두지 않았다. `chat_attachment.message_id` 가 이미 그것을 가리킨다.
- 사진이 없는 메시지에 아무것도 덧붙이지 않는다. 덧붙이면 그만큼이 매 실행에 실린다.
- 에이전트가 볼 수 있게 경로를 알리되 목록을 훑으라고 시키지 않는다.
  파일 이름을 그대로 적어 무엇이 있는지 바로 알게 한다.

## 작업 항목

### 1. 메시지를 보낼 때 첨부 번호를 함께 받는다

`chat/presentation/ChatDtos.java` 의 메시지 요청 record 에 칸 하나를 더한다.
어느 record 인지 아래로 찾는다.

```bash
# cwd: 저장소 root
grep -rn "conversationId" backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java
```

| 칸 | 뜻 |
| --- | --- |
| `attachmentIds` | 함께 보낼 첨부 번호 목록. 없으면 비어 있다 |

**`null` 과 빈 목록을 같게 다룬다.** 화면이 사진을 고르지 않았을 때 어느 쪽으로 보낼지
정하지 않아도 되게 한다.

### 2. `chat/application/ChatService` 가 첨부를 묶는다

`runTurn` 이 지금 `messages.save(ChatMessage.fromUser(...))` 로 메시지를 저장한다.
그 뒤에 `AttachmentService.attach(messageId, attachmentIds)` 를 부른다.

`attach` 의 규칙이다. phase-01 이 만든 메서드이고 그 판정을 여기서 다시 하지 않는다.

- 그 번호가 이 대화의 것이 아니면 거절한다
- 이미 다른 메시지에 묶인 것이면 거절한다
- 지워진 것이면 거절한다
- **하나라도 거절되면 메시지가 나가지 않는다.** 실행을 시작하기 전에 판정한다

**거절은 `VALIDATION_FAILED` 다.** 남의 첨부 번호를 보냈는지 없는 번호를 보냈는지
갈라 알리지 않는다.

### 3. Hermes 에 보내는 입력에 사진 자리를 덧붙인다

`ChatService` 가 `text` 를 그대로 Hermes 에 넘기는 자리를 찾아 그 앞에 붙인다.
그 자리가 어디인지 아래로 확인한다.

```bash
# cwd: 저장소 root
grep -n "text" backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java
```

덧붙이는 모양이다. **사진이 없으면 한 글자도 붙이지 않는다.**

```
[이번 메시지에 올린 사진]
<그 대화의 사진 디렉터리 경로>
- <파일 이름 1>
- <파일 이름 2>

이미지는 read_file 로 읽지 말고 vision_analyze 로 본다.
```

경로는 **에이전트 쪽에서 보이는 경로**다. `AttachmentProperties.root` 는 Control Plane 쪽 경로이고,
두 컨테이너의 마운트 지점이 다를 수 있다.

**그래서 에이전트 쪽 경로를 설정으로 따로 받는다.**
`AttachmentProperties` 에 `agentRoot` 를 더한다. 비면 기동을 실패시킨다.

같은 값을 둘로 두는 것이 아니라 **다른 두 컨테이너가 같은 디렉터리를 다른 이름으로 보는 것**이다.
그 사실을 그 설정의 주석에 적는다.

### 4. 대화를 읽을 때 첨부를 함께 준다

대화 이력을 돌려주는 경로가 메시지마다 그 첨부 목록을 함께 준다.
그 경로가 어디인지 아래로 찾는다.

```bash
# cwd: 저장소 root
grep -rn "conversations" backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java
```

**메시지 한 줄마다 질의를 더하지 않는다.** 그 대화의 첨부를 한 번에 읽어 메시지 번호로 나눈다.

응답의 첨부 한 줄은 phase-01 이 만든 `AttachmentView` 를 그대로 쓴다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java`

| 무엇 | 기대 |
| --- | --- |
| 사진 둘을 붙여 보낸다 | 두 행의 `message_id` 가 그 메시지를 가리킨다 |
| 사진 없이 보낸다 | Hermes 로 간 입력이 사용자가 쓴 것과 정확히 같다 |
| 사진을 붙여 보낸다 | Hermes 로 간 입력에 디렉터리와 파일 이름이 들어 있다 |
| 같은 첨부를 두 메시지에 붙인다 | 둘째가 거절된다. 둘째 메시지가 저장되지 않았다 |
| 남의 대화의 첨부 번호 | 거절. 메시지가 저장되지 않았다 |
| 지워진 첨부 번호 | 거절 |
| 없는 첨부 번호 | 거절. 남의 것과 같은 코드다 |
| 저장된 메시지 본문 | 덧붙인 것이 들어 있지 않다 |

**마지막 줄이 중요하다.** 사람이 쓴 것과 우리가 덧붙인 것이 섞이지 않는 것을 고정한다.

`test/e2e/scenarios/` 에 하나 더한다. 기존 시나리오 파일의 짜임을 따른다.

**파일만 더하면 돌지 않는다.** `test/e2e/run.ts` 의 `SCENARIOS` 배열에 등록해야 돈다.
순서가 뜻을 갖고 `busyScenario` 와 `modelSelectionScenario` 를 뒤에 두는 까닭이 그 파일의 주석에 있다.
**그 둘보다 앞에 넣는다.**

| 무엇 | 기대 |
| --- | --- |
| 사진을 올리고 붙여 보낸다 | Hermes 대역이 받은 입력에 그 파일 이름이 있다 |
| 그 대화를 다시 읽는다 | 그 메시지에 첨부가 달려 있다 |
| 첨부를 지운 뒤 다시 읽는다 | 그 자리가 남고 `visible` 이 거짓이다 |

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

`pnpm build` 가 요구하는 환경 변수는 `web/AGENTS.md` 의 「검사」 절이 갖는다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentProperties.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `test/e2e/scenarios/` | 추가 |

## 끝낸 뒤

`tasks/plan018-chat-photo-upload/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 3으로 올린다.

**배포하지 않는다.** 아직 화면에서 사진을 고를 수 없다.
