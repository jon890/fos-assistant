# Memory

Memory 항목과 그 판, 그룹의 collection 목록, 에이전트가 받는 collection 을 저장하는 표 넷의 칸과 제약을 갖는다.
실행에 실을 항목을 고르는 규칙은 [`backend/memory.md`](../memory.md) 가 갖는다.

## memory

사용자와 그룹에 대해 에이전트가 알아야 할 것 하나가 한 줄이다.
종류가 `MEMORY` 인 줄은 사실 하나이고, `DOCUMENT` 는 이름으로 찾는 긴 글 하나, `SOURCE` 는 출처로 남긴 원문 하나다.
지금 화면과 제안이 만드는 줄은 모두 `core` collection 의 `MEMORY` 다. 문서 API 가 만드는 줄은 `USER` 범위의 `DOCUMENT` 이고 `ACCEPTED` 다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `scope` | VARCHAR(20) | `USER` 또는 `GROUP`. 기본값이 없다 |
| `owner_user_id` | BIGINT NULL | `USER` 일 때 필요하다. 그 사람만 본다 |
| `group_id` | BIGINT NULL | `GROUP` 일 때 필요하다 |
| `collection` | VARCHAR(64) | 영역의 key. 소문자로 시작하고 소문자, 숫자, 하이픈만 쓴다. 기본값은 `core` |
| `entry_type` | VARCHAR(20) | `MEMORY`, `DOCUMENT`, `SOURCE`. 기본값은 `MEMORY` |
| `document_key` | VARCHAR(128) NULL | `DOCUMENT` 의 이름. 같은 주인과 `collection` 안에서 하나다. `MEMORY` 와 `SOURCE` 는 비어 있다 |
| `title` | VARCHAR(200) | 색인에 실을 제목 한 줄 |
| `content` | TEXT | 본문. 민감 항목이면 암호문이다 |
| `content_key_id` | VARCHAR(32) NULL | 본문을 암호화한 key 의 id. 비어 있으면 `content` 는 평문이다. `SENSITIVE` 인 줄은 key 가 있을 때 기동하며 채운다. key 가 없으면 평문으로 남은 줄이 비어 있을 수 있다 |
| `retrieval` | VARCHAR(20) | `ALWAYS` 는 본문을 매 실행에 싣는다. `SEARCH` 는 제목과 번호만 색인에 싣는다. `ARCHIVE` 는 색인에도 싣지 않는다. 기본값은 `SEARCH` |
| `always_inject` | BOOLEAN | `retrieval` 로 옮긴 옛 칸. `retrieval` 이 `ALWAYS` 일 때만 참으로 적는다. 읽지 않는다. 다음 배포에서 지운다 |
| `sensitivity` | VARCHAR(20) | `NORMAL` 또는 `SENSITIVE`. 기본값은 `NORMAL`. `SENSITIVE` 는 `ALWAYS` 로 둘 수 없다 |
| `revision` | INT | 지금 값의 판 번호. 1 에서 시작하고 본문이나 `retrieval` 이나 `sensitivity` 를 고칠 때마다 1 씩 는다. 승인과 거절은 올리지 않는다 |
| `source_type` | VARCHAR(32) NULL | 출처의 종류. 사람이 직접 적었으면 비어 있다. 기존 개인 지식에서 들인 줄은 `brain` 이다 |
| `source_ref` | VARCHAR(512) NULL | 출처를 가리키는 값. 다른 항목이면 `memory:<번호>`. 들인 줄은 `<namespace>/<저장소 안의 경로>` 다 |
| `source_date` | DATE NULL | 출처의 날짜 |
| `status` | VARCHAR(20) | `PROPOSED` 또는 `ACCEPTED` 또는 `REJECTED` |
| `proposed_by_execution_id` | BIGINT NULL | 이 항목을 제안하거나 `memory_remember` 로 바로 저장한 실행. 사람이 직접 적었으면 비어 있다 |
| `proposal_dedup_key` | VARCHAR(64) NULL | 제안하거나 바로 저장한 사용자·제목·본문의 해시. 직접 등록한 항목과 민감 항목은 비어 있다 |
| `accepted_by_user_id` | BIGINT NULL | 누가 받아들였는가 |
| `accepted_at` | DATETIME(6) NULL | |
| `created_at`, `updated_at` | DATETIME(6) | |

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

새 칸에는 모두 기본값이 있어 옛 코드도 이 표에 쓴다. 다만 옛 코드는 새 칸을 모르므로, 되돌린 동안 쓴 것은 다시 올리기 전에 맞춘다.

| 되돌린 동안 옛 코드가 한 일 | 다시 올리면 | 맞추는 것 |
| --- | --- | --- |
| `always_inject` 를 참으로 만들거나 고쳤다 | `retrieval` 이 `SEARCH` 로 남아 항상 층에서 색인으로 내려간다 | `always_inject` 가 참이고 `retrieval` 이 `SEARCH` 인 줄을 `ALWAYS` 로, 거짓이고 `ALWAYS` 인 줄을 `SEARCH` 로 고친다 |
| 에이전트를 만들었다 | `agent_memory_collection` 에 줄이 없어 그 에이전트가 Memory 를 받지 않는다 | 줄이 하나도 없고 옛 커넥터 에이전트가 아닌 에이전트에 `core` 를 넣는다 |
| Memory 를 고치거나 지웠다 | `memory_revision` 에 그 판이 없다 | 맞출 수 없다. 그 사이의 이력은 비어 있다 |

근거는 [ADR-003](../../adr/ADR-003-memory-권한은-주입으로-강제한다.md), [ADR-012](../../adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md),
[ADR-052](../../adr/ADR-052-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md),
[ADR-053](../../adr/ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md),
[ADR-058](../../adr/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md) 에 있다.

## memory_revision

지금 값에서 물러난 판 하나가 한 줄이다. 고치면 고치기 전의 값을, 지우면 마지막 값을 남긴다.
지금 값은 `memory` 에 있고 이 표에는 없다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `memory_id` | BIGINT | 항목 번호. 지운 항목의 판도 남으므로 `memory` 에 없는 번호일 수 있다. 외래 키를 걸지 않는다 |
| `revision` | INT | 이 줄이 담은 판 번호. `memory_id` 와 함께 기본 키다 |
| `change_type` | VARCHAR(20) | `UPDATED` 는 고쳐서 다음 판이 생겼다. `DELETED` 는 지웠고 이 판이 마지막 값이다 |
| `scope`, `owner_user_id`, `group_id` | | 그때의 범위와 주인. 지운 뒤에도 누가 볼 수 있는지 정한다 |
| `collection` | VARCHAR(64) | 그때의 collection |
| `entry_type`, `document_key` | | 그때의 종류와 문서 이름. 지운 문서의 판을 이름으로 찾는다 |
| `status` | VARCHAR(20) | 그때의 승인 상태. 지운 항목이 제안이었는지 받아들인 항목이었는지 남는다 |
| `title` | VARCHAR(200) | |
| `content` | TEXT | 그 판의 본문. 그때 민감 항목이었으면 암호문이다. 일반 항목이었어도 그 항목이 뒤에 민감 항목이 되면 암호문으로 바뀐다 |
| `content_key_id` | VARCHAR(32) NULL | 그 판의 본문을 암호화한 key 의 id. 비어 있으면 평문이다. 판이 암호문인지는 `sensitivity` 가 아니라 이 칸으로 본다 |
| `retrieval`, `sensitivity` | VARCHAR(20) | 그 판의 값 |
| `changed_by_user_id` | BIGINT NULL | 이 판을 물러나게 한 사용자 |
| `reason` | VARCHAR(200) NULL | 바꾼 까닭. 지금 화면은 적지 않는다 |
| `changed_at` | DATETIME(6) | |

한 항목을 두 번 고치고 지우면 줄이 셋이다. 판 1 과 2 가 `UPDATED` 로, 판 3 이 `DELETED` 로 남는다.
승인 전인 제안을 지워도 같은 방식으로 남는다.
출처 칸은 판에 남기지 않는다. 고칠 때 바뀌지 않는 값이다.
고치거나 지울 때 그 항목을 쓰기 잠금으로 읽는다. 두 요청이 같은 판 번호로 판을 남기려 하지 않게 한 번에 하나씩 돈다.

## memory_collection

그룹이 쓰는 collection 하나가 한 줄이다. 화면의 탭과 에이전트 접근 설정이 고를 목록이다.
누가 읽는지는 이 표가 정하지 않는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `group_id` | BIGINT | `collection_key` 와 함께 기본 키다 |
| `collection_key` | VARCHAR(64) | `memory.collection` 에 적는 key |
| `display_name` | VARCHAR(100) | 화면에 보이는 이름 |
| `sort_order` | INT | 화면 순서 |
| `created_at` | DATETIME(6) | |

그룹마다 `core`, `career`, `learning`, `health`, `finance`, `home`, `identity` 일곱 개로 시작한다.
마이그레이션이 사용자가 있는 그룹에 넣고, 그 뒤에 생긴 그룹은 목록을 처음 읽을 때 넣는다.
목록을 읽는 `MemoryCollectionService.collectionsOf` 는 `GET /api/v1/memory-collections` 가 부른다. 판을 읽는 `MemoryService.revisionsOf` 는 아직 부르는 API 가 없다. 화면과 API 를 넓힐 때 연결한다.

## agent_memory_collection

에이전트가 받는 collection 하나가 한 줄이다. 근거는 [ADR-053](../../adr/ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md) 에 있다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `agent_id` | BIGINT | `agent.id`. `collection` 과 함께 기본 키다 |
| `collection` | VARCHAR(64) | 받는 collection 의 key |
| `allow_sensitive` | BOOLEAN | 참이면 이 collection 의 `SENSITIVE` 항목까지 받는다. 기본값은 `FALSE` |
| `created_at` | DATETIME(6) | |

**줄이 하나도 없는 에이전트는 Memory 를 받지 않는다.**
에이전트를 처음 저장할 때 `core` 한 줄을 민감 허용 없이 넣는다. 옛 커넥터 에이전트에는 넣지 않고, 줄이 있어도 옛 커넥터 에이전트는 받지 않는다.

## memory_capture

에이전트가 `memory_remember` 로 남긴 기록 한 줄이다. 대화의 답 아래에 「기억했어요」 와 제안 카드를 그리고, 되돌리기가 무엇을 되돌릴지 정한다.
도구와 API 는 [`backend/memory.md`](../memory.md) 의 「에이전트가 기억을 남기는 길」 이 갖는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | 기본 키 |
| `memory_id` | BIGINT | 만들거나 고친 항목. 되돌리거나 사람이 지우면 `memory` 에 없는 번호가 된다. 외래 키를 걸지 않는다 |
| `user_id` | BIGINT | 항목의 주인. origin 실행의 사용자다 |
| `conversation_id` | BIGINT NULL | 그 실행의 대화. 대화 밖의 실행이면 비어 있다 |
| `execution_id` | BIGINT | 기록을 남긴 origin 실행. 한 실행의 상한(3개)을 이 칸으로 센다 |
| `kind` | VARCHAR(20) | `CREATED` 바로 저장해 만들었다, `UPDATED` 바로 저장해 기존 항목의 본문을 고쳤다, `PROPOSED` 제안으로 남겼다 |
| `base_revision` | INT NULL | `UPDATED` 일 때 고치기 전의 판 번호. 되돌리면 이 판의 값으로 돌린다 |
| `created_at` | DATETIME(6) | |
| `undone_at` | DATETIME(6) NULL | 사람이 되돌린 시각. 되돌린 기록은 대화에 그리지 않는다 |

| 색인 | 칸 | 쓰는 곳 |
| --- | --- | --- |
| `idx_memory_capture_conversation` | `conversation_id`, `id` | 대화의 기록 목록 |
| `idx_memory_capture_execution` | `execution_id` | 한 실행의 상한 |

- 같은 사실을 같은 실행에서 두 번 남겨도 중복 키가 같은 항목을 하나로 둔다. 기록은 저장하거나 고친 경우에만 남는다
- `UPDATED` 를 되돌릴 때 항목의 판이 `base_revision + 1` 이 아니면 그 뒤에 사람이 다시 고친 것이다. 되돌리지 않는다
