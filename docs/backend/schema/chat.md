# 대화

대화와 메시지, 대기 메시지, 사진 첨부, 결과물, 결과 전달 기록, 실행과 질문의 연결을 저장하는 표 아홉의 칸과 제약을 갖는다.
이 표들을 읽고 쓰는 경로는 [`backend/conversation.md`](../conversation.md) 와 그 옆의 문서들이 갖는다.

## conversation

주고받는 하나의 스레드다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `public_id` | BINARY(16) UNIQUE | 화면 주소와 API 에 쓰는 UUID. 만들 때 정하고 바뀌지 않는다 |
| `user_id` | BIGINT | 이 대화의 주인. 다른 사용자는 읽지 못한다 |
| `agent_id` | BIGINT | 대화를 만들 때 정한다. 뒤에 바뀌지 않는다 |
| `hermes_session_id` | VARCHAR(128) NULL | 다음 turn 에 보낼 Hermes session. 새 대화는 첫 turn 을 보내기 전에 Control Plane 이 `fos-<uuid>` 로 정해 적는다. 그 전의 대화는 첫 실행이 돌려준 값이다. 압축 교체로 Hermes 가 다른 session 을 돌려주면 그 값으로 바뀐다. 특정 profile 안의 값이다 |
| `hermes_root_session_id` | VARCHAR(128) NULL | 그 대화의 루트 session. 새 대화는 첫 turn 을 보내기 전에 `hermes_session_id` 와 같은 `fos-<uuid>` 를 적고, 압축 교체에도 바뀌지 않는다. MCP `agent_*` 호출이 들고 오는 서명한 루트 session 이 이 값이다. 이 칸이 생기기 전의 대화는 비어 있다 |
| `title` | VARCHAR(200) | 첫 메시지의 앞부분. 사진을 먼저 올리려고 만든 대화는 첫 메시지 전까지 비어 있다 |
| `model_provider` | VARCHAR(64) NULL | 이 대화에서 고른 provider. `model` 과 함께 채우거나 함께 비운다 |
| `model` | VARCHAR(128) NULL | 이 대화에서 직접 고른 모델. 비면 어느 모델로 도는지는 [모델 단계와 실행 기록](../../model-tiers.md) 의 「모델 선택」 이 정한다 |
| `reasoning_effort` | VARCHAR(16) NULL | 이 대화에서 고른 effort. `none`, `low`, `medium`, `high`, `xhigh`, `max` 중 하나. `none` 은 reasoning 끄기이고 비어 있음(미지정)과 다르다. 비면 어느 강도로 도는지는 [모델 단계와 실행 기록](../../model-tiers.md) 의 「모델 선택」 이 정한다 |
| `model_selection_mode` | VARCHAR(16) NULL | `DEFAULT`, `TIER`, `CUSTOM`. 비어 있으면 사용자와 그룹 기본값을 따른다 |
| `model_tier` | VARCHAR(16) NULL | 이 대화에서 고른 모델 단계 |
| `updated_at` | DATETIME(6) | 목록 정렬에 쓴다. 같은 값이면 `id` 가 큰 쪽이 앞이다 |
| `deleted_at` | DATETIME(6) NULL | 사용자가 지운 시각. 채워지면 목록과 조회와 보내기에서 없는 대화와 같다 |
| `auto_turn_count` | INT NOT NULL DEFAULT 0 | 마지막 사용자 질문 뒤로 Control Plane 이 위임 결과를 전하려고 연 turn 수. 사용자 질문을 저장할 때 0 으로 돌린다. `assistant.delegation-wake.max-auto-turns`(기본 10)에 닿으면 더 깨우지 않는다 |
| `purpose` | VARCHAR(16) NOT NULL DEFAULT 'CHAT' | `CHAT` 은 보통 대화, `CHECK` 는 먼저 살펴보기의 점검 대화다. 만들 때 정하고 바뀌지 않는다. 사용자와 에이전트마다 지우지 않은 점검 대화 가운데 `id` 가 가장 큰 것을 쓴다([`proactive-check.md`](../proactive-check.md)) |
| `task_id` | BIGINT NULL | 이 대화를 만든 예약 작업. 사용자가 연 대화는 비어 있다. 외래 키를 두지 않는다. 뜻은 [`task.md`](task.md) 의 「conversation 에 더하는 칸」 |

`hermes_session_id` 가 특정 profile 안의 값이라, 대화의 에이전트는 중간에 바뀌지 않는다.

색인은 `(user_id, deleted_at, updated_at, id)` 다(V51). 점검 대화를 찾는 `(user_id, agent_id, purpose)` 도 있다(V69). 목록이 사용자의 지우지 않은 대화를 `updated_at desc, id desc` 로 쪽마다 읽는다.
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

`SYSTEM` 줄은 Control Plane 이 부모 대화를 깨울 때 한 줄 남긴다. 사용자가 결과를 다시 전달할 때도 한 줄 남긴다. 본문은 어느 에이전트의 결과가 도착했는지 알리는 짧은 글이고, 자식의 답 전문은 넣지 않는다.
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
| `position` | INT NOT NULL | 같은 메시지에 붙인 사진의 고른 순서. 0부터 센다. 기존에 메시지에 묶인 행은 첨부 번호 순서로 채웠다 |
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

같은 메시지의 사진은 `position` 오름차순으로 화면, Hermes 입력, 사용자가 보는 사진 순번에 함께 쓴다.
지운 사진도 그 자리를 차지한다.

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
| `deleted_at` | DATETIME(6) NULL | 보관 기간이 지나 파일을 지운 시각. 지운 뒤 적지 못한 행은 다음 정리가 파일이 없다고 확인한 시각이다 |

`(message_id, path)` 에 유일 제약이 있다. 같은 파일을 다음 turn 이 다시 고치면 그 turn 의 답에 새 행이 생긴다.
화면은 답마다 그 답의 행을 보인다. 파일은 하나이므로 옛 답에서 열어도 지금 내용이 보인다.

**행을 지우지 않는다.** 첨부와 같다. 파일이 지워지면 `deleted_at` 을 적어 화면이 「보관 기간이 지나 볼 수 없습니다」를 보인다.
근거는 [ADR-027](../../adr/ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md) 에 있다.
지운 뒤 표시에 실패한 행을 다시 맞추는 규칙은 [`docs/backend/artifact.md`](../artifact.md) 의 「지운 표시를 다시 맞추기」 가 갖는다.

## result_delivery

자동 turn 하나가 부모 대화에 넘긴 결과들의 묶음이다. 그 turn 이 알림 줄을 저장할 때 생긴다.
결정은 [ADR-075](../../adr/ADR-075-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md), 상태가 바뀌는 흐름은 [`agent-delegation.md`](../agent-delegation.md) 의 「결과 전달이 끝나지 않았을 때」 에 있다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | 화면과 다시 전달 경로가 쓰는 번호다. 대화 주인만 그 대화의 묶음을 부른다 |
| `conversation_id` | BIGINT | 결과를 받은 대화 |
| `status` | VARCHAR(20) | `DELIVERING`, `DELIVERED`, `FAILED`, `STOPPED`. 마지막 시도의 끝과 같다 |
| `attempt_count` | INT | 지금까지 만든 시도 수. 다시 전달할 때 하나 늘린다 |
| `created_at` | DATETIME(6) | |
| `updated_at` | DATETIME(6) | 상태가 바뀐 시각 |

색인은 `(conversation_id, status)` 다.

**다시 전달은 `status` 를 `FAILED` 나 `STOPPED` 에서 `DELIVERING` 으로 바꾸는 조건부 update 로 시작한다.** 바뀐 줄이 없으면 시작하지 않는다. 한 묶음에 도는 시도가 둘이 되지 않는 근거다.
묶음과 시도의 상태는 같은 트랜잭션에서 바꾼다.

외래 키를 두지 않는다. `connector_action` 과 같이 대화가 지워져도 기록을 남긴다. 지운 대화의 묶음은 다시 전달하지 못한다.

## result_delivery_item

묶음에 든 결과 하나가 한 행이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | 넣은 순서다. 다시 전달할 때 이 순서로 입력을 만든다 |
| `delivery_id` | BIGINT FK | `result_delivery` |
| `source` | VARCHAR(40) | 결과를 낸 쪽. 위임 결과는 `DELEGATION`, 승인한 커넥터 호출의 결과는 `CONNECTOR_ACTION` 이다 |
| `result_key` | VARCHAR(64) | 그쪽의 결과 이름. `DELEGATION` 은 `agent_execution.id`, `CONNECTOR_ACTION` 은 `connector_action.public_id` 의 UUID 글이다 |

`(source, result_key)` 에 유일 제약이 있다. 한 결과는 한 묶음에만 든다. 같은 결과를 두 자동 turn 이 함께 넘기려 하면 뒤의 트랜잭션이 알림 줄까지 함께 되돌아간다.
색인은 `(delivery_id, id)` 다. 다시 전달할 때 묶음의 항목을 넣은 순서로 읽는다.

결과 본문은 여기 두지 않는다. 다시 전달할 때 실행 줄의 `output_text` 와 승인 줄의 `result_text` 를 다시 읽는다.

## result_delivery_attempt

묶음을 부모에 넘긴 한 번이 한 행이다. 첫 시도는 묶음과 함께, 다음 시도는 다시 전달할 때 생긴다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `delivery_id` | BIGINT FK | `result_delivery` |
| `attempt_no` | INT | 1 부터 센다 |
| `status` | VARCHAR(20) | `RUNNING`, `SUCCEEDED`, `FAILED`, `STOPPED` |
| `execution_id` | BIGINT NULL | 이 시도의 부모 turn 실행 줄. 실행 줄을 만들면 채운다. 그 전에 실패했거나 내려갔으면 비어 있다 |
| `notice_message_id` | BIGINT NULL | 이 시도가 저장한 마지막 `SYSTEM` 줄. 화면은 묶음의 마지막 시도의 이 줄 아래에 상태와 버튼을 그린다 |
| `error_code` | VARCHAR(64) NULL | `FAILED` 의 원인. 부모 turn 의 오류 코드이거나, 실행 줄이 생기기 전에 내려간 `INTERRUPTED` 다 |
| `started_at` | DATETIME(6) | |
| `finished_at` | DATETIME(6) NULL | `RUNNING` 이면 비어 있다 |

`(delivery_id, attempt_no)` 에 유일 제약이 있다. 색인은 `(status, started_at)` 과 `(execution_id)` 다.
기동할 때 앞 프로세스가 남긴 `RUNNING` 시도를 `started_at` 으로 고르고, 기동 정리가 실행 줄을 적을 때 `execution_id` 로 찾는다.

`execution_id` 와 `notice_message_id` 에는 외래 키를 두지 않는다. 그 줄이 지워져도 시도의 끝은 남는다.

## execution_question

사람이 보낸 대화 turn 의 실행과 그 질문 메시지를 잇는다. 한 실행이 한 줄이다.
`memory_remember` 가 바로 저장할 수 있는 실행인지 이 줄로 판정한다([ADR-093](../../adr/ADR-093-사용자가-대화에서-직접-말한-사실은-에이전트가-바로-기억하고-그-밖은-제안으로-남긴다.md)).

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `execution_id` | BIGINT | 기본 키. turn 의 루트 실행 |
| `message_id` | BIGINT | 그 turn 의 질문(`USER` 메시지). 새 질문이면 방금 저장한 메시지, 다시 생성이면 이미 있던 질문 |
| `created_at` | DATETIME(6) | |

- 새 질문과 다시 생성의 실행에만 남긴다. 예약 작업, 먼저 살펴보기, 맡긴 일의 결과를 전하는 turn, 맡겨서 도는 실행은 줄이 없다
- 외래 키를 걸지 않는다. 읽는 쪽(`TurnQuestions`)은 메시지가 없거나, `USER` 가 아니거나, 그 실행의 사용자가 보낸 것이 아니면 없는 줄로 본다
- 줄을 남기지 못해도 turn 은 잇는다. 그 실행의 `memory_remember` 는 제안으로만 남는다
