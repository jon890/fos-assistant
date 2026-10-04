# 할 일과 먼저 알리기

할 일, 사용자의 숨기기와 미루기, 먼저 알리기의 지표 사건을 저장하는 표의 칸과 제약을 갖는다.
뜻과 판정은 [`../follow-up.md`](../follow-up.md) 와 [`../attention.md`](../attention.md) 가 갖는다.

세 표는 아직 마이그레이션이 없다. 표마다 그 절의 「아직 구현 전이다」 줄을 표를 만든 PR 이 지운다.

## follow_up

**아직 구현 전이다.**

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `public_id` | BINARY(16) UNIQUE | API 와 화면에 쓰는 UUID |
| `user_id` | BIGINT | 주인. 다른 사용자는 읽지 못한다 |
| `conversation_id` | BIGINT NULL | 연결한 대화. 에이전트의 제안은 그 실행의 대화다. 직접 더할 때 고르지 않았으면 비어 있다 |
| `proposed_by_execution_id` | BIGINT NULL | 제안한 실행. 사람이 직접 더했으면 비어 있다 |
| `title` | VARCHAR(200) | 한 줄. 평문이다 |
| `title_key` | CHAR(64) | 정규화한 제목의 SHA-256 16진수 |
| `due_at` | DATETIME(6) NULL | 기한 |
| `waiting` | BOOLEAN NOT NULL DEFAULT FALSE | 기다리는 중 |
| `status` | VARCHAR(20) | `PROPOSED`, `OPEN`, `DONE`, `DROPPED`, `REJECTED` |
| `open_marker` | TINYINT NULL | `PROPOSED` 와 `OPEN` 이면 1, 끝난 상태면 비어 있다. 유일 제약에만 쓴다 |
| `created_at`, `updated_at` | DATETIME(6) | |
| `accepted_at` | DATETIME(6) NULL | 받아들이거나 직접 더한 시각 |
| `closed_at` | DATETIME(6) NULL | `DONE`, `DROPPED`, `REJECTED` 가 된 시각 |

| 제약 | 칸 | 막는 것 |
| --- | --- | --- |
| `uk_follow_up_open_title` | `user_id`, `title_key`, `open_marker` | 같은 할 일이 열린 채 둘이 되는 것. 끝난 줄은 `open_marker` 가 비어 걸리지 않는다 |

색인은 `(user_id, status)` 와 `(conversation_id, status)` 에 둔다.
대화를 지워도 줄은 남는다. 대화는 숨기기만 하기 때문이다.

## attention_control

**아직 구현 전이다.**

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `user_id` | BIGINT | |
| `card_key` | VARCHAR(16) | 제어를 건 카드. `failures`, `needs_me`, `delegated`, `continue` |
| `item_key` | VARCHAR(80) | 판정의 `itemKey` |
| `action` | VARCHAR(16) | `HIDE`, `SNOOZE` |
| `state_key` | VARCHAR(64) NULL | `HIDE` 일 때 숨긴 상태. 판정의 `stateKey` 가 이것과 다르면 다시 보인다 |
| `until_at` | DATETIME(6) NULL | `SNOOZE` 의 기한 |
| `created_at`, `updated_at` | DATETIME(6) | |

| 제약 | 칸 | 막는 것 |
| --- | --- | --- |
| `uk_attention_control_item` | `user_id`, `card_key`, `item_key` | 한 카드의 한 항목에 제어가 둘 생기는 것. 새 제어는 그 줄을 고친다. 같은 대화가 두 카드에 나와도 제어가 서로 덮어쓰지 않는다 |

## attention_event

**아직 구현 전이다.**

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `user_id` | BIGINT | |
| `item_key` | VARCHAR(80) | |
| `state_key` | VARCHAR(64) | |
| `trigger_type` | VARCHAR(32) | 판정의 `trigger` |
| `attention` | VARCHAR(16) | 그때의 판정. `NOW`, `LATER` |
| `event_type` | VARCHAR(16) | `SHOWN`, `OPENED`, `ACTED`, `HIDDEN`, `SNOOZED` |
| `stale` | BOOLEAN NOT NULL DEFAULT FALSE | 그때 출처의 신선도가 `STALE` 이었다 |
| `created_at` | DATETIME(6) | |

| 제약 | 칸 | 막는 것 |
| --- | --- | --- |
| `uk_attention_event_once` | `user_id`, `item_key`, `state_key`, `event_type`, `attention` | 화면을 열 때마다 같은 사건이 쌓이는 것. 같은 상태에서 `LATER` 가 `NOW` 로 바뀌면 `NOW` 의 `SHOWN` 이 따로 남는다 |

`created_at` 에 색인을 둔다. 보관 기간이 지난 줄을 지울 때 쓴다.
제목과 본문을 담는 칸이 없다.
