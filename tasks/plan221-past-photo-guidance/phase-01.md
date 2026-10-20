# Phase 01. 실행 입력이 이번 메시지 사진과 지난 사진 수를 나눠 적는다

**Execution profile**: standard

## 목표

`AttachmentService.agentInput` 의 사진 안내가 이번 메시지의 사진 수만 「사진은 모두 N장이다」 로 적어, 지난 사진만 있는 turn 에 「사진은 모두 0장이다」 가 실린다.
이번 메시지의 사진과 지난 사진을 나눠 적고, 지난 사진만 있으면 `attachment_inspect` 로 직접 보라고 안내한다.

**범위 외**: 블로그 스킬 문장(phase 02). 원본 조회 도구의 동작과 한도. 사진을 이미지로 싣는 규칙.

## 컨텍스트

- 안내의 규칙은 `docs/features/attachment.md` 의 「실행 입력에 사진을 싣는 법」 의 안내 표가 갖는다. 이 phase 는 그 표대로 구현한다
- 안내는 `backend/src/main/java/com/bifos/assistant/chat/attachment/application/AttachmentService.java` 의 `private static String photoGuidance(List<AgentPhoto> photos)` 가 만든다. `photos` 는 이번 메시지의 첨부(`attached`)에서 만든 것이라, 이번 메시지에 사진이 없으면 빈 목록이다
- `agentInput` 안의 `sent` 는 이 대화에서 메시지에 묶인 모든 첨부다. 이번 메시지의 첨부도 이미 묶여 들어 있다. 지난 사진은 `sent` 가운데 `attached` 의 번호에 없는 첨부다. 보관 중인지는 `inspectable(ChatAttachment)` 가 판정한다
- `agentInput` 은 지난 사진 가운데 보관 중인 것의 번호 목록을 만들어 `MediaPipelineEvents.referenced(conversationId, 번호 목록)` 에 넘긴다. 지난 사진 수는 그 목록의 크기를 쓴다. 같은 첨부에 `inspectable` 을 다시 부르지 않는다
- 지금 문구를 단언하는 테스트: `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` 의 250줄(「사진은 모두 30장이다.」)과 333줄(「사진은 모두 2장이다.」), `backend/src/test/java/com/bifos/assistant/chat/AttachmentDeletionBarrierTest.java` 의 471줄(「사진은 모두 1장」)이다. `test/e2e/scenarios/chat-attachment.ts` 는 「이 메시지에 이미지로 함께 실은 사진: 1번째 사진.」 을 단언하므로 그 문장은 바꾸지 않는다

**근거 문서**: `docs/features/attachment.md` 의 「실행 입력에 사진을 싣는 법」

## 의도 메모

- 2026-10-20 운영 기록에서 사진 참조 목록에 67장이 있는 turn 에 「사진은 모두 0장이다」 가 실렸고, 모델은 지난 사진을 조회하지 않고 사용자에게 사진 설명을 부탁했다. 「모두」 는 대화 전체로 읽히므로 쓰지 않는다
- 안내는 작업 종류에 묶지 않는다. 블로그라는 말을 넣지 않는다. 블로그의 절차는 스킬이 갖는다
- 「이 메시지에 이미지로 함께 실은 사진」 과 「입력에 싣지 못한 사진」 문장은 그대로 둔다. e2e 와 기존 테스트가 그 문장에 기대고, 뜻도 이번 메시지에 한정돼 있다

## Blocked 조건

- `MediaPipelineEvents` 에 `referenced` 메서드가 없다 → `PHASE_BLOCKED: 지난 사진 참조 단계 사건이 먼저 main 에 있어야 한다. 블로그 사진 평가의 기준값을 잰 뒤에 이 phase 를 한다` 출력 후 종료

## 작업 항목

### 1. `AttachmentService.photoGuidance` 를 바꾼다

`backend/src/main/java/com/bifos/assistant/chat/attachment/application/AttachmentService.java`

- 시그니처를 `private static String photoGuidance(List<AgentPhoto> photos, int pastCount)` 로 바꾸고 `agentInput` 이 지난 사진 번호 목록의 크기를 넘긴다
- `photos` 가 있으면 첫 줄을 `이번 메시지의 사진은 K장이다.` 로 쓴다. 이어서 지금의 「이 메시지에 이미지로 함께 실은 사진」, 「입력에 싣지 못한 사진」 문장을 그대로 쓴다. `pastCount` 가 1 이상이면 `이 대화에는 앞서 보낸 사진 P장도 있다. 지난 사진이 필요하면 위 참조의 attachment_id로 attachment_inspect를 불러 본다.` 를 더한다
- `photos` 가 비고 `pastCount` 가 1 이상이면 다음 세 줄을 쓴다
  - `이번 메시지에 새로 붙인 사진은 없다. 이 대화에는 앞서 보낸 사진 P장이 있다.`
  - `사진을 근거로 답하거나 글을 써야 하면 위 참조의 attachment_id로 attachment_inspect를 불러 필요한 사진을 직접 본다.`
  - `보지 않은 사진의 내용을 사용자에게 설명해 달라고 하지 않는다.`
- `photos` 가 비고 `pastCount` 가 0 이면(보낸 사진이 모두 보관 종료) `이번 메시지에 새로 붙인 사진은 없다. 앞서 보낸 사진은 모두 보관이 끝나 볼 수 없다.` 한 줄을 쓴다
- 메서드 Javadoc 에 이번 메시지와 지난 사진을 나눠 적는 까닭을 한 문장으로 적는다

### 2. 이 phase 를 검증하는 테스트

- `ChatAttachmentTurnTest` 250줄과 333줄의 기대 문장을 `이번 메시지의 사진은 30장이다.`, `이번 메시지의 사진은 2장이다.` 로 바꾼다
- `AttachmentDeletionBarrierTest` 471줄의 기대 문장을 `이번 메시지의 사진은 1장` 으로 바꾼다
- `ChatAttachmentTurnTest` 의 `nextTextTurnRestoresPastReferences` 에 단언을 더한다. 두 번째 입력이 `이번 메시지에 새로 붙인 사진은 없다. 이 대화에는 앞서 보낸 사진 1장이 있다.` 와 `보지 않은 사진의 내용을 사용자에게 설명해 달라고 하지 않는다.` 를 담고, `사진은 모두` 와 `0장` 을 담지 않는다
- 같은 테스트 파일에 사례 둘을 더한다
  - 앞 메시지에 사진 한 장, 이번 메시지에 사진 한 장을 보내면 입력이 `이번 메시지의 사진은 1장이다.` 와 `이 대화에는 앞서 보낸 사진 1장도 있다.` 를 담는다
  - 앞 메시지의 사진을 지운 뒤 사진 없이 보내면 입력이 `앞서 보낸 사진은 모두 보관이 끝나 볼 수 없다.` 를 담는다. 첨부를 지우는 방법은 같은 파일의 기존 삭제 사례를 따른다

## 검증

```bash
cd backend && ./gradlew test --tests '*ChatAttachmentTurnTest' --tests '*AttachmentDeletionBarrierTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
! git grep -n "사진은 모두" -- backend/src/main
```

모두 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/attachment/application/AttachmentService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentDeletionBarrierTest.java` | 수정 |
