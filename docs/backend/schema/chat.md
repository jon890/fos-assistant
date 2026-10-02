# 대화

대화와 메시지, 대기 메시지, 사진 첨부, 결과물을 저장하는 표 다섯의 칸과 제약을 갖는다.
이 표들을 읽고 쓰는 경로는 [`backend/conversation.md`](../conversation.md) 와 그 옆의 문서들이 갖는다.

## conversation

주고받는 하나의 스레드다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `public_id` | BINARY(16) UNIQUE | 화면 주소와 API 에 쓰는 UUID. 만들 때 정하고 바뀌지 않는다 |
| `user_id` | BIGINT | 이 대화의 주인. 다른 사용자는 읽지 못한다 |
| `agent_id` | BIGINT | 대화를 만들 때 정한다. 뒤에 바뀌지 않는다 |
| `hermes_session_id` | VARCHAR(128) NULL | 다음 turn 에 보낼 Hermes session. 새 대화는 첫 turn 을 보내기 전에 Control Plane 이 `fos-<uuid>` 로 정해 적는다. 그 전의 대화는 첫 실행이 돌려준 값이다. 압축 교체로 Hermes 가 다른 session 을 돌려주면 그 값으로 바뀐다. 특정 profile 안의 값이다 |
| `hermes_root_session_id` | VARCHAR(128) NULL | 그 대화의 뿌리 session. 새 대화는 첫 turn 을 보내기 전에 `hermes_session_id` 와 같은 `fos-<uuid>` 를 적고, 압축 교체에도 바뀌지 않는다. MCP `agent_*` 호출이 들고 오는 서명한 뿌리 session 이 이 값이다. 이 칸이 생기기 전의 대화는 비어 있다 |
| `title` | VARCHAR(200) | 첫 메시지의 앞부분. 사진을 먼저 올리려고 만든 대화는 첫 메시지 전까지 비어 있다 |
| `model_provider` | VARCHAR(64) NULL | 이 대화에서 고른 provider. `model` 과 함께 채우거나 함께 비운다 |
| `model` | VARCHAR(128) NULL | 이 대화에서 직접 고른 모델. 비면 어느 모델로 도는지는 [모델 단계와 실행 기록](../../model-tiers.md) 의 「모델 선택」 이 정한다 |
| `reasoning_effort` | VARCHAR(16) NULL | 이 대화에서 고른 effort. `low`, `medium`, `high`, `xhigh`, `max` 중 하나. 비면 어느 강도로 도는지는 [모델 단계와 실행 기록](../../model-tiers.md) 의 「모델 선택」 이 정한다 |
| `model_selection_mode` | VARCHAR(16) NULL | `DEFAULT`, `TIER`, `CUSTOM`. 비어 있으면 사용자와 그룹 기본값을 따른다 |
| `model_tier` | VARCHAR(16) NULL | 이 대화에서 고른 모델 단계 |
| `updated_at` | DATETIME(6) | 목록 정렬에 쓴다. 같은 값이면 `id` 가 큰 쪽이 앞이다 |
| `deleted_at` | DATETIME(6) NULL | 사용자가 지운 시각. 채워지면 목록과 조회와 보내기에서 없는 대화와 같다 |
| `auto_turn_count` | INT NOT NULL DEFAULT 0 | 마지막 사용자 질문 뒤로 Control Plane 이 위임 결과를 전하려고 연 turn 수. 사용자 질문을 저장할 때 0 으로 돌린다. `assistant.delegation-wake.max-auto-turns`(기본 10)에 닿으면 더 깨우지 않는다 |

`hermes_session_id` 가 특정 profile 안의 값이라, 대화의 에이전트는 중간에 바뀌지 않는다.

색인은 `(user_id, deleted_at, updated_at, id)` 다(V51). 목록이 사용자의 지우지 않은 대화를 `updated_at desc, id desc` 로 쪽마다 읽는다.
지운 대화가 쌓여도 한 쪽을 읽는 줄 수가 쪽 크기에 머문다.

**`agent_id` 에 FK 를 두지 않는다.** 칸도 NULL 을 받는다(V4 가 칸을 더하며 그렇게 만들었다).
에이전트를 지우는 것은 `deleted_at` 을 적는 것이라 정상 경로에서는 행이 사라지지 않는다.
그래도 행이 없는 대화가 운영에서 나왔고, 그 대화를 읽는 경로는 행이 없어도 실패하지 않게 고쳤다
([`backend/agent.md`](../agent.md) 의 「에이전트 만들기와 지우기」 절).
FK 를 더하려면 이미 행이 없는 대화를 먼저 정리해야 하고, 그 정리는 대화 이력을 지우거나 가짜 에이전트 행을 만드는 일이 된다.
행이 사라진 원인을 찾은 뒤 다시 판단한다.

**`id` 는 Control Plane 밖으로 나가지 않는다.** 화면과 API 는 대화를 `public_id` 로만 가리킨다.
다른 표는 지금처럼 `id` 로 대화를 참조한다.
새 대화는 UUID v7 을 받고, 마이그레이션 전에 있던 대화는 임의 값(v4)을 받았다.
근거는 [ADR-025](../../adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md)에 있다.

`title` 은 사용자가 고칠 수 있다. 앞뒤 공백을 떼고 1자에서 200자까지 받는다.

`model_provider`, `model`, `reasoning_effort` 는 대화의 주인이 언제든 바꾼다. 바꾼 값은 그 뒤의 보내기, 다시 생성, Memory 제안, 흐름의 하위 실행에 쓰인다.
새 대화는 셋 다 비어 있다. 고를 수 있는 모델 목록은 저장하지 않는다.

작업 영역(workspace)은 제거됐다.
근거는 [ADR-010](../../adr/ADR-010-작업-영역을-제거하고-에이전트가-그-자리를-갖는다.md)에 있다.

## chat_message

대화 안의 한 줄이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `conversation_id` | BIGINT | |
| `role` | VARCHAR(20) | `USER`, `ASSISTANT`, `SYSTEM`. `SYSTEM` 은 위임 결과가 도착했다는 알림 줄이다 |
| `content` | LONGTEXT | |
| `sender_user_id` | BIGINT NULL | 이 줄을 쓴 사람. `ASSISTANT` 와 `SYSTEM` 은 비어 있다 |
| `execution_id` | BIGINT NULL | 이 답을 만든 실행. `USER` 와 `SYSTEM` 은 비어 있다 |
| `replaces_message_id` | BIGINT NULL | 이 메시지가 새 판으로 대신하는 이전 메시지. 같은 대화, 같은 `role` 이다 |

`content` 를 `LONGTEXT` 로 못 박는다.
길이를 주지 않은 `@Lob` 문자열을 Hibernate 가 MySQL 에서 `tinytext` 로 기대해 기동이 실패한다.

`replaces_message_id` 는 다시 생성이 채운다. 다시 생성한 답은 이전 답을 가리킨다.
예전에는 수정한 사용자 메시지가 고치기 전 메시지를 가리켰다. 수정을 없앴지만 그 줄은 남아 있고 화면이 계속 넘겨 볼 수 있다.
근거는 [ADR-024](../../adr/ADR-024-메시지-수정을-없애고-중지한-뒤-다시-보낸다.md) 에 있다.
**이전 판을 지우지 않는다.** 화면이 넘겨 볼 수 있어야 하고, 그 판을 만든 실행이 그것을 가리킨다.
근거는 [ADR-022](../../adr/ADR-022-다시-생성과-수정은-같은-session-에-판으로-쌓는다.md) 에 있다.

중지한 실행의 답도 한 줄로 남는다.
근거는 [ADR-021](../../adr/ADR-021-중지한-답은-멈춘-자리까지-남긴다.md) 에 있다.

`SYSTEM` 줄은 Control Plane 이 부모 대화를 깨울 때 한 줄 남긴다. 본문은 어느 에이전트의 결과가 도착했는지 알리는 짧은 글이고, 자식의 답 전문은 넣지 않는다.
다시 생성의 대상이 아니다. 근거는 [ADR-040](../../adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md) 에 있다.

`sender_user_id` 는 화면이 보낸 사람 이름을 보이기 위한 것이다.
대화는 여전히 주인 한 사람의 것이고, 여러 사람이 같은 대화를 읽고 쓰는 것은 아직 만들지 않았다.

## chat_pending_message

turn 이 도는 동안 사용자가 보낸 메시지 하나가 한 행이다. 보내지기 전까지만 있다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | 쌓인 순서다. 합칠 때 이 순서로 잇는다 |
| `conversation_id` | BIGINT | |
| `user_id` | BIGINT | 이 글을 보낸 사용자. 합친 `USER` 행의 `sender_user_id` 가 된다 |
| `content` | LONGTEXT | 글. 한 행은 8000자까지다 |
| `held` | BOOLEAN NOT NULL DEFAULT FALSE | 멈춰 두었다. 앞 turn 을 중지했거나 보내려다 저장 전에 실패했다 |
| `created_at` | DATETIME(6) | |

색인은 `(conversation_id, id)` 다.

**보낸 행은 지운다.** 대기 행을 지우는 것과 합친 글을 `chat_message` 의 `USER` 행으로 저장하는 것이 한 트랜잭션이다.
보낸 글은 `chat_message` 에 남으므로 여기에 이력을 두지 않는다.
취소한 행도 지운다.

한 대화에 5행까지 둔다. 사이에 빈 줄 하나를 두고 이은 길이가 8000자를 넘지 못한다.
상한은 표의 제약이 아니라 `PendingMessageService` 가 더할 때 본다.

**한 행이라도 `held` 가 참이면 그 대화의 대기 행을 모두 보내지 않는다.**
사용자가 「보내기」 를 누르면 그 대화의 `held` 를 모두 내린다.

사진은 담지 않는다.
근거는 [ADR-048](../../adr/ADR-048-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md) 에 있다.

## chat_attachment

대화에 올린 사진 한 장이 한 행이다. 본문은 파일로 두고 여기에는 그 사진을 가리키는 것만 둔다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `conversation_id` | BIGINT | 어느 대화에 올렸는가. 디렉터리 이름이기도 하다 |
| `message_id` | BIGINT NULL | 함께 보낸 메시지. 아직 보내지 않았으면 비어 있다 |
| `uploaded_by_user_id` | BIGINT | 올린 사람 |
| `original_name` | VARCHAR(255) | 올릴 때의 파일 이름. 화면이 보인다 |
| `stored_name` | VARCHAR(255) NULL | 디스크에 둔 이름. `{id}.{확장자}` 다. 번호를 받은 직후 같은 트랜잭션에서 채우므로 커밋된 행에는 언제나 있다 |
| `content_type` | VARCHAR(100) | |
| `byte_size` | BIGINT | |
| `expires_at` | DATETIME(6) | 이 시각이 지나면 파일을 지운다 |
| `deleted_at` | DATETIME(6) NULL | 파일을 실제로 지운 시각. 비어 있으면 아직 있다 |
| `created_at` | DATETIME(6) | |

**행을 지우지 않는다.** 파일을 지우고 `deleted_at` 만 적는다.
그래야 지난 대화를 열었을 때 그 자리에 사진이 있었다는 것이 남고,
화면이 「보관 기간이 지나 볼 수 없습니다」를 보일 수 있다.

`deleted_at` 이 비어 있는지가 볼 수 있는지를 정한다. `expires_at` 은 언제 지울지만 정한다.
둘로 판정하면 지우는 일이 늦었을 때 화면과 디스크가 어긋난다.

`message_id` 가 비어 있는 행은 올렸지만 보내지 않은 것이다.
그 행도 `expires_at` 이 지나면 함께 지운다.

한 사용자가 남의 대화의 첨부를 읽지 못한다.
`conversation.user_id` 가 그 경계를 갖고, 첨부는 그 대화를 통해서만 닿는다.

근거는 [ADR-020](../../adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md) 에 있다.

## chat_artifact

에이전트가 turn 안에 대화의 결과물 폴더에 만들거나 고친 HTML 파일 하나가 한 행이다.
본문은 파일로 두고 여기에는 그 파일을 가리키는 것만 둔다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `conversation_id` | BIGINT | 어느 대화의 폴더인가. 폴더 이름이기도 하다 |
| `message_id` | BIGINT | 이 파일을 만든 turn 의 답 메시지 |
| `path` | VARCHAR(500) | 대화 폴더 안의 상대 경로. `/` 로 나눈다 |
| `byte_size` | BIGINT | 찾았을 때의 크기 |
| `created_at` | DATETIME(6) | |
| `deleted_at` | DATETIME(6) NULL | 보관 기간이 지나 파일을 지운 시각 |

`(message_id, path)` 에 유일 제약이 있다. 같은 파일을 다음 turn 이 다시 고치면 그 turn 의 답에 새 행이 생긴다.
화면은 답마다 그 답의 행을 보인다. 파일은 하나이므로 옛 답에서 열어도 지금 내용이 보인다.

**행을 지우지 않는다.** 첨부와 같다. 파일이 지워지면 `deleted_at` 을 적어 화면이 「보관 기간이 지나 볼 수 없습니다」를 보인다.
근거는 [ADR-027](../../adr/ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md) 에 있다.
