## ADR-20261008: 지운 대화는 정리 작업이 본문과 Hermes session 까지 지우고, 대화 줄과 실행 줄은 본문 없이 남긴다

- **status**: `accepted`
- Date: 2026-10-08
- **대체된 부분**: `docs/backend/schema/README.md` 의 「지울 때」 가 정하던 「대화를 지워도 메시지와 실행 기록과 Hermes session 은 그대로 둔다」 를 이 결정이 바꾼다.
  첨부와 결과물의 「행을 지우지 않는다」 는 보관 기간으로 지울 때에만 남고, 대화를 지울 때는 행까지 지운다.

### 결정

**사용자가 대화를 지우면 화면에서는 지금처럼 바로 사라진다(`deleted_at`).
정리 작업이 매분 지운 대화를 찾아 본문을 실제로 지우고 `conversation.purged_at` 을 적는다.**

| 대상 | 지울 때 하는 일 |
| --- | --- |
| Hermes session | 그 대화의 실행이 보낸 session 과 대화의 두 session 칸(`hermes_session_id`, `hermes_root_session_id`)을 `DELETE /api/sessions/{id}` 로 지운다. 위임한 자식 session 은 Hermes 가 함께 지운다. 404 는 이미 지운 것으로 본다 |
| 첨부 | 파일과 `chat_attachment` 행을 지운다 |
| 결과물 | 대화의 결과물 폴더를 통째로 지우고 `chat_artifact` 행을 지운다 |
| 메시지 | `chat_message`, `chat_pending_message`, `execution_question` 행을 지운다 |
| 실행 기록 | `agent_execution.output_text` 와 `execution_event.detail` 만 비운다. 실행 줄, 사건 줄, 토큰, 금액은 남긴다 |
| 대화 줄 | 남긴다. 제목과 두 session 칸을 비우고 `purged_at` 을 적는다 |

| 상황 | 동작 |
| --- | --- |
| 그 대화에 `RUNNING` 실행이 있다 | 미룬다. 도는 실행은 Hermes session 을 이어 쓴다 |
| 그 대화 실행의 자식 사용량 작업(`subagent_usage_job`)이 `WAITING` 이다 | 미룬다. session 을 먼저 지우면 자식 사용량을 읽지 못한다 |
| Hermes 에 닿지 못했거나 파일을 지우지 못했다 | 그 대화는 `purged_at` 없이 남는다. 다음 차례에 처음부터 다시 한다. 실패가 이어지면 기다리는 간격을 1분부터 한 시간까지 두 배씩 늘린다 |
| 지운 에이전트가 관리하던 profile 이라 profile key 가 없다 | profile 과 함께 state.db 가 지워졌으므로 그 session 은 건너뛴다 |
| 실행에 에이전트가 없어 API 주소를 모른다 | 경고 로그를 남기고 그 session 만 건너뛴다 |
| 정리한 대화에 늦게 끝난 실행의 답이 온다 | 메시지 저장이 `CONVERSATION_NOT_FOUND` 로 거절한다. 지운 대화에 평문이 다시 쌓이지 않는다 |
| 이 결정 앞에 지운 대화 | 같은 정리 대상이다. 배포한 뒤 첫 차례들이 지운 순서대로 지운다 |

데이터베이스 쪽은 대화 줄을 메시지 저장과 같은 쓰기 잠금으로 잡고 한 트랜잭션으로 지운다.
Hermes session 과 파일은 그 트랜잭션 앞에서 지운다. 트랜잭션이 실패해도 다시 지우면 그대로 끝나는 일들이다.

### 맥락

가족 밖의 친구가 쓰기 시작했다. `docs/privacy.md` 는 「대화를 지우면 화면에서 사라지지만 메시지 기록은 그 서버의 데이터베이스에 남는다」 고 적고 있었다.
지운 대화의 본문이 메시지 표, 첨부와 결과물 파일, 실행 기록, Hermes `state.db` 의 session 과 메시지에 그대로 남았다.

운영 Hermes(`v2026.9.24`)의 API server 는 `DELETE /api/sessions/{session_id}` 를 연다(`gateway/platforms/api_server.py` 의 `_handle_delete_session`).
`SessionDB.delete_session` 이 그 session 의 메시지와 위임 자식 session 을 한 쓰기 트랜잭션에서 지운다.
core 를 고치지 않고 이 경로만 부른다([ADR-001](ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md)).

### 대안 기각

- **대화 줄과 실행 줄까지 지운다**: 사용량 화면은 지운 대화의 실행도 센다. 돈은 이미 나갔다.
  실행 줄을 지우면 월 합계가 줄고, `proactive_check` 같은 표의 외래 키가 대화 줄을 가리킨다.
  본문만 비우면 합계와 참조가 그대로다.
- **유예 기간을 두고 지운다**: 지운 대화를 되살리는 기능이 없다. 기다리는 동안 본문만 남는다.
- **삭제 요청 안에서 바로 지운다**: Hermes 가 느리거나 꺼져 있으면 화면의 삭제가 실패한다.
  도는 실행이나 자식 사용량을 기다릴 수도 없다. 화면은 바로 숨기고 정리는 따로 재시도한다.
- **Hermes 의 보존 기간(`sessions.retention_days`)에 맡긴다**: 끝난 session 만 기간이 지나야 지운다. 사용자가 지운 대화를 바로 지우지 못한다.
- **실패 횟수를 표에 적는다**: 칸 둘과 마이그레이션이 는다. 재기동하면 간격이 처음으로 돌아갈 뿐이고 지우는 일은 다시 시도한다.

### 결과

- 얻는 것: 지운 대화의 메시지, 첨부, 결과물, 실행 본문, Hermes session 이 몇 분 안에 서버에서 사라진다.
  사용량 합계와 실행 줄을 가리키는 다른 표는 그대로다.
- 감당할 것:
  - **데이터베이스의 지운 줄은 백업에 남는다.** 백업을 언제 버리는지는 운영 저장소 `fos-home-infra` 가 정한다.
  - **Hermes 의 압축과 분기로 생긴 session 은 남을 수 있다.** `delete_session` 은 위임 자식만 함께 지우고 압축과 분기 자식은 부모 칸만 비운다.
    실행이 보낸 session 은 모두 지우므로 이어 쓴 session 은 지워진다. 실행에 적히지 않은 session 은 Hermes 의 보존 기간이 지운다.
  - **모델 공급자 호출이 실패한 요청의 덤프 파일은 남는다.** Hermes 는 그때 요청 본문 전체를 profile 의 `sessions/request_dump_<session>_*.json` 에 쓴다(비밀값만 가린다, `agent/agent_runtime_helpers.py`).
    API server 의 삭제는 `delete_session` 을 `sessions_dir` 없이 불러 이 파일을 지우지 않는다. 운영의 정기 정리가 지운다.
  - **다른 표의 본문은 남는다.** `connector_action` 의 인자와 결과, `memory_capture`, `follow_up`, `notification` 의 글은 이 결정에서 지우지 않는다. 저장 시 암호화의 다음 단계가 다룬다.
  - **SQLite 와 MySQL 은 지운 줄을 파일에서 바로 덮어쓰지 않는다.** 빈 페이지가 다시 쓰이거나 VACUUM 이 돌 때까지 디스크에 남을 수 있다. 디스크 파일을 복사한 사람은 읽을 수 있다.
  - 정리 작업이 기다리는 간격은 메모리에만 있다. 재기동하면 실패하던 대화를 곧바로 다시 시도한다.
