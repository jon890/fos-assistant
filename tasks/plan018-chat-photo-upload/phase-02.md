# Phase 02. 사진이 놓인 자리를 에이전트에게 알린다

**Execution profile**: deep

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

**인자가 지나가는 길이다.** `ChatController` 의 `send` 와 `stream` 이 `request.attachmentIds()` 를 넘기고,
`ChatService.send` 와 `ChatService.stream` 이 `List<Long> attachmentIds` 인자를 하나 더 받는다.
기존 네 인자 메서드는 빈 목록을 넘기는 오버로드로 남긴다. 테스트의 기존 호출을 고치지 않는다.

판정은 phase-01 이 만든 `AttachmentService.requireAttachable` 과 `attach` 가 갖는다. 여기서 다시 하지 않는다.
**하나라도 거절되면 메시지가 저장되지 않고 대화도 새로 생기지 않는다.** 그래서 순서가 정해진다.

1. 첨부가 있는데 `conversationId` 가 비었으면 `VALIDATION_FAILED`.
   첨부는 대화에 올리므로 첨부가 있으면 대화 번호도 있다. 화면은 첫 사진을 올릴 때
   phase-01 의 빈 대화 경로로 번호를 먼저 받는다. `resolveConversation` 이 대화를 만들기 전에 판정한다
2. `route` 로 대화와 에이전트를 정한다
3. 첨부가 있는데 그 에이전트에 흐름이 붙었으면 `VALIDATION_FAILED`.
   흐름은 `runTurn` 을 거치지 않아 사진 자리를 덧붙일 수 없다. 오류 없이 무시되게 두지 않는다
4. `requireAttachable(conversationId, attachmentIds)`
5. `runTurn` 이 메시지를 저장하고 `attach(messageId, conversationId, attachmentIds)` 를 부른다.
   **둘을 `TransactionTemplate` 으로 한 트랜잭션에 묶는다.** `attach` 가 동시 요청에 걸려 던지면
   메시지 저장도 함께 되돌린다. Hermes 호출은 그 트랜잭션 밖이다

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
사진 자리를 사용자가 쓴 글 **앞에** 두고 빈 줄 하나로 나눈다.

```
[이번 메시지에 올린 사진]
<agentRoot>/<대화 번호>
- <디스크 이름 1> (올린 이름: <올릴 때의 이름 1>)
- <디스크 이름 2> (올린 이름: <올릴 때의 이름 2>)

이미지는 read_file 로 읽지 말고 vision_analyze 로 본다.

<사용자가 쓴 글>
```

**파일 이름은 디스크 이름 `{첨부 번호}.{확장자}` 다.** 에이전트가 그 이름으로 파일을 연다.
올릴 때의 이름은 무엇인지 알아보게 괄호로만 덧붙인다. 그 이름으로는 파일을 찾지 못한다.

덧붙인 입력은 `begin` 이 만드는 `HermesRunCommand` 의 `input` 에만 들어간다.
provider 가 막혀 다음 모델로 넘어가는 시도도 같은 입력을 쓴다.

경로는 **에이전트 쪽에서 보이는 경로**다. `AttachmentProperties.root` 는 Control Plane 쪽 경로이고,
두 컨테이너의 마운트 지점이 다를 수 있다.

**그래서 에이전트 쪽 경로를 설정으로 따로 받는다.**
`AttachmentProperties` 에 `agentRoot` 를 더한다. 비면 기동을 실패시킨다.
`root` 와 같이 compact constructor 가 빈 문자열도 막는다.
`application.yml` 은 `agent-root: ${ASSISTANT_ATTACHMENT_AGENT_ROOT}` 로 받는다.

같은 값을 둘로 두는 것이 아니라 **다른 두 컨테이너가 같은 디렉터리를 다른 이름으로 보는 것**이다.
그 사실을 그 설정의 주석에 적는다.

기본값이 없으므로 검사에도 값을 준다. phase-01 이 `root` 를 준 세 곳과 같다.

| 파일 | 값 |
| --- | --- |
| `backend/src/test/resources/application-test.yml` | `/agent-side/attachments`. 검사는 그 경로를 열지 않고 입력에 적힌 글자만 본다 |
| `test/e2e/run.ts` | 같은 글자. `ASSISTANT_ATTACHMENT_AGENT_ROOT` 로 넘긴다 |
| `test/browser/fixtures.ts` | 같다 |

### 4. 대화를 읽을 때 첨부를 함께 준다

대화 이력을 돌려주는 경로가 메시지마다 그 첨부 목록을 함께 준다.
그 경로가 어디인지 아래로 찾는다.

```bash
# cwd: 저장소 root
grep -rn "conversations" backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java
```

**메시지 한 줄마다 질의를 더하지 않는다.** phase-01 의 `allOf(conversationId)` 로 한 번에 읽어 메시지 번호로 나눈다.
지워진 첨부도 담는다. 그 자리를 남겨야 한다.

`MessageView` 에 `attachments` 칸을 더한다. 첨부가 없으면 빈 목록이다.
응답의 첨부 한 줄은 phase-01 이 만든 `AttachmentView` 를 그대로 쓴다.

### 4-1. 화면이 사진 단추를 둘지 알게 한다

대화 화면은 `/api/v1/agents` 로 사용자용 `AgentView` 를 받는다. 거기에는 흐름 여부가 없다.
`agent/presentation/AgentDtos.java` 의 `AgentView` 에 `acceptsAttachments` 참거짓 칸을 더한다.
그 에이전트에 흐름이 붙지 않았으면 참이다. 흐름 이름 자체는 내보내지 않는다.
값은 phase-01 이 만든 `Agent.acceptsAttachments()` 다. 위 2번의 거절도 그 메서드를 부른다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java`

| 무엇 | 기대 |
| --- | --- |
| 사진 둘을 붙여 보낸다 | 두 행의 `message_id` 가 그 메시지를 가리킨다 |
| 사진 없이 보낸다 | Hermes 로 간 입력이 사용자가 쓴 것과 정확히 같다 |
| 사진을 붙여 보낸다 | Hermes 로 간 입력에 `agentRoot/대화 번호` 와 **디스크 이름**이 들어 있다 |
| 같은 첨부를 두 메시지에 붙인다 | 둘째가 거절된다. 둘째 메시지가 저장되지 않았다 |
| 남의 대화의 첨부 번호 | 거절. 메시지가 저장되지 않았다 |
| 지워진 첨부 번호 | 거절 |
| 없는 첨부 번호 | 거절. 남의 것과 같은 코드다 |
| 저장된 메시지 본문 | 덧붙인 것이 들어 있지 않다 |
| 대화 번호 없이 첨부를 붙여 보낸다 | 거절. 대화가 새로 생기지 않았다 |
| 흐름이 붙은 에이전트의 대화에 첨부를 붙여 보낸다 | 거절. 메시지가 저장되지 않았다 |
| 대화 이력을 읽는다 | 그 메시지에 첨부 둘이 달리고, 다른 메시지는 빈 목록이다 |
| 에이전트 목록을 읽는다 | 흐름이 붙은 에이전트만 `acceptsAttachments` 가 거짓이다 |

**「저장된 메시지 본문」 줄이 중요하다.** 사람이 쓴 것과 우리가 덧붙인 것이 섞이지 않는 것을 고정한다.

`test/e2e/scenarios/` 에 하나 더한다. 기존 시나리오 파일의 짜임을 따른다.

시나리오를 쓰려면 대역과 하네스에 두 가지가 더 필요하다. 지금은 없다.

- `test/e2e/fake-hermes.ts` 에 `lastSubmittedInput()` 을 더한다. `lastSubmittedInstructions()` 와 같은 방식이다
- `test/e2e/harness.ts` 에 multipart 로 올리는 도우미를 더한다. 지금 `call` 은 JSON 본문만 보낸다.
  작은 PNG 바이트를 검사 안에서 만들어 올린다. 저장소에 이미지를 넣지 않는다

**파일만 더하면 돌지 않는다.** `test/e2e/run.ts` 의 `SCENARIOS` 배열에 등록해야 돈다.
순서가 뜻을 갖고 `busyScenario` 와 `modelSelectionScenario` 를 뒤에 두는 까닭이 그 파일의 주석에 있다.
**그 둘보다 앞에 넣는다.**

| 무엇 | 기대 |
| --- | --- |
| 사진을 올리고 붙여 보낸다 | Hermes 대역이 받은 입력에 그 파일 이름이 있다 |
| 그 대화를 다시 읽는다 | 그 메시지에 첨부가 달려 있다 |
| 첨부를 지운 뒤 다시 읽는다 | 그 자리가 남고 `visible` 이 거짓이다 |
| 지운 첨부의 본문을 읽는다 | 410 `ATTACHMENT_GONE` |

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
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정. `acceptsAttachments` |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/harness.ts` | 수정 |
| `test/browser/fixtures.ts` | 수정. 환경 변수 하나 |
| `test/e2e/scenarios/` | 추가 |

## 끝낸 뒤

`tasks/plan018-chat-photo-upload/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 3으로 올린다.

**배포하지 않는다.** 아직 화면에서 사진을 고를 수 없다.
