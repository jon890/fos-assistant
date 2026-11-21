# 대화 답장

사용자가 대화의 메시지나 그림을 집어 「답장」 해서, 비서가 집은 글과 그림을 보고 답하게 하는 기능이다.

covers: `backend/src/main/java/com/bifos/assistant/chat/reply/`, `web/src/components/chat/reply-*`, `web/src/components/chat/use-reply-target.ts`, `web/src/lib/reply.ts`

요구와 결정은 스펙 [`chat-reference/spec.md`](chat-reference/spec.md), 구조는 설계 [`chat-reference/design.md`](chat-reference/design.md) 가 갖는다.
지금은 같은 대화 안의 답장(PR1)이 있다. 「새 대화에서 시작」 과 지난 대화의 그림 사본(PR2)은 아직 없다.
지금의 대화 화면은 [대화](chat.md), 그림을 바탕과 참고로 쓰는 길은 [비서가 만든 이미지](generated-image.md), 사진 첨부와 결과물 파일의 보관 규칙은 [첨부](attachment.md) 가 갖는다.

## 보내기와 검사

흐르는 보내기(`POST /api/v1/chat/messages/stream`)가 `reply: {messageId, attachmentId?, imagePath?}` 를 받는다. 흐르지 않는 보내기는 `reply` 가 있으면 `VALIDATION_FAILED` 다.
`chat.reply` 의 `ReplyTargets` 가 대화를 잠그고 질문을 저장하기 전에 검사한다.

| 경우 | 결과 |
| --- | --- |
| 없는 메시지, 다른 대화나 다른 사용자의 메시지, 알림 줄, steer 말풍선, 그 메시지의 것이 아닌 사진이나 그림 | `MESSAGE_NOT_FOUND`(404). 아무것도 저장하지 않는다 |
| 집은 사진의 보관 기간이 지났거나 지워졌다. 집은 그림 파일이 없다 | `REPLY_SOURCE_UNAVAILABLE`(409). 아무것도 저장하지 않는다 |
| 대화 없는 첫 보내기, `@` 맡김과 함께, 사진과 그림을 함께 집었다 | `VALIDATION_FAILED` |

통과하면 질문 줄의 세 칸에 적는다([`data-schema.md`](../../backend/docs/data-schema.md) 의 「chat_message」).
답을 만드는 중에는 화면이 답장을 보내지 않는다. 대기 줄은 글만 받기 때문이다(스펙 DECISION18).

## 실행 입력

`ReplyInput` 이 사진 참조 구역 뒤, 질문 글 앞에 「[사용자가 집은 메시지]」 구역을 넣는다.

- 쓴 사람(사용자, 비서)과 전문을 `<quoted-message>` 로 감싼다. 답 글은 이력처럼 그림 경로를 가리고 20,000자에서 자른다
- 집은 사진과 그림은 `attachment:<첨부 번호>`, `generated:<제목>` 가리킴과 함께 적는다. 그림 도구는 이 가리킴으로 원본을 받는다
- 집은 사진과 그림의 줄인 사본은 이번 메시지 사진과 한 예산으로 입력 이미지에 넣고 이름표는 「집은 그림 N」 이다. 이번 메시지 사진이 먼저 담긴다
- 여럿이면 어느 것인지 되묻게 한다
- 다시 만들기는 질문 줄의 칸으로 같은 구역을 만든다. 그사이 쓸 수 없게 된 사진과 그림은 빼고 쓸 수 없다고 적는다

## 이력의 상자

이력의 사용자 메시지에 `reply: {messageId, role, excerpt, available, images}` 가 붙는다. `excerpt` 는 그림 경로를 가린 원래 글의 빈 줄이 아닌 앞 두 줄이다. `images` 는 집은 한 장, 또는 메시지 전체를 집었으면 그 메시지의 사진과 그림이고 지워진 것은 `deleted` 다.
