# 판단 피드백

판단 피드백 사건을 저장하는 표의 칸과 제약을 갖는다.
사건의 뜻, 기록 지점, 반응 읽기, 보관은 [`../decision-feedback.md`](../decision-feedback.md) 가 갖는다.

## decision_feedback_event

제안 하나의 사건 한 줄이다. 덧붙이기만 하고 고치지 않는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT PK | |
| `user_id` | BIGINT FK `app_user` ON DELETE CASCADE | 기록의 주인. 다른 사용자는 관리자여도 읽지 못한다 |
| `subject_type` | VARCHAR(24) | `FOLLOW_UP`, `MEMORY`, `CONNECTOR_ACTION`, `CHECK`, `AUTONOMY_DECISION` |
| `subject_key` | VARCHAR(80) | 제안의 열쇠. 지금 화면의 `itemKey` 와 같은 모양이다. 같은 제안의 사건을 잇는 correlation key 다 |
| `event_type` | VARCHAR(24) | `SURFACED`, `ACCEPTED`, `DISMISSED`, `POSTPONED`, `EDITED`, `APPROVED`, `REJECTED`, `EXECUTION_SUCCEEDED`, `EXECUTION_FAILED` |
| `actor` | VARCHAR(16) | `USER`, `AGENT`, `SYSTEM` |
| `conversation_id` | BIGINT NULL FK `conversation` ON DELETE CASCADE | 제안이 나온 대화. 대화를 지우면 서비스가 이 칸으로 사건을 지운다 |
| `origin_execution_id` | BIGINT NULL FK `agent_execution` ON DELETE SET NULL | 제안을 낸 실행 트리의 루트. 살펴보기 트리면 그 살펴보기의 `root_execution_id` 와 같다 |
| `source_check_id` | BIGINT NULL FK `proactive_check` ON DELETE SET NULL | 사건이 직접 가리키는 살펴보기 |
| `autonomy_decision_id` | BIGINT NULL FK `proactive_autonomy_decision` ON DELETE SET NULL | 사건이 직접 가리키는 행동 정책 판정 |
| `subject_version` | VARCHAR(64) NULL | 그때 제안의 판. 할 일은 `title_key`, Memory 는 판 번호, 승인 줄은 인자 해시다 |
| `reason_code` | VARCHAR(64) NULL | 정형 까닭. 어느 단추였는지(`ATTENTION_HIDE`, `DIRECT_CREATE` 등)나 실패 코드다 |
| `changed_fields` | VARCHAR(64) NULL | `EDITED` 에서 바뀐 칸 이름을 쉼표로 이은 것. 값은 담지 않는다 |
| `contract_version` | INT | 이 표의 계약 버전. 지금은 1 |
| `occurred_at` | DATETIME(6) | 사건이 일어난 시각 |

색인은 `(user_id, occurred_at)`, `(user_id, subject_key)`, `conversation_id`, `occurred_at` 이다.
앞의 둘은 읽기 모델이, 셋째는 대화 삭제가, 넷째는 보관 기간 정리가 쓴다.

제목, 본문, 인자, 결과 글, 모델이 쓴 글을 담는 칸이 없다.
