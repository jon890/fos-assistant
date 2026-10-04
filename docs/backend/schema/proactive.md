# 먼저 살펴보기

먼저 살펴보기 한 번과 그 발견을 저장하는 표 둘의 칸과 제약을 갖는다.
이 표들을 읽고 쓰는 경로는 [`backend/proactive-check.md`](../proactive-check.md) 가 갖는다.
점검 대화는 [`chat.md`](chat.md) 의 `conversation.purpose` 로 가린다.

## proactive_check

살펴보기 한 번이다. 시작할 때 만들고 끝날 때 갱신한다. 실행의 토큰과 금액은 `agent_execution` 이 갖고 여기 다시 적지 않는다.
살펴보기 한 번의 비용은 `root_execution_id` 로 그 트리의 실행 줄을 합쳐 얻는다([`proactive-check.md`](../proactive-check.md) 의 「비용과 효과」).

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `user_id` | BIGINT FK `app_user` | 살펴보기를 연 사용자. 점검 대화의 주인이다 |
| `agent_id` | BIGINT FK `agent` | 살펴본 에이전트 |
| `conversation_id` | BIGINT FK `conversation` | 결과가 남는 점검 대화 |
| `root_execution_id` | BIGINT NULL UNIQUE FK `agent_execution` | 살펴보기 turn 의 실행 줄. 실행 줄을 만들기 전에 실패하면 비어 있다. 살펴보기 트리를 가리는 기준이다 |
| `hermes_root_session_id` | VARCHAR(128) NULL | 이 살펴보기를 보낸 점검 대화의 루트 session. session 을 바꿀지 셀 때 쓴다 |
| `trigger_type` | VARCHAR(16) | `MANUAL`(단추), `SCHEDULED`(매일 깨우기, 아직 없다) |
| `status` | VARCHAR(16) | `RUNNING`, `SUCCEEDED`, `FAILED`, `STOPPED` |
| `outcome` | VARCHAR(16) NULL | `FINDINGS`, `NOTHING_NEW`, `INVALID_RESULT`. `SUCCEEDED` 일 때만 채운다 |
| `error_code` | VARCHAR(64) NULL | `FAILED` 와 `STOPPED` 의 까닭. 상한으로 멈추면 `CHECK_TIME_LIMIT`, `CHECK_TOOL_LIMIT` 이고 사용자가 멈추면 비어 있다 |
| `tool_calls` | INT NOT NULL DEFAULT 0 | 살펴보기 turn 이 시작한 도구 호출 수 |
| `delegations` | INT NOT NULL DEFAULT 0 | 그 트리에서 맡긴 위임 자식 수. 끝날 때 센다 |
| `new_findings` | INT NOT NULL DEFAULT 0 | 「새로 알릴 것」 으로 그린 발견 수 |
| `reference_findings` | INT NOT NULL DEFAULT 0 | 「참고」 로 내린 발견 수 |
| `started_at` | DATETIME(6) | |
| `finished_at` | DATETIME(6) NULL | 끝나면 채운다 |

색인은 `(user_id, agent_id, started_at)` 과 `(conversation_id, hermes_root_session_id)` 다.
앞의 것은 마지막 살펴보기를 읽고, 뒤의 것은 session 을 바꿀지 센다.

`trigger` 는 MySQL 의 예약어라 칸 이름을 `trigger_type` 으로 둔다.

## proactive_check_finding

결과 블록의 발견 하나다. 다음 살펴보기가 최근에 알린 것을 입력에 싣는 데 쓰고, 「지금」 화면과 할 일로 이을 때 원래 기록이 된다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `check_id` | BIGINT FK `proactive_check` ON DELETE CASCADE | 이 발견을 낸 살펴보기 |
| `conversation_id` | BIGINT | 점검 대화. 입력에 실을 발견을 대화로 읽으려고 둔다. `proactive_check.conversation_id` 와 같다 |
| `kind` | VARCHAR(16) | `NEW` 는 「새로 알릴 것」, `REFERENCE` 는 「참고」 |
| `reason` | VARCHAR(32) NULL | `REFERENCE` 의 까닭. `NO_SOURCE`, `NOT_CHECKED_NOW`, `CLOSED`, `STALE`, `FRESHNESS_UNKNOWN`, `INCOMPLETE`, `REPEATED` |
| `area` | VARCHAR(40) | 분야 지침이 정한 영역 |
| `topic_key` | VARCHAR(120) NULL | 분야 지침이 정한 주제 키. 같은 주제와 같은 원문을 다시 알리지 않는 판정에 쓴다 |
| `title` | VARCHAR(120) | 발견의 제목. 모델이 쓴 글이다 |
| `source_url` | VARCHAR(2000) NULL | 검사를 통과한 원문 주소. `NO_SOURCE` 면 비어 있다 |
| `checked_at` | DATETIME(6) NULL | 원문을 확인한 시각. 읽지 못했으면 비어 있다 |
| `created_at` | DATETIME(6) | |

색인은 `(conversation_id, kind, created_at)` 이다.

발견의 이유, 사실, 추정, 다음 행동은 대화 메시지에만 있고 이 표에 두지 않는다.
같은 글을 두 곳에 두면 사용자가 대화를 지워도 한쪽에 남는다.
