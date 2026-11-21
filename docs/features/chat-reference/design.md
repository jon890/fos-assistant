# 대화 답장 기술 설계

스펙: [`spec.md`](spec.md). 이 설계는 PR1(같은 대화 답장)을 자세히 적고, PR2 는 PR1 이 만든 자리에 무엇을 더하는지만 적는다. PR2 의 기술 설계는 PR2 에서 이 파일을 고쳐 더한다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | 화면이 집은 것을 `{messageId, attachmentId?, imagePath?}` 로 들고 있다. 그림이나 사진을 눌렀으면 그 한 장의 번호나 경로가 차고, 글을 눌렀으면 메시지 번호만 찬다. 올린 사진은 크게 보기 화면이 없으므로 넓은 화면에서는 사진 위에 마우스를 올리면 작은 「답장」 단추가 보인다 |
| REQ2 | `MessageActions` 줄에 「답장」 단추를 더하고 저장된 내 메시지(`message-bubble.tsx` 의 사용자 줄)에도 같은 줄을 둔다. 보내는 중인 말풍선(`outgoing-message.tsx`)과 steer 말풍선에는 두지 않는다. 그림 크게 보기 화면에 「답장」 단추를 더한다. 「새 대화에서 시작」 은 PR2 가 더한다(DECISION21) |
| REQ3 | 터치 기기(`(hover: none)`)에서 메시지와 그림에 길게 누르기(500ms) 처리를 붙여 「답장」, 「복사」 메뉴를 연다. 같은 조건에서 메시지 영역의 글 선택과 그림의 브라우저 메뉴(`-webkit-touch-callout`)를 끈다 |
| REQ4 | 입력창 위에 집은 것 칩을 그린다. 글은 앞 두 줄을 CSS 줄 자르기로, 그림은 기존 작은 그림 주소로 보인다. 대화 화면 상태에 하나만 둔다 |
| REQ5 | 보내기 단추 조건은 지금 그대로(글이 있어야 함)다. 집은 것은 대화 화면 상태라 대화를 옮기거나 새로 고치면 사라진다(DECISION20) |
| REQ6 | `chat_message` 에 집은 것을 가리키는 세 칸을 더하고, 이력의 `MessageView` 에 `reply` 를 더해 상자를 그린다. 상자를 누르면 그 메시지 자리로 스크롤하고 잠시 강조한다. 이전 버전이면 버전 넘기기를 그 버전으로 바꾼다(DECISION19) |
| REQ7 | `ChatTurnRunner` 가 질문 줄의 답장 칸을 읽어 실행 입력 글 앞에 「사용자가 집은 메시지」 구역을 넣고, 집은 그림과 사진을 실행 입력 이미지에 더한다 |
| REQ8 | 집은 구역에 그 그림의 가리킴(`attachment:<번호>`, `generated:<제목>`)을 적는다. 그림 도구의 받기 경로([ADR-20261120 / image-source](../../adr/ADR-20261120-image-source.md))는 같은 대화의 것을 이미 받으므로 바꾸지 않는다 |
| REQ9 | 보낼 때 Control Plane 이 집은 것을 검사한다. 그림 파일이 지워졌거나 보관 기간이 지났으면 `REPLY_SOURCE_UNAVAILABLE`(409)로 거절하고 메시지를 저장하지 않는다. 화면은 이 코드와 `MESSAGE_NOT_FOUND` 둘 다 「원래 메시지를 찾을 수 없어요. 다시 집어 주세요」 를 보이고 글과 칩을 입력창에 남긴다. 사용자가 칩을 닫고 다시 집는다 |
| REQ13 | 상자의 작은 그림은 그 그림의 지금 상태(지워짐, 보관 기간 지남)를 따라 그림 스펙 REQ9 의 표시를 쓴다. 같은 대화 안에서는 원래 메시지를 하나씩 지우는 길이 없어 글은 늘 있다 |
| REQ14 | 집은 것의 메시지는 요청 대화(`access.requireOwn` 를 통과한 대화)에 있어야 한다. 다른 대화나 다른 사용자의 번호는 `MESSAGE_NOT_FOUND` 로 같은 응답을 준다 |
| REQ15 | 다시 만들기는 질문 줄의 답장 칸을 그대로 읽으므로 같은 구역이 다시 들어간다. 이때는 검사로 막지 않고 쓸 수 없는 그림만 빼고 그 사실을 구역에 적는다 |
| REQ10 | PR2. 아래 「PR2 에 남긴 자리」 의 첫 보내기 |
| REQ11 | PR2. 아래 「PR2 에 남긴 자리」 의 첨부 사본 |
| REQ12 | PR2. 아래 「PR2 에 남긴 자리」 의 대화 공개 식별자 |

## 책임과 경계

| 모듈 | 맡는 것 |
| --- | --- |
| `chat.reply` | 집은 것의 검사(`ReplyTargets`)와 실행 입력 구역(`ReplyInput`). `chat.turn.application` 이 클래스 상한(rule-75)에 닿아 있어 새 하위 기능으로 둔다([ADR-20261015 / package-layout](../../../backend/docs/adr/ADR-20261015-package-layout.md)) |
| `chat.turn` | 보내기 요청의 `reply` 를 받아 `chat.reply` 로 검사하고, 질문 줄에 칸을 적고, 실행 입력에 구역을 넣는다. 다시 만들기도 같은 길이다 |
| `chat.conversation` | `ChatMessage` 의 세 칸과 이력 질의. 새 질의는 없다 |
| `chat.attachment` | 이번 메시지의 사진, 집은 사진, 집은 그림 바이트를 한 `data:` 예산(`MAX_ENCODED_BYTES`)과 같은 디코딩 제한으로 넣는 메서드를 `AttachmentImages` 에 더한다. 지금의 `photos` 를 두 번 부르면 예산이 두 배가 되고 디코딩 제한을 비켜 간다. 줄이기는 패키지 안의 `AgentImageResizer` 를 쓴다 |
| `chat.artifact` | 그림 행 번호로 원본 바이트를 읽는 함수 하나를 더한다(`GeneratedImageService.readByTitle` 과 같은 읽기). 줄이지 않는다 |
| `web` 대화 화면 | 단추, 길게 누르기 메뉴, 칩, 상자, 스크롤 |

Hermes 와 `fos-ctx` 는 바꾸지 않는다. 실행 입력의 글과 이미지 칸은 지금 형태 그대로 쓴다([`hermes-contract.md`](../../../hermes/docs/hermes-contract.md) 의 `input` 표).

## 인터페이스와 데이터

**보내기 요청.** `SendMessageRequest` 에 선택 칸 `reply` 를 더한다.

```json
{"reply": {"messageId": 123, "attachmentId": 45, "imagePath": null}}
```

- `attachmentId` 와 `imagePath` 는 둘 다 비거나 하나만 찬다. 둘 다 차면 `VALIDATION_FAILED` 다
- `imagePath` 는 이력의 `ArtifactView.path` 이고 그림 행(`generation_call_key` 가 있는 행)만 받는다
- 대화를 새로 만드는 첫 보내기(`conversationId` 없음)에 `reply` 가 있으면 `VALIDATION_FAILED` 다. PR2 가 연다
- `@` 맡김(`delegateTo`)과 함께 오면 `VALIDATION_FAILED` 다. 맡김은 사진을 받지 않는다(`ChatTurnRouting` 의 맡김 검사). 화면은 집은 것이 있으면 맡길 에이전트를 고를 때 집은 것을 비우고, 반대로 집으면 맡길 에이전트 칩을 비운다. 나중에 고른 것이 남는다
- 검사는 첨부 검사(`requireAttachable`)처럼 대화 잠금과 질문 저장 전의 `route()` 에서 한다. 저장 트랜잭션에 넣으면 거절해도 대화방 요약 줄과 session 교체가 먼저 일어난다

| 검사 | 실패 |
| --- | --- |
| 메시지가 이 대화의 `USER` 나 `ASSISTANT` 줄이다. steer 말풍선(`steer_state` 가 찬 줄)은 집지 않는다 | `MESSAGE_NOT_FOUND`(404, 새 오류 코드) |
| 사진은 그 메시지에 묶인 이 대화의 첨부다 | `MESSAGE_NOT_FOUND` |
| 그림은 그 메시지에 묶인 그림 행이다 | `MESSAGE_NOT_FOUND` |
| 사진이 보이고(지우지 않음) 보관 기간 안이다. 그림 파일이 있다 | `REPLY_SOURCE_UNAVAILABLE`(409) |
| 글을 집었으면 그 메시지의 사진과 그림 가운데 쓸 수 없는 것은 빼고 보낸다 | 거절하지 않는다 |

**저장.** `chat_message` 에 세 칸을 더한다. 외래 키는 두지 않는다. 대화를 지우면 줄이 함께 지워지고, PR2 의 다른 대화 줄은 원래가 지워져도 남아야 한다.

| 컬럼 | 뜻 |
| --- | --- |
| `reply_message_id` | 집은 메시지 줄. 사용자 메시지에만 찬다 |
| `reply_attachment_id` | 사진 한 장을 집었으면 그 첨부 |
| `reply_artifact_id` | 그림 한 장을 집었으면 그 그림 행 |

새 테이블을 만들지 않는다. 마이그레이션 번호는 `orch queue reserve` 로 받는다.

**이력.** `MessageView` 에 `reply` 를 더한다.

```json
{"reply": {"messageId": 123, "role": "ASSISTANT", "excerpt": "앞 두 줄", "available": true,
  "images": [{"kind": "GENERATED", "path": "generated/수채화 고양이.png", "attachmentId": null, "deleted": false}]}}
```

- `excerpt` 는 그림 경로를 가린(`MediaPathMask.strip`) 원래 글의 빈 줄이 아닌 앞 두 줄(줄마다 200자)이다. 마크다운 기호를 지우지 않고 화면이 글자로 보인다
- `images` 는 그림 한 장을 집었으면 그 한 장, 글을 집었으면 그 메시지의 사진과 그림이다. 작은 그림은 기존 첨부와 결과물 주소로 그린다
- `available` 은 원래 메시지 줄이 있는지다. PR1 에서는 늘 참이다

**실행 입력.** 질문 글 앞, 사진 참조 구역 뒤에 넣는다.

```text
[사용자가 집은 메시지]
사용자가 이 메시지를 가리키며 아래 글을 보냈다. 「이것」, 「이 그림」 은 이 메시지다.
쓴 사람: 비서
<quoted-message>
...전문...
</quoted-message>
집은 그림: generated:수채화 고양이 (입력에 이미지로 함께 넣었다)
```

- 글은 전문을 넣되 20,000자에서 자르고 잘랐다고 적는다(스펙 DECISION17). Control Plane 에는 입력 글 상한이 없고 Hermes 요청 본문 상한 10MB 만 있다. 이미지 예산 7MB 에 2만 자(한글 UTF-8 약 60KB)를 더해도 지침과 기억 구역의 자리가 남는다. 보내는 글 상한(8000자)의 두 배 넘게 두어 보통 답은 자르지 않는다
- 답 줄의 글은 이력처럼 `MediaPathMask.strip` 으로 그림 경로를 가린 뒤 넣는다. 경로가 남으면 모델이 경로를 그림 도구에 적고 fos-ctx 가 막는다. 상자의 `excerpt` 도 가린 글에서 자른다
- 이미지는 사진 참조와 같은 `data:` 예산(`AttachmentImages.MAX_ENCODED_BYTES`)을 나눠 쓴다. 이번 메시지에 올린 사진을 먼저 넣고 남은 예산으로 집은 그림을 넣는다. 넣지 못한 그림은 가리킴만 적는다
- 집은 메시지에 그림이 여럿이고 사용자 글이 어느 그림인지 말하지 않으면 되묻게 한다는 지침 한 줄을 넣는다(스펙 DECISION22)

## 실패와 일관성

- 검사는 저장 전에 하고 파일 삭제는 트랜잭션에 묶이지 않는다. 검사 뒤 보관 정리가 파일을 지우면 실행 입력을 만들 때 그 그림만 빼고 「쓸 수 없다」 고 적는다. 메시지는 이미 저장됐으므로 실패시키지 않는다
- 답을 만드는 중에는 답장을 보내지 않는다. 대기 줄(`PendingMessageRequest`)은 글만 받고 여러 글을 한 turn 으로 합치므로 메시지마다 집은 것을 붙일 수 없다. 사진 올리기도 같은 까닭으로 도는 중에 막혀 있다(`composer.tsx`). 화면은 칩이 있으면 도는 동안 보내기를 끄고 「답이 끝나면 보낼 수 있어요」 를 보인다(스펙 DECISION18). 대기 줄 요청(`PendingMessageRequest`)에는 `reply` 칸이 없다
- 화면이 도는 중임을 모른 채 보냈다가 `CONVERSATION_BUSY` 를 받으면 지금은 글만 대기 줄에 넣는다(`use-conversation-send.ts`). 집은 것이 있으면 대기 줄로 옮기지 않고 글을 입력창에 되돌린다. 집은 것은 보내기가 받아들여졌을 때만 비우므로 실패하면 그대로 남는다
- 옛 화면이 `reply` 없이 보내면 지금과 같다. 새 칸은 모두 NULL 을 받아 배포 순서가 없다

## 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ1, REQ4, REQ5, REQ6 | 화면 단위 테스트(칩, 상자, 보내기 조건)와 브라우저 테스트 하나(답에 마우스를 올려 「답장」, 보내고 상자를 눌러 스크롤) |
| REQ2, REQ3 | 브라우저 테스트의 넓은 폭과 휴대폰 폭(터치) |
| REQ7, REQ8, REQ15 | Control Plane 통합 테스트. 가짜 Hermes 가 받은 입력의 구역과 이미지를 본다. 다시 만들기도 같은 테스트에서 본다 |
| REQ9, REQ14 | Control Plane 통합 테스트. 다른 대화와 다른 사용자의 번호, 지운 그림 |
| REQ13 | 화면 단위 테스트(지운 그림의 상자) |

실제 비서가 집은 그림을 보고 다시 그리는 왕복은 배포 뒤 운영에서 사용자가 확인한다.

## PR2 에 남긴 자리

- 첫 보내기(`conversationId` 없음)의 `reply` 를 열고, 원래 대화가 요청자 것인지 Control Plane 이 확인한다
- 다른 대화의 글은 지울 수 있으므로 상자 글의 사본 칸이 더 필요하다. 본문과 같이 대화 주인의 데이터 key 로 암호화하고, AAD 에 줄 번호와 칸 이름을 넣으며 옆에 `_key_id` 칸을 둔다([`data-schema.md`](../../../backend/docs/data-schema.md) 의 「chat_message」). 그림과 사진은 새 대화의 첨부 사본(`chat_attachment.source_artifact_id` 의 선례)으로 만든다
- `reply_message_id` 가 다른 대화의 줄을 가리키게 되므로 이력이 대화 공개 식별자를 함께 내야 상자가 그 대화를 연다

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 집은 것을 두는 자리 | `chat_message` 의 세 칸 | 작성자 | 새 테이블을 만들지 않는다. 한 메시지에 하나라(스펙 DECISION7) 목록이 필요 없고, 다시 만들기가 질문 줄만 읽으면 된다 |
| DECISION2 | 그림을 가리키는 값 | 요청과 이력은 `path`, 저장은 그림 행 번호 | 작성자 | 이력의 `ArtifactView` 가 번호 없이 경로로 그림을 가리킨다. 저장은 경로가 바뀌어도 그 행을 가리키게 번호로 둔다 |
| DECISION3 | 집은 그림을 모델에게 보이는 법 | 실행 입력 이미지에 줄인 사본으로 넣는다 | 작성자 | 이번 메시지 사진과 같은 길이라 모델이 바로 본다. 바탕으로 쓰는 원본은 그림 도구가 가리킴으로 따로 받는다 |
| DECISION4 | 도는 중의 답장 | 보내지 않고 답이 끝나기를 기다리게 한다. 스펙 DECISION18 을 따른다 | 작성자 | 대기 줄이 글만 받고 합친다. 사진 올리기와 같은 규칙이다 |
| DECISION5 | 휴대폰 메뉴를 쓰는 기준 | 화면 폭이 아니라 마우스 없는 터치 기기(`hover: none`) | 작성자 | 넓은 태블릿도 마우스를 올릴 수 없다. 폭이 좁은 컴퓨터 창은 마우스로 단추 줄을 쓴다 |
| DECISION6 | 상자의 글 | 서버가 앞 두 줄을 잘라 이력에 넣는다 | 작성자 | 집은 메시지가 화면에 아직 읽히지 않았을 수 있고, PR2 의 다른 대화 상자도 같은 칸을 쓴다 |
| DECISION7 | 인용 전문의 상한 | 20,000자 | 작성자 | Hermes 요청 본문 10MB 안에 이미지 예산 7MB 와 함께 들어가고, 보내는 글 상한의 두 배 넘게 둔다 |
| DECISION8 | 답장과 `@` 맡김 | 함께 보내지 않는다. 나중에 고른 것이 남는다 | 작성자 | 맡김은 사진을 받지 않아 그림이 조용히 빠진다 |
| DECISION9 | 이미지 예산 | 이번 메시지 사진, 집은 사진과 그림이 한 예산을 나눠 쓴다. 이번 메시지 사진이 먼저다 | 작성자 | 사진 첨부의 예산 근거([ADR-20261009 / native-image-input](../../../backend/docs/adr/ADR-20261009-native-image-input.md))를 그대로 쓴다 |

## 결정 기록 연결

| 결정 | 종류 | ADR 또는 필요 없음의 이유 | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| DECISION1~DECISION9 | implementation | 필요 없음. 기존 표에 칸을 더하고 기존 입력 길을 쓴다. 되돌리기 쉽다 | 이 설계와 [`data-schema.md`](../../../backend/docs/data-schema.md) 의 「chat_message」 |
