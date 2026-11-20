# Phase 01. 주소 비교 값 객체를 꺼낸다

**Execution profile**: standard

## 목표

`ConversationLinks` 안의 주소 비교 record(`Target`)를 같은 패키지의 값 객체로 옮긴다. 다음 phase 의 「비서가 찾은 주소」 판정이 같은 규칙을 쓰기 위해서다. 동작은 바꾸지 않는다.

**범위 외**: 새 판정, 새 칸, 새 경로. phase 2 가 한다.

## 컨텍스트

`backend/src/main/java/com/bifos/assistant/chat/conversation/application/ConversationLinks.java` 의 `private record Target` 이 가리킨 주소를 비교 글(호스트, 다른 표기 호스트, 경로와 쿼리)과 받을 주소로 나눈다. `comparable`, `trimWord`, `urlPrefix` 와 상수(`SCHEME`, `URL_CHARACTERS`, `TRAILING`, `LEADING`)를 함께 쓴다.
이 저장소는 이동만 하는 커밋과 동작을 바꾸는 커밋을 나눈다(루트 `AGENTS.md` 「머지는 PR 로 한다」).

**요구사항**: REQ2

**근거 문서**: `docs/features/agent-found-image/design.md` 의 「같은 주소로 보는 규칙」

## 의도 메모

- 새 클래스 이름은 `LinkTarget` 이고 패키지는 `chat.conversation.application` 그대로다. 패키지 밖에서 쓰지 않으므로 package-private 으로 둔다
- 메서드의 글자와 동작을 그대로 옮긴다. 다음 phase 가 쓸 새 메서드는 여기서 더하지 않는다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/chat/conversation/application/LinkTarget.java` 를 만든다

`ConversationLinks.Target` 과 그것만 쓰는 정적 도우미(`comparable`, `isHex`, `trimWord`, `urlPrefix`, `indexOfAny`, `asciiEncoded`, `otherSpelling`)와 상수를 옮긴다. `of(String)`, `matches(String)`, `fetchUri()` 의 시그니처는 그대로다.

### 2. `backend/src/main/java/com/bifos/assistant/chat/conversation/application/ConversationLinks.java` 가 `LinkTarget` 을 쓴다

`findSentLink` 는 `LinkTarget.of(link)` 를 부른다. 클래스 머리 설명의 규칙 서술은 `LinkTarget` 으로 옮기고 여기서는 가리키기만 한다.

### 3. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/conversation/application/ConversationLinksTest.java`

기존 테스트를 고치지 않고 그대로 통과시킨다. `ConversationLinks.comparable` 을 직접 부르는 테스트가 있으면 `LinkTarget.comparable` 로 바꾼다.

## 변경 파일

| 파일 | 종류 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/application/LinkTarget.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/application/ConversationLinks.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/conversation/application/ConversationLinksTest.java` | 수정 |

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.conversation.application.ConversationLinksTest' --tests 'com.bifos.assistant.mcp.ImageSourceEndpointTest'
```

두 테스트가 모두 통과한다. 같은 맥에서 다른 작업이 돌면 무거운 검사 대기열(`orch slot heavy --`)로 감싼다.
