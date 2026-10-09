# 할 일과 먼저 알리기

할 일, 사용자의 숨기기와 미루기, 먼저 알리기의 지표 사건을 저장하는 표에서 마이그레이션만 읽어서는 알 수 없는 칸의 뜻과 제약의 까닭을 갖는다.
칸과 타입, 색인은 `V71__attention_control_event.sql` 과 `V72__follow_up.sql` 이 갖는다.
뜻과 판정은 [`../follow-up.md`](../follow-up.md) 와 [`../attention.md`](../attention.md) 가 갖는다.

## follow_up

- `conversation_id` 는 연결한 대화다. 에이전트의 제안은 그 실행의 대화이고, 직접 더할 때 고르지 않았으면 비어 있다.
- `proposed_by_execution_id` 는 제안한 실행이다. 사람이 직접 더했으면 비어 있다.
- `title_key` 는 정규화한 제목의 SHA-256 16진수다. 같은 할 일인지 이 칸으로 본다.
- `accepted_at` 은 받아들이거나 직접 더한 시각이고, `closed_at` 은 끝난 상태가 된 시각이다.

**`uk_follow_up_open_title`(`user_id`, `title_key`, `open_marker`)은 같은 할 일이 열린 채 둘이 되는 것을 막는다.**
`open_marker` 는 `PROPOSED` 와 `OPEN` 이면 1 이고 끝난 상태면 비어 있다. 유일 제약은 비어 있는 값을 서로 다르다고 보므로 끝난 줄은 걸리지 않는다. 이 칸은 유일 제약에만 쓴다.

외래 키는 사용자(`fk_follow_up_user`)에만 둔다. 대화와 실행이 지워져도 줄은 남는다. 대화는 숨기기만 하기 때문이다.

## attention_control

한 사용자의 한 카드의 한 `itemKey` 에 한 줄이다. 새 제어는 그 줄을 고친다.
**유일 제약 `uk_attention_control_item` 에 `card_key` 를 넣어 카드마다 제어를 나눈다.** 같은 대화가 두 카드에 나와도 제어가 서로 덮어쓰지 않는다.
`card_key` 는 API 의 소문자 카드 열쇠를 대문자로 쓴 값이고, 값의 목록은 `CardKey` 가 갖는다.
`state_key` 는 `HIDE` 일 때 숨긴 상태다. 판정의 `stateKey` 가 이것과 다르면 다시 보인다. `until_at` 은 `SNOOZE` 의 기한이다.

## attention_event

**유일 제약 `uk_attention_event_once`(`user_id`, `item_key`, `state_key`, `event_type`, `attention`)는 화면을 열 때마다 같은 사건이 쌓이는 것을 막는다.**
판정 칸이 제약에 들어 있어, 같은 상태에서 `LATER` 가 `NOW` 로 바뀌면 `NOW` 의 `SHOWN` 이 따로 남는다.
`stale` 은 그때 출처의 신선도가 `STALE` 이었다는 뜻이다.

`attention` 은 아래를 차례로 보고 처음 맞는 것을 쓴다.

1. 지금 응답에 그 항목이 있으면 그 판정이다. `HIDDEN`, `SNOOZED` 는 요청의 카드 안에서만 찾는다.
2. 없으면 그 요청자가 그 항목으로 남긴 가장 최근 `SHOWN` 의 판정이다. `OPENED`, `ACTED` 는 `stateKey` 까지 같은 `SHOWN` 만 보고, `HIDDEN`, `SNOOZED` 는 상태와 상관없이 본다.
3. 그것도 없고 숨겼거나 미뤄 억제 전 후보에만 있으면 `SUPPRESSED` 다.

그래서 한 번 보였다가 숨긴 항목의 사건은 `SUPPRESSED` 가 아니라 그때 보인 `NOW` 나 `LATER` 로 남는다.

`created_at` 의 색인은 보관 기간이 지난 줄을 지울 때 쓴다.
제목과 본문을 담는 칸이 없다.
