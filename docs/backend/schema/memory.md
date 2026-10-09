# Memory

Memory 항목과 그 판, 그룹의 collection 목록, 에이전트가 받는 collection 과 그 변경 기록을 저장하는 표에서 마이그레이션만 읽어서는 알 수 없는 칸의 뜻과 제약의 까닭을 갖는다.
칸과 타입, 색인은 마이그레이션(`V7__memory.sql`, `V47__memory_v2.sql` 과 그 뒤의 Memory 마이그레이션)이 갖고, 값의 목록은 각 enum 이 갖는다.
실행에 실을 항목을 고르는 규칙은 [`backend/memory.md`](../memory.md) 가 갖는다.

## memory

사용자와 그룹에 대해 에이전트가 알아야 할 것 하나가 한 줄이다.
종류가 `MEMORY` 인 줄은 사실 하나이고, `DOCUMENT` 는 이름으로 찾는 긴 글 하나, `SOURCE` 는 출처로 남긴 원문 하나다.
지금 있는 화면과 제안이 만드는 줄은 모두 `core` collection 의 `MEMORY` 다. 문서 API 가 만드는 줄은 `USER` 범위의 `DOCUMENT` 이고 `ACCEPTED` 다.

- `scope` 가 `USER` 면 `owner_user_id` 가, `GROUP` 이면 `group_id` 가 주인이다. `scope` 에는 기본값이 없다
- `content` 는 민감 항목이면 암호문이다. `content_key_id` 가 비어 있으면 평문이다. `SENSITIVE` 인 줄은 key 가 있을 때 기동하며 채우므로, key 가 없으면 평문으로 남은 줄이 비어 있을 수 있다
- `retrieval` 의 뜻은 `MemoryRetrieval` 이 갖는다. `SENSITIVE` 는 `ALWAYS` 로 둘 수 없다
- `always_inject` 는 `retrieval` 로 옮긴 옛 칸이다. `retrieval` 이 `ALWAYS` 일 때만 참으로 적고 읽지 않는다. 한 배포 뒤에 지우기로 했지만 지우는 마이그레이션은 아직 없다
- `revision` 은 지금 값의 판 번호다. 1 에서 시작하고 본문이나 `retrieval` 이나 `sensitivity` 를 고칠 때마다 1 씩 는다. 승인과 거절은 올리지 않는다
- `source_type` 과 `source_ref` 는 출처다. 사람이 직접 적었으면 비어 있다. 다른 항목이면 `source_ref` 가 `memory:<번호>` 다. 예전에 기존 개인 지식에서 들인 줄은 `source_type` 이 `brain` 이고 `source_ref` 가 `<namespace>/<저장소 안의 경로>` 다. 지금 코드에는 줄을 들이는 경로가 없다
- `proposed_by_execution_id` 는 이 항목을 제안하거나 `memory_remember` 로 바로 저장한 실행이다. 사람이 직접 적었으면 비어 있다
- `proposal_dedup_key` 는 제안하거나 바로 저장한 사용자, 제목, 본문의 해시다. 직접 등록한 항목과 민감 항목은 비어 있다

**`ACCEPTED` 인 항목만 주입한다.**
`PROPOSED` 는 사람이 아직 보지 않은 것이고, 에이전트가 그것을 사실로 쓰면 안 된다.

주입할 때 요청자의 `USER` 항목과 요청자가 속한 그룹의 `GROUP` 항목 가운데, 그 실행의 에이전트가 받는 `collection` 의 항목만 고른다.
`SENSITIVE` 항목은 그 `collection` 에서 민감 항목을 허용받은 에이전트에만 고른다.
`SOURCE` 와 `ARCHIVE` 는 고르지 않는다.
다른 사용자의 `USER` 항목과 받지 않는 `collection` 의 항목은 고르는 단계에서 빠지므로 Hermes 로 나가는 문자열에 들어가지 않는다.

| 유일 제약 | 칸 | 막는 것 |
| --- | --- | --- |
| `uk_memory_proposal_dedup` | `proposal_dedup_key` | 같은 제안이 동시에 들어와 두 행이 되는 것 |
| `uk_memory_user_document` | `owner_user_id`, `collection`, `document_key` | 한 사용자의 한 `collection` 에 같은 이름의 문서가 둘 생기는 것 |
| `uk_memory_group_document` | `group_id`, `collection`, `document_key` | 한 그룹의 한 `collection` 에 같은 이름의 문서가 둘 생기는 것 |
| `uk_memory_user_source` | `owner_user_id`, `source_type`, `source_ref` | 한 사용자의 같은 출처가 두 줄이 되는 것. 출처가 없는 줄은 `source_ref` 가 비어 걸리지 않는다 |

주인 칸이 범위에 따라 달라 문서 제약을 둘로 둔다. 비어 있는 칸은 유일 검사에 들지 않으므로 `document_key` 가 없는 줄은 걸리지 않는다.

### 옛 판으로 되돌렸다가 다시 올릴 때

V47 이전 판으로 되돌린 동안 옛 코드가 쓴 줄은 다시 올리기 전에 맞춘다.
`always_inject` 와 `retrieval` 이 어긋난 줄은 `always_inject` 를 따라 `retrieval` 을 `ALWAYS` 나 `SEARCH` 로 고친다.
`agent_memory_collection` 에 줄이 없는 에이전트에는 `core` 를 넣는다. 옛 커넥터 에이전트는 뺀다.
되돌린 동안 고치거나 지운 Memory 는 `memory_revision` 에 판이 남지 않는다.

근거는 [ADR-003](../../../backend/docs/adr/ADR-003-memory-권한은-주입으로-강제한다.md), [ADR-012](../../../backend/docs/adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md),
[ADR-052](../../../backend/docs/adr/ADR-052-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md),
[ADR-053](../../../backend/docs/adr/ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md),
[ADR-058](../../adr/archive/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md) 에 있다.

## memory_revision

지금 값에서 물러난 판 하나가 한 줄이다. 고치면 고치기 전의 값을, 지우면 마지막 값을 남긴다.
지금 값은 `memory` 에 있고 이 표에는 없다.

- `memory_id` 는 지운 항목의 판도 남으므로 `memory` 에 없는 번호일 수 있다. 그래서 외래 키를 걸지 않는다
- `scope`, `owner_user_id`, `group_id` 는 그때의 범위와 주인이다. 지운 뒤에도 누가 볼 수 있는지 이 칸으로 정한다
- `entry_type`, `document_key` 는 그때의 종류와 문서 이름이다. 지운 문서의 판을 이름으로 찾는다
- `status` 는 그때의 승인 상태다. 지운 항목이 제안이었는지 받아들인 항목이었는지 남는다
- `content` 는 그때 민감 항목이었으면 암호문이다. 일반 항목이었어도 그 항목이 뒤에 민감 항목이 되면 암호문으로 바뀐다. 그래서 판이 암호문인지는 `sensitivity` 가 아니라 `content_key_id` 로 본다
- `changed_by_user_id` 는 이 판을 물러나게 한 사용자다. `reason` 은 지금 있는 화면이 적지 않는다

한 항목을 두 번 고치고 지우면 줄이 셋이다. 판 1 과 2 가 `UPDATED` 로, 판 3 이 `DELETED` 로 남는다.
승인 전인 제안을 지워도 같은 방식으로 남는다.
출처 칸은 판에 남기지 않는다. 고칠 때 바뀌지 않는 값이다.
고치거나 지울 때 그 항목을 쓰기 잠금으로 읽는다. 두 요청이 같은 판 번호로 판을 남기려 하지 않게 한 번에 하나씩 돈다.

## memory_collection

그룹이 쓰는 collection 하나가 한 줄이다. 화면의 탭과 에이전트 접근 설정이 고를 목록이다.
누가 읽는지는 이 표가 정하지 않는다.

`collection_key` 는 `memory.collection` 에 적는 key 다.

그룹마다 `MemoryCollection.DEFAULT_KEYS` 로 시작한다.
마이그레이션이 사용자가 있는 그룹에 넣고, 그 뒤에 생긴 그룹은 목록을 처음 읽을 때 넣는다.
목록을 읽는 `MemoryCollectionService.collectionsOf` 는 `GET /api/v1/memory-collections` 와 관리자의 `/api/v1/admin/agents/{code}/memory-collections` 가 부른다. 판을 읽는 `MemoryService.revisionsOf` 는 아직 부르는 API 가 없다. 화면과 API 를 넓힐 때 연결한다.

## agent_memory_collection

에이전트가 받는 collection 하나가 한 줄이다. 근거는 [ADR-053](../../../backend/docs/adr/ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md) 에 있다.

`allow_sensitive` 가 참이면 이 collection 의 `SENSITIVE` 항목까지 받는다.

**줄이 하나도 없는 에이전트는 Memory 를 받지 않는다.**
에이전트를 처음 저장할 때 `core` 한 줄을 민감 허용 없이 넣는다. 옛 커넥터 에이전트에는 넣지 않고, 줄이 있어도 옛 커넥터 에이전트는 받지 않는다.
그 뒤에는 `ADMIN` 이 관리자 영역에서 줄을 더하고 빼고 민감 허용을 바꾼다. 바꿀 때마다 `agent_memory_collection_change` 에 한 줄을 남긴다.
`created_at` 은 그 collection 을 붙인 시각이다. 민감 허용만 바꾸면 그대로 둔다.

## agent_memory_collection_change

`agent_memory_collection` 의 줄 하나가 바뀐 것이 한 줄이다. 지우지 않는다. 근거는 [ADR-20261008 / agent-memory-grants-admin](../../adr/ADR-20261008-agent-memory-grants-admin.md) 에 있다.

- `allow_sensitive` 는 `GRANTED` 와 `SENSITIVE_CHANGED` 면 바꾼 뒤의 값이고, `REVOKED` 면 떼기 전의 값이다
- `changed_by_user_id` 는 바꾼 `ADMIN` 이다. 외래 키를 걸지 않는다
- `(agent_id, id)` 색인은 관리 화면의 최근 변경을 읽는 데 쓴다

한 번의 저장이 여러 collection 을 바꾸면 줄도 여럿이고 `changed_at` 이 같다.
바뀌지 않은 collection 은 남기지 않는다. 마이그레이션이 넣은 `core` 와 새 에이전트에 넣는 `core` 도 남기지 않는다. 사람이 바꾼 것만 남긴다.

## memory_capture

에이전트가 `memory_remember` 로 남긴 기록 한 줄이다. 대화의 답 아래에 「기억했어요」 와 제안 카드를 그리고, 되돌리기가 무엇을 되돌릴지 정한다.
도구와 API 는 [`backend/memory.md`](../memory.md) 의 「에이전트가 기억을 남기는 길」 이 갖는다.

- `memory_id` 는 만들거나 고친 항목이다. 되돌리거나 사람이 지우면 `memory` 에 없는 번호가 되므로 외래 키를 걸지 않는다
- `user_id` 는 origin 실행의 사용자이고, `execution_id` 는 기록을 남긴 origin 실행이다. 한 실행의 상한을 `execution_id` 로 센다
- `conversation_id` 는 그 실행의 대화다. 대화 밖의 실행이면 비어 있다. 대화의 기록 목록은 `(conversation_id, id)` 색인으로 읽는다
- `base_revision` 은 `CREATED` 면 저장한 때의 판 번호, `UPDATED` 면 고치기 전의 판 번호이고 `PROPOSED` 면 비어 있다
- `previous_status` 는 기존 제안을 받아들인 `CREATED` 만 `PROPOSED` 이고 나머지는 비어 있다
- `undone_at` 은 사람이 되돌린 시각이다. 되돌린 기록은 대화에 그리지 않는다

- 같은 사실을 같은 실행에서 두 번 남겨도 중복 키가 같은 항목을 하나로 둔다. 기록은 저장하거나 고친 경우에만 남는다
- `CREATED` 를 되돌릴 때 항목의 판이 `base_revision` 이 아니면, `UPDATED` 를 되돌릴 때 `base_revision + 1` 이 아니면 그 뒤에 사람이 다시 고친 것이다. 되돌리지 않는다
- 이미 있던 제안을 바로 저장 조건에서 받아들인 기록도 `CREATED` 다. `previous_status` 에 `PROPOSED` 를 남기고 되돌리면 항목을 보존하며 승인 정보도 지워 제안 상태로 돌린다
