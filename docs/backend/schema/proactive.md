# 먼저 살펴보기

먼저 살펴보기 한 번과 그 발견, 문제 후보를 저장하는 표 셋의 칸과 제약을 갖는다.
이 표들을 읽고 쓰는 경로는 [`backend/proactive-check.md`](../proactive-check.md) 가 갖는다.
점검 대화는 [`chat.md`](chat.md) 의 `conversation.purpose` 로 가린다.

## proactive_check

살펴보기 한 번이다. 시작할 때 만들고 끝날 때 갱신한다. 실행별 토큰과 금액은 `agent_execution` 이 갖는다.
깨우기 예산을 측정할 트리 토큰 합계만 여기에도 남긴다.
살펴보기 한 번의 비용은 `root_execution_id` 로 그 트리의 실행 줄을 합쳐 얻는다([`proactive-check.md`](../proactive-check.md) 의 「비용과 효과」).

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `user_id` | BIGINT FK `app_user` | 살펴보기를 연 사용자. 점검 대화의 주인이다 |
| `agent_id` | BIGINT FK `agent` | 살펴본 에이전트 |
| `conversation_id` | BIGINT FK `conversation` | 결과가 남는 점검 대화 |
| `root_execution_id` | BIGINT NULL UNIQUE FK `agent_execution` | 살펴보기 turn 의 실행 줄. 실행 줄을 만들기 전에 실패하면 비어 있다. 살펴보기 트리를 가리는 기준이다 |
| `hermes_root_session_id` | VARCHAR(128) NULL | 이 살펴보기를 보낸 점검 대화의 루트 session. session 을 바꿀지 셀 때 쓴다 |
| `trigger_type` | VARCHAR(16) | `MANUAL`(단추), `SCHEDULED`(매일 깨우기) |
| `status` | VARCHAR(16) | `RUNNING`, `SUCCEEDED`, `FAILED`, `STOPPED` |
| `outcome` | VARCHAR(16) NULL | `FINDINGS`, `NOTHING_NEW`, `INVALID_RESULT`. `SUCCEEDED` 일 때만 채운다 |
| `invalid_reason` | VARCHAR(32) NULL | 결과 블록을 읽지 못한 까닭. `outcome` 이 `INVALID_RESULT` 일 때만 채운다. `EMPTY_ANSWER`, `NO_BLOCK`, `NOT_JSON`, `BAD_VERSION`, `BAD_OUTCOME`. 뜻은 [`proactive-check.md`](../proactive-check.md) 의 「결과 계약」 이 갖는다. 이 칸을 더하기 전에 끝난 줄은 비어 있다 |
| `error_code` | VARCHAR(64) NULL | `FAILED` 와 `STOPPED` 의 까닭. 상한으로 멈추면 `CHECK_TIME_LIMIT`, `CHECK_TOOL_LIMIT` 이고 사용자가 멈추면 비어 있다. 서버가 도중에 내려가 기동할 때 닫은 줄은 `INTERRUPTED` 다 |
| `tool_calls` | INT NOT NULL DEFAULT 0 | 살펴보기 turn 이 시작한 도구 호출 수 |
| `delegations` | INT NOT NULL DEFAULT 0 | 그 트리에서 맡긴 위임 자식 수. 끝날 때 센다 |
| `writes_allowed` | BOOLEAN NOT NULL DEFAULT FALSE | 시작할 때 옮겨 적은 그 에이전트의 「먼저 살펴보기에 쓰기 도구 허용」 값. 그 살펴보기의 경계를 정한다(ADR-082) |
| `new_findings` | INT NOT NULL DEFAULT 0 | 「새로 알릴 것」 으로 그린 발견 수 |
| `reference_findings` | INT NOT NULL DEFAULT 0 | 「참고」 로 내린 발견 수 |
| `report_json` | JSON NULL | Control Plane 이 검사해 채운 다섯 칸 보고 |
| `tree_input_tokens` | BIGINT NULL | 루트와 자식 실행의 입력 토큰 합계 |
| `tree_cached_input_tokens` | BIGINT NULL | 같은 트리의 캐시 입력 토큰 합계 |
| `tree_output_tokens` | BIGINT NULL | 같은 트리의 출력 토큰 합계 |
| `skipped_reason` | VARCHAR(32) NULL | 모델 없이 끝낸 까닭. 지금은 `UNREAD_REPORT` 를 쓴다 |
| `report_opened_at` | DATETIME(6) NULL | 요청자가 보고를 연 시각. 「보고 열기」 나 점검 대화를 처음 읽거나 그 대화에 메시지를 보낸 때다 |
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

## proactive_check_problem

결과 블록 버전 3의 문제 후보 하나다([ADR-093](../../adr/ADR-093-문제-찾기는-살펴보기-결과의-문제-후보로-받고-control-plane-이-근거와-중복을-결정적으로-검사한다.md)).
받아들인 것과 버린 것을 모두 남긴다. 다음 살펴보기의 중복 판정과 입력에 쓰고, 우선순위를 정하는 다음 단계가 읽는다.
칸의 뜻과 검사는 [`proactive-check.md`](../proactive-check.md) 의 「문제 후보」 가 갖는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `check_id` | BIGINT FK `proactive_check` ON DELETE CASCADE | 이 후보를 낸 살펴보기 |
| `conversation_id` | BIGINT | 점검 대화. `proactive_check.conversation_id` 와 같다. 중복 판정을 대화로 읽으려고 둔다 |
| `status` | VARCHAR(16) | `ACCEPTED`, `DROPPED` |
| `drop_reason` | VARCHAR(32) NULL | `DROPPED` 의 까닭. `INCOMPLETE`, `NO_GOAL`, `NO_EVIDENCE`, `DUPLICATE`, `EXISTING_FOLLOW_UP` |
| `problem_key` | VARCHAR(120) | 앞뒤 공백을 지우고 소문자로 맞춘 문제 키. 비었으면 빈 글이다 |
| `problem` | VARCHAR(300) NULL | 모델이 쓴 문제 |
| `related_goal` | VARCHAR(200) NULL | 모델이 쓴 관련 목표나 맥락 |
| `action_type` | VARCHAR(16) NULL | `ACTION`, `QUESTION`. 정해진 값 밖이면 모델이 쓴 값을 잘라 둔다 |
| `action_text` | VARCHAR(200) NULL | 다음 행동이나 질문 |
| `confidence` | VARCHAR(16) NULL | `LOW`, `MEDIUM`, `HIGH`. 정해진 값 밖이면 모델이 쓴 값을 잘라 둔다 |
| `expected_benefit` | VARCHAR(300) NULL | 기대 효과 가설 |
| `side_effect` | VARCHAR(16) NULL | `NONE`, `INTERNAL`, `EXTERNAL`. 정해진 값 밖이면 모델이 쓴 값을 잘라 둔다 |
| `risk` | VARCHAR(200) NULL | 위험 힌트 |
| `change_since_last` | VARCHAR(300) NULL | 지난번과 달라진 점 |
| `evidence_json` | JSON | 근거로 받아들인 발견의 `topicKey`, `sourceUrl`, `checkedAt` 배열. 근거가 없으면 빈 배열이다 |
| `evidence_checked_at` | DATETIME(6) NULL | 근거 발견의 확인 시각 가운데 가장 이른 것. 근거가 없으면 비어 있다 |
| `created_at` | DATETIME(6) | |

색인은 `(conversation_id, status, created_at)` 이다.

후보의 글은 모델이 쓴 글이고 대화에 그리지 않는다. 다섯 칸 보고(`report_json`)처럼 검사한 모양을 그대로 남긴다.
원문 본문과 커넥터 응답은 남기지 않는다.

## `proactive_value_evaluation`

가치 평가 시도 하나다. [가치 평가](../value-evaluation.md)가 입력과 결과, replay 계약을 갖는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT PK | 평가 식별자 |
| `check_id` | BIGINT FK | 후보가 나온 살펴보기. 지우면 평가도 함께 지운다 |
| `user_id` | BIGINT FK | 요청 사용자. 관리자도 남의 것을 읽지 못한다 |
| `replay_of_id` | BIGINT, 선택 | 바로 재평가한 원본 시도 |
| `outcome` | VARCHAR(32) | `RUNNING`, `EVALUATED`, `INSUFFICIENT_EVIDENCE`, `FALLBACK`, `EMPTY` |
| `evidence_json` | JSON | 최소 후보 스냅샷·기준 시각·질문, provider 식별 정보, 축별 판단·근거 키·확신·설명·순서 |
| `created_at` | DATETIME(6) | 평가 시도를 시작한 시각 |

사용자와 시작 시각, 상태에 색인이 있다. 전체 개인 문맥과 원시 모델 응답을 저장하지 않는다.
